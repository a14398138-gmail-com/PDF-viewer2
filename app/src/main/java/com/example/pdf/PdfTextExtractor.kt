package com.example.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.contentstream.PDFGraphicsStreamEngine
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImage
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.io.StringWriter
import kotlin.math.max
import kotlin.math.min

data class TextWord(
    val index: Int,
    val text: String,
    val relX: Float, // Normalized left (0.0 .. 1.0)
    val relY: Float, // Normalized top (0.0 .. 1.0)
    val relWidth: Float, // Normalized width (0.0 .. 1.0)
    val relHeight: Float, // Normalized height (0.0 .. 1.0)
    val lineIndex: Int
)

data class TextLine(
    val lineIndex: Int,
    val text: String,
    val relX: Float, // Normalized left (0.0 .. 1.0)
    val relY: Float, // Normalized top (0.0 .. 1.0)
    val relWidth: Float, // Normalized width (0.0 .. 1.0)
    val relHeight: Float, // Normalized height (0.0 .. 1.0)
    val isBold: Boolean = false,
    val words: List<TextWord> = emptyList()
)

/** An axis-aligned rectangle in normalized page coordinates (0..1). */
data class RelRect(
    val relX: Float,
    val relY: Float,
    val relWidth: Float,
    val relHeight: Float
)

data class PageText(
    val pageIndex: Int,
    val fullText: String,
    val paragraphs: List<String> = emptyList(),
    val lines: List<TextLine> = emptyList(),
    val words: List<TextWord> = emptyList(),
    val isNativeTextLayer: Boolean = false,
    /** Placed-image rectangles (XObject and inline images) in normalized
     *  page coordinates. Text sitting on top of these is figure content,
     *  not body text; the block extractor excludes it from translation. */
    val imageRects: List<RelRect> = emptyList(),
    val lowerText: String = fullText.lowercase()
)

data class SearchMatch(
    val pageIndex: Int,
    val snippet: String,
    val charOffset: Int
)

/**
 * An AutoCloseable session that keeps the PDDocument open across multiple page extractions
 * avoiding repeated full PDF file parsing.
 */
class PdfTextExtractionSession(
    val file: File,
    private val doc: PDDocument?
) : AutoCloseable {
    val numberOfPages: Int = doc?.numberOfPages ?: 0

    fun extractNativeText(pageIndex: Int): PageText? {
        val document = doc ?: return null
        return PdfTextExtractor.extractNativeTextFromDoc(document, pageIndex)
    }

    override fun close() {
        try {
            doc?.close()
        } catch (e: Exception) {
            Log.w("PdfExtractionSession", "Error closing PDDocument: ${e.message}")
        }
    }
}

/**
 * Walks a page's content stream and records the user-space bounding box of
 * every drawn image (the current transformation matrix maps the unit
 * square onto the image's placement rectangle). All path/paint callbacks
 * are no-ops; only image placements are of interest.
 */
private class ImageRectCollector(page: PDPage) : PDFGraphicsStreamEngine(page) {

    /** [minX, minY, maxX, maxY] in page user space (y-up). */
    val userRects = mutableListOf<FloatArray>()

    override fun drawImage(image: PDImage) {
        try {
            val ctm = graphicsState.currentTransformationMatrix
            val corners = listOf(
                ctm.transformPoint(0f, 0f),
                ctm.transformPoint(1f, 0f),
                ctm.transformPoint(1f, 1f),
                ctm.transformPoint(0f, 1f)
            )
            val minX = corners.minOf { it.x }
            val minY = corners.minOf { it.y }
            val maxX = corners.maxOf { it.x }
            val maxY = corners.maxOf { it.y }
            if (maxX > minX && maxY > minY) {
                userRects.add(floatArrayOf(minX, minY, maxX, maxY))
            }
        } catch (e: Exception) {
            // A single bad placement must not lose the rest of the page.
        }
    }

    override fun appendRectangle(
        p0: android.graphics.PointF,
        p1: android.graphics.PointF,
        p2: android.graphics.PointF,
        p3: android.graphics.PointF
    ) = Unit

    override fun clip(rule: android.graphics.Path.FillType) = Unit
    override fun moveTo(x: Float, y: Float) = Unit
    override fun lineTo(x: Float, y: Float) = Unit
    override fun curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) = Unit
    override fun getCurrentPoint(): android.graphics.PointF = android.graphics.PointF(0f, 0f)
    override fun closePath() = Unit
    override fun endPath() = Unit
    override fun strokePath() = Unit
    override fun fillPath(rule: android.graphics.Path.FillType) = Unit
    override fun fillAndStrokePath(rule: android.graphics.Path.FillType) = Unit
    override fun shadingFill(shadingName: COSName) = Unit
}

private data class ExtractedWord(
    val text: String,
    val relX: Float,
    val relY: Float,
    val relWidth: Float,
    val relHeight: Float
)

object PdfTextExtractor {
    private const val TAG = "PdfTextExtractor"
    private var isPdfBoxInitialized = false

    var onOcrModelStatusListener: ((isPreparing: Boolean, message: String?) -> Unit)? = null

    // Japanese recognizer (handles Japanese plus Latin characters) is primary:
    // this app's documents are mostly Japanese lecture/paper material, and the
    // previous Latin-only client made OCR of scanned Japanese PDFs unusable.
    private val japaneseRecognizer: TextRecognizer by lazy {
        try {
            TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        } catch (e: Throwable) {
            Log.w(TAG, "Japanese recognizer unavailable, falling back to Latin: ${e.message}")
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        }
    }

    private val latinRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    fun init(context: Context) {
        if (!isPdfBoxInitialized) {
            try {
                PDFBoxResourceLoader.init(context.applicationContext)
                isPdfBoxInitialized = true
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing PDFBox: ${e.message}")
            }
        }
    }

    /**
     * Creates an AutoCloseable extraction session with a single loaded PDDocument instance.
     */
    fun openSession(file: File): PdfTextExtractionSession {
        if (!file.exists()) return PdfTextExtractionSession(file, null)
        return try {
            val doc = PDDocument.load(file)
            PdfTextExtractionSession(file, doc)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load PDDocument for session: ${e.message}")
            PdfTextExtractionSession(file, null)
        }
    }

    /**
     * Glyph box [left, top, right, bottom] of one [TextPosition] in page
     * user-space points. The vertical extent comes from the font's
     * ascent/descent (scaled by the em size, for which heightDir is the
     * display-space proxy) instead of assuming the whole em square sits
     * above the baseline — the old assumption under-covered large display
     * type, whose glyphs then peeked out from under the translation
     * overlay's erase rectangle. Falls back to the old formula when the
     * font carries no usable descriptor metrics.
     */
    private fun glyphBox(tp: TextPosition): FloatArray {
        val baseline = tp.yDirAdj
        var top = baseline - tp.heightDir
        var bottom = baseline
        try {
            val descriptor = tp.font?.fontDescriptor
            val em = tp.heightDir
            if (descriptor != null && em > 0f) {
                val ascent = descriptor.ascent
                val descent = descriptor.descent
                if (ascent > 0f && descent < ascent) {
                    // Descriptor metrics are in 1000-unit glyph space.
                    val ascTop = baseline - em * (ascent / 1000f)
                    val descBottom = baseline - em * (descent / 1000f)
                    if (descBottom > ascTop) {
                        top = ascTop
                        bottom = descBottom
                    }
                }
            }
        } catch (e: Exception) {
            // Keep the fallback box.
        }
        return floatArrayOf(tp.xDirAdj, top, tp.xDirAdj + tp.widthDirAdj, bottom)
    }

    /**
     * Collects the placed-image rectangles of [page] (image XObjects and
     * inline images, including images nested in form XObjects) as
     * normalized [RelRect]s. Rectangles covering almost the whole page are
     * dropped: they are background/watermark art, and treating them as
     * figures would exclude every line of text on the page.
     */
    private fun collectPageImageRects(page: PDPage, cropBox: PDRectangle): List<RelRect> {
        val pWidth = cropBox.width.coerceAtLeast(1f)
        val pHeight = cropBox.height.coerceAtLeast(1f)
        val llx = cropBox.lowerLeftX
        val lly = cropBox.lowerLeftY
        return try {
            val collector = ImageRectCollector(page)
            collector.processPage(page)
            collector.userRects.mapNotNull { r ->
                val relX = ((r[0] - llx) / pWidth).coerceIn(0f, 1f)
                val relW = ((r[2] - r[0]) / pWidth).coerceIn(0f, 1f)
                val relH = ((r[3] - r[1]) / pHeight).coerceIn(0f, 1f)
                // User space is y-up; normalized page space is y-down.
                val relY = (1f - ((r[3] - lly) / pHeight)).coerceIn(0f, 1f)
                if (relW <= 0f || relH <= 0f || relW * relH > 0.85f) {
                    null
                } else {
                    RelRect(relX, relY, relW, relH)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Image rectangle collection failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Extracts text and exact word bounding boxes natively from an already opened PDDocument instance.
     */
    fun extractNativeTextFromDoc(doc: PDDocument, pageIndex: Int): PageText? {
        return try {
            if (pageIndex < 0 || pageIndex >= doc.numberOfPages) return null
            val page = doc.getPage(pageIndex)
            val cropBox = page.cropBox ?: page.mediaBox ?: return null
            val pWidth = cropBox.width.coerceAtLeast(1f)
            val pHeight = cropBox.height.coerceAtLeast(1f)

            val words = mutableListOf<TextWord>()
            val lines = mutableListOf<TextLine>()
            val fullTextSb = StringBuilder()
            var wordGlobalIdx = 0
            var lineGlobalIdx = 0

            val stripper = object : PDFTextStripper() {
                init {
                    startPage = pageIndex + 1
                    endPage = pageIndex + 1
                    sortByPosition = true
                }

                override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
                    if (text.isBlank() || textPositions.isEmpty()) return

                    var curWordSb = StringBuilder()
                    var curWordLeft = Float.MAX_VALUE
                    var curWordTop = Float.MAX_VALUE
                    var curWordRight = Float.MIN_VALUE
                    var curWordBottom = Float.MIN_VALUE

                    val rawWords = mutableListOf<ExtractedWord>()

                    fun flushWord() {
                        if (curWordSb.isNotEmpty() && curWordLeft < curWordRight && curWordTop < curWordBottom) {
                            val relX = (curWordLeft / pWidth).coerceIn(0f, 1f)
                            val relY = (curWordTop / pHeight).coerceIn(0f, 1f)
                            val relW = ((curWordRight - curWordLeft) / pWidth).coerceIn(0.005f, 1f)
                            val relH = ((curWordBottom - curWordTop) / pHeight).coerceIn(0.005f, 1f)

                            rawWords.add(
                                ExtractedWord(
                                    text = curWordSb.toString(),
                                    relX = relX,
                                    relY = relY,
                                    relWidth = relW,
                                    relHeight = relH
                                )
                            )
                        }
                        curWordSb = StringBuilder()
                        curWordLeft = Float.MAX_VALUE
                        curWordTop = Float.MAX_VALUE
                        curWordRight = Float.MIN_VALUE
                        curWordBottom = Float.MIN_VALUE
                    }

                    for (tp in textPositions) {
                        val str = tp.unicode ?: ""
                        for (ch in str) {
                            if (ch.isWhitespace()) {
                                flushWord()
                            } else if (isCjkChar(ch)) {
                                // Flush any preceding non-CJK accumulated word
                                flushWord()

                                // Create individual 1-character TextWord for Japanese/CJK
                                val box = glyphBox(tp)
                                val x = box[0]
                                val top = box[1]
                                val right = box[2]
                                val bottom = box[3]

                                val relX = (x / pWidth).coerceIn(0f, 1f)
                                val relY = (top / pHeight).coerceIn(0f, 1f)
                                val relW = ((right - x) / pWidth).coerceIn(0.005f, 1f)
                                val relH = ((bottom - top) / pHeight).coerceIn(0.005f, 1f)

                                rawWords.add(
                                    ExtractedWord(
                                        text = ch.toString(),
                                        relX = relX,
                                        relY = relY,
                                        relWidth = relW,
                                        relHeight = relH
                                    )
                                )
                            } else {
                                // Accumulate English / alphanumeric words
                                curWordSb.append(ch)
                                val box = glyphBox(tp)
                                val x = box[0]
                                val top = box[1]
                                val right = box[2]
                                val bottom = box[3]

                                curWordLeft = min(curWordLeft, x)
                                curWordTop = min(curWordTop, top)
                                curWordRight = max(curWordRight, right)
                                curWordBottom = max(curWordBottom, bottom)
                            }
                        }
                    }
                    flushWord()

                    if (rawWords.isEmpty()) return

                    // Sort words left-to-right
                    val sortedRaw = rawWords.sortedBy { it.relX }

                    // Segment line on large horizontal gaps (e.g. column gutters between title & standfirst)
                    val segments = mutableListOf<MutableList<ExtractedWord>>()
                    var currentSegment = mutableListOf<ExtractedWord>()

                    for (w in sortedRaw) {
                        if (currentSegment.isEmpty()) {
                            currentSegment.add(w)
                        } else {
                            val prev = currentSegment.last()
                            val gap = w.relX - (prev.relX + prev.relWidth)
                            val maxH = max(prev.relHeight, w.relHeight)
                            val gapThreshold = max(0.025f, maxH * 1.2f)
                            if (gap >= gapThreshold) {
                                segments.add(currentSegment)
                                currentSegment = mutableListOf(w)
                            } else {
                                currentSegment.add(w)
                            }
                        }
                    }
                    if (currentSegment.isNotEmpty()) {
                        segments.add(currentSegment)
                    }

                    for (seg in segments) {
                        val segWords = seg.map { ew ->
                            val tw = TextWord(
                                index = wordGlobalIdx++,
                                text = ew.text,
                                relX = ew.relX,
                                relY = ew.relY,
                                relWidth = ew.relWidth,
                                relHeight = ew.relHeight,
                                lineIndex = lineGlobalIdx
                            )
                            words.add(tw)
                            tw
                        }
                        val segText = joinWords(segWords)
                        val lineLeft = segWords.minOf { it.relX }
                        val lineTop = segWords.minOf { it.relY }
                        val lineRight = segWords.maxOf { it.relX + it.relWidth }
                        val lineBottom = segWords.maxOf { it.relY + it.relHeight }

                        lines.add(
                            TextLine(
                                lineIndex = lineGlobalIdx,
                                text = segText,
                                relX = lineLeft,
                                relY = lineTop,
                                relWidth = lineRight - lineLeft,
                                relHeight = lineBottom - lineTop,
                                words = segWords
                            )
                        )
                        lineGlobalIdx++
                        fullTextSb.append(segText).append("\n")
                    }
                }
            }

            stripper.writeText(doc, StringWriter())

            val fullText = fullTextSb.toString().trim()
            if (words.isNotEmpty() && fullText.isNotBlank()) {
                PageText(
                    pageIndex = pageIndex,
                    fullText = fullText,
                    paragraphs = listOf(fullText),
                    lines = lines,
                    words = words,
                    isNativeTextLayer = true,
                    imageRects = collectPageImageRects(page, cropBox)
                )
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Native text extraction skipped or failed for page $pageIndex: ${e.message}")
            null
        }
    }

    /**
     * Extracts text and exact word bounding boxes natively from PDF file (single page convenience).
     */
    fun extractNativeTextFromPdf(file: File, pageIndex: Int): PageText? {
        if (!file.exists()) return null
        return try {
            PDDocument.load(file).use { doc ->
                extractNativeTextFromDoc(doc, pageIndex)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Native text extraction skipped or failed for page $pageIndex: ${e.message}")
            null
        }
    }

    suspend fun extractPageFromBitmap(bitmap: Bitmap, pageIndex: Int): PageText = withContext(Dispatchers.Default) {
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            // Non-blocking coroutine await using kotlinx-coroutines-play-services.
            // Japanese model first; if it finds nothing, retry with the Latin model
            // (covers purely Latin documents the Japanese model may miss).
            var result = japaneseRecognizer.process(image).await()
            if (result.text.isBlank()) {
                try {
                    val latinResult = latinRecognizer.process(image).await()
                    if (latinResult.text.isNotBlank()) result = latinResult
                } catch (e: Exception) {
                    Log.w(TAG, "Latin OCR fallback failed on page $pageIndex: ${e.message}")
                }
            }

            val lines = mutableListOf<TextLine>()
            val allWords = mutableListOf<TextWord>()
            val paragraphs = mutableListOf<String>()
            val bWidth = bitmap.width.toFloat().coerceAtLeast(1f)
            val bHeight = bitmap.height.toFloat().coerceAtLeast(1f)

            var wordGlobalIndex = 0
            var lineGlobalIndex = 0

            for (block in result.textBlocks) {
                if (block.text.isNotBlank()) {
                    paragraphs.add(block.text)
                }
                for (line in block.lines) {
                    val rect = line.boundingBox ?: Rect(0, 0, 0, 0)
                    val relX = (rect.left.toFloat() / bWidth).coerceIn(0f, 1f)
                    val relY = (rect.top.toFloat() / bHeight).coerceIn(0f, 1f)
                    val relWidth = (rect.width().toFloat() / bWidth).coerceIn(0f, 1f)
                    val relHeight = (rect.height().toFloat() / bHeight).coerceIn(0f, 1f)

                    val lineWords = mutableListOf<TextWord>()
                    for (element in line.elements) {
                        val elemRect = element.boundingBox ?: Rect(0, 0, 0, 0)
                        val elemRelX = (elemRect.left.toFloat() / bWidth).coerceIn(0f, 1f)
                        val elemRelY = (elemRect.top.toFloat() / bHeight).coerceIn(0f, 1f)
                        val elemRelW = (elemRect.width().toFloat() / bWidth).coerceIn(0f, 1f)
                        val elemRelH = (elemRect.height().toFloat() / bHeight).coerceIn(0f, 1f)

                        val elemText = element.text
                        val hasCjk = elemText.any { isCjkChar(it) }

                        if (hasCjk && elemText.length > 1) {
                            // Split Japanese/CJK element into individual character words with proportional widths
                            val charCount = elemText.length
                            val charWidth = elemRelW / charCount.toFloat()
                            for (cIdx in 0 until charCount) {
                                val ch = elemText[cIdx]
                                val word = TextWord(
                                    index = wordGlobalIndex++,
                                    text = ch.toString(),
                                    relX = (elemRelX + cIdx * charWidth).coerceIn(0f, 1f),
                                    relY = elemRelY,
                                    relWidth = charWidth.coerceIn(0.005f, 1f),
                                    relHeight = elemRelH,
                                    lineIndex = lineGlobalIndex
                                )
                                lineWords.add(word)
                                allWords.add(word)
                            }
                        } else {
                            val word = TextWord(
                                index = wordGlobalIndex++,
                                text = elemText,
                                relX = elemRelX,
                                relY = elemRelY,
                                relWidth = elemRelW,
                                relHeight = elemRelH,
                                lineIndex = lineGlobalIndex
                            )
                            lineWords.add(word)
                            allWords.add(word)
                        }
                    }

                    if (lineWords.isEmpty() && line.text.isNotBlank()) {
                        val lineText = line.text
                        val hasCjk = lineText.any { isCjkChar(it) }
                        if (hasCjk && lineText.length > 1) {
                            val charCount = lineText.length
                            val charWidth = relWidth / charCount.toFloat()
                            for (cIdx in 0 until charCount) {
                                val ch = lineText[cIdx]
                                val word = TextWord(
                                    index = wordGlobalIndex++,
                                    text = ch.toString(),
                                    relX = (relX + cIdx * charWidth).coerceIn(0f, 1f),
                                    relY = relY,
                                    relWidth = charWidth.coerceIn(0.005f, 1f),
                                    relHeight = relHeight,
                                    lineIndex = lineGlobalIndex
                                )
                                lineWords.add(word)
                                allWords.add(word)
                            }
                        } else {
                            val word = TextWord(
                                index = wordGlobalIndex++,
                                text = lineText,
                                relX = relX,
                                relY = relY,
                                relWidth = relWidth,
                                relHeight = relHeight,
                                lineIndex = lineGlobalIndex
                            )
                            lineWords.add(word)
                            allWords.add(word)
                        }
                    }

                    lines.add(
                        TextLine(
                            lineIndex = lineGlobalIndex,
                            text = line.text,
                            relX = relX,
                            relY = relY,
                            relWidth = relWidth,
                            relHeight = relHeight,
                            words = lineWords
                        )
                    )
                    lineGlobalIndex++
                }
            }

            PageText(
                pageIndex = pageIndex,
                fullText = result.text,
                paragraphs = paragraphs,
                lines = lines,
                words = allWords,
                isNativeTextLayer = false
            )
        } catch (e: Exception) {
            // Only treat the failure as "model preparing" when the message actually
            // says so. Almost every ML Kit failure is an MlKitException, so the old
            // class-name check mislabelled genuine recognition failures as
            // "preparing" forever.
            val msg = e.message ?: ""
            val isDownloading = msg.contains("download", ignoreCase = true) ||
                    msg.contains("waiting", ignoreCase = true) ||
                    msg.contains("module", ignoreCase = true) && msg.contains("unavailable", ignoreCase = true)

            if (isDownloading) {
                Log.w(TAG, "ML Kit OCR model is preparing or downloading in background: ${e.message}", e)
                onOcrModelStatusListener?.invoke(true, "OCRの準備中です")
            } else {
                Log.e(TAG, "ML Kit recognition failed on page $pageIndex: ${e.message}", e)
                onOcrModelStatusListener?.invoke(false, null)
            }
            PageText(
                pageIndex = pageIndex,
                fullText = "",
                paragraphs = emptyList(),
                lines = emptyList(),
                words = emptyList()
            )
        }
    }

    fun isCjkChar(c: Char): Boolean {
        val ub = Character.UnicodeBlock.of(c)
        return ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
                ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B ||
                ub == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
                ub == Character.UnicodeBlock.HIRAGANA ||
                ub == Character.UnicodeBlock.KATAKANA ||
                ub == Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS ||
                ub == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
                ub == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
    }

    /**
     * Joins a list of TextWord instances into flowing natural text:
     * - Preserves spaces between Latin/alphanumeric words
     * - Joins CJK characters without spaces
     * - Reconnects hyphenated words across line wraps (e.g. "inter-" + "national" -> "international")
     */
    fun joinWords(words: List<TextWord>): String {
        if (words.isEmpty()) return ""
        val sb = StringBuilder()
        for (i in words.indices) {
            val cur = words[i]
            if (i > 0) {
                val prev = words[i - 1]
                val prevLastChar = prev.text.lastOrNull()
                val curFirstChar = cur.text.firstOrNull()
                val isPrevCjk = prevLastChar != null && isCjkChar(prevLastChar)
                val isCurCjk = curFirstChar != null && isCjkChar(curFirstChar)

                // 行またぎであっても改行を挿入せず、自然な文章として連結
                if (prev.text.endsWith("-") && !isPrevCjk && !isCurCjk) {
                    if (!prev.text.dropLast(1).contains('-')) {
                        // 単語末尾のハイフンによる行またぎ（例: "inter-" + "national" -> "international"）を結合
                        sb.setLength(sb.length - 1)
                    } else {
                        // 複合語ハイフン（例: "blue-light-" + "mediated" -> "blue-light-mediated"）はハイフンを維持
                    }
                } else if (!isPrevCjk && !isCurCjk) {
                    // 英数字・欧文単語間のスペース
                    sb.append(" ")
                }
            }
            sb.append(cur.text)
        }
        return sb.toString()
    }

    fun formatSelectedWords(words: List<TextWord>, indices: Set<Int>): String {
        val sorted = indices.sorted().mapNotNull { words.getOrNull(it) }
        return joinWords(sorted)
    }

    /**
     * Cleans up unwanted line-breaks in multi-line extracted text (for CJK & continuous sentences).
     */
    fun cleanPdfExtractedText(raw: String): String {
        if (raw.isBlank()) return ""
        // Replace single line breaks while preserving double newlines (paragraphs)
        val paragraphs = raw.split("\n\n")
        return paragraphs.joinToString("\n\n") { p ->
            val lines = p.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val sb = StringBuilder()
            for (i in lines.indices) {
                val line = lines[i]
                if (i > 0) {
                    val prev = lines[i - 1]
                    val prevLast = prev.lastOrNull()
                    val curFirst = line.firstOrNull()
                    val isPrevCjk = prevLast != null && isCjkChar(prevLast)
                    val isCurCjk = curFirst != null && isCjkChar(curFirst)

                    if (prev.endsWith("-") && !isPrevCjk && !isCurCjk) {
                        if (!prev.dropLast(1).contains('-')) {
                            sb.setLength(sb.length - 1)
                        }
                    } else if (!isPrevCjk && !isCurCjk) {
                        sb.append(" ")
                    }
                }
                sb.append(line)
            }
            sb.toString()
        }
    }

    /**
     * Highly optimized search that uses the pre-cached `lowerText` of each PageText.
     */
    fun search(pages: List<PageText>, query: String): List<SearchMatch> {
        if (query.isBlank()) return emptyList()
        val lowerQuery = query.lowercase().trim()
        if (lowerQuery.isEmpty()) return emptyList()
        val matches = mutableListOf<SearchMatch>()

        for (page in pages) {
            val lowerText = page.lowerText
            if (!lowerText.contains(lowerQuery)) continue

            val originalText = page.fullText
            var startIndex = 0
            while (true) {
                val found = lowerText.indexOf(lowerQuery, startIndex)
                if (found == -1) break

                val snippetStart = (found - 30).coerceAtLeast(0)
                val snippetEnd = (found + query.length + 30).coerceAtMost(originalText.length)
                var snippet = originalText.substring(snippetStart, snippetEnd).trim()
                if (snippetStart > 0) snippet = "...$snippet"
                if (snippetEnd < originalText.length) snippet = "$snippet..."

                matches.add(
                    SearchMatch(
                        pageIndex = page.pageIndex,
                        snippet = snippet,
                        charOffset = found
                    )
                )
                startIndex = found + 1
            }
        }
        return matches
    }

    /**
     * Ordinal (0-based) of [match] among all occurrences of [query] on its page,
     * derived from the match's char offset into the page's full text.
     */
    fun occurrenceOrdinal(page: PageText, match: SearchMatch, query: String): Int {
        val lowerQuery = query.lowercase().trim()
        if (lowerQuery.isEmpty()) return 0
        var count = 0
        var idx = page.lowerText.indexOf(lowerQuery)
        while (idx != -1 && idx < match.charOffset) {
            count++
            idx = page.lowerText.indexOf(lowerQuery, idx + 1)
        }
        return count
    }

    /**
     * Maps a search hit to the indices of the words it spans, so the page view can
     * highlight the hit in place. Words are re-joined with the same rules as
     * [formatSelectedWords] (CJK characters without spaces, Latin words with a
     * space) and the [occurrenceOrdinal]-th occurrence of the query in that
     * joined text is selected. This is an approximation for OCR pages whose
     * block layout differs from the word stream, but it is exact for the common
     * single-line and CJK cases.
     */
    fun searchHighlightIndices(page: PageText, query: String, occurrenceOrdinal: Int): Set<Int> {
        val lowerQuery = query.lowercase().trim()
        if (lowerQuery.isEmpty() || page.words.isEmpty()) return emptySet()
        val words = page.words.sortedBy { it.index }
        val sb = StringBuilder()
        val starts = IntArray(words.size)
        val ends = IntArray(words.size)
        for (i in words.indices) {
            val cur = words[i]
            if (i > 0) {
                val prev = words[i - 1]
                val prevLast = prev.text.lastOrNull()
                val curFirst = cur.text.firstOrNull()
                val isPrevCjk = prevLast != null && isCjkChar(prevLast)
                val isCurCjk = curFirst != null && isCjkChar(curFirst)
                if (prev.text.endsWith("-") && !isPrevCjk && !isCurCjk) {
                    if (!prev.text.dropLast(1).contains('-')) {
                        sb.setLength(sb.length - 1)
                    }
                } else if (!isPrevCjk && !isCurCjk) {
                    sb.append(" ")
                }
            }
            starts[i] = sb.length
            sb.append(cur.text)
            ends[i] = sb.length
        }
        val joined = sb.toString().lowercase()
        var idx = joined.indexOf(lowerQuery)
        var ordinal = 0
        while (idx != -1 && ordinal < occurrenceOrdinal) {
            idx = joined.indexOf(lowerQuery, idx + 1)
            ordinal++
        }
        if (idx == -1) return emptySet()
        val hitEnd = idx + lowerQuery.length
        val result = mutableSetOf<Int>()
        for (i in words.indices) {
            if (ends[i] > idx && starts[i] < hitEnd) {
                result.add(words[i].index)
            }
        }
        return result
    }
}
