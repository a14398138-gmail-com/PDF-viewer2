package com.example.pdf

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A layout block: a run of lines that belong together (a paragraph, a heading,
 * a figure caption, one column segment). Coordinates are normalized to the
 * page (0..1), using the same convention as [TextWord]/[TextLine].
 */
data class TextBlock(
    val blockIndex: Int,
    val text: String,
    val relX: Float,
    val relY: Float,
    val relWidth: Float,
    val relHeight: Float,
    val lineCount: Int,
    /** Mean height of the block's lines (relative to page height). The
     *  renderer derives the translation font size and the erase padding
     *  from this so both scale with the source typography. */
    val avgLineHeight: Float = 0f
)

/** A [TextBlock] plus its translation, for the overlay renderer. */
data class TranslatedBlock(
    val block: TextBlock,
    val translatedText: String
)

/**
 * Groups the lines of a natively-extracted page into layout blocks without
 * breaking multi-column layouts:
 *
 * 1. A vertical "gutter" (an x-band almost no line crosses) splits the page
 *    into columns; lines are assigned to a column by their centre.
 * 2. Lines that cross the whole gutter (titles, author lines, a full-width
 *    abstract) are pulled out as full-width lines first — forcing them into
 *    one column used to produce blocks whose rectangles overlapped the other
 *    column and garbled the overlay around titles.
 * 3. Inside a column, lines are visited top-to-bottom and a new block starts
 *    when the vertical gap is large, the line height changes a lot (heading /
 *    caption vs. body), or the horizontal overlap with the running block is
 *    tiny (e.g. a centred caption under a figure).
 *
 * This is a pure function of [PageText] so it is unit-testable and reuses the
 * existing extraction pipeline (and its disk cache) instead of re-parsing the
 * PDF with PDFBox.
 */
object PdfBlockExtractor {

    fun blocksFromPageText(page: PageText): List<TextBlock> {
        val lines = page.lines
            .filter { it.text.isNotBlank() && it.relWidth > 0f && it.relHeight > 0f }
            .sortedWith(compareBy({ it.relY }, { it.relX }))
        if (lines.isEmpty()) return emptyList()

        val split = splitColumns(lines)
        val fullBlocks = clusterColumn(split.fullWidthLines)
        val columnBlocks = split.columns.map { clusterColumn(it) }
        val ordered = orderBlocks(fullBlocks, columnBlocks)
        return ordered.mapIndexed { i, b -> b.copy(blockIndex = i) }
    }

    /**
     * Reading order with full-width blocks: a full-width block (title,
     * abstract) acts as a horizontal band boundary — column blocks below it
     * come after it, and within one band the left column is read before the
     * right column.
     */
    private fun orderBlocks(
        fullBlocks: List<TextBlock>,
        columnBlocks: List<List<TextBlock>>
    ): List<TextBlock> {
        if (fullBlocks.isEmpty()) return columnBlocks.flatten()
        if (columnBlocks.isEmpty()) return fullBlocks
        data class Entry(val block: TextBlock, val band: Int, val kind: Int, val col: Int)
        val entries = mutableListOf<Entry>()
        fullBlocks.forEachIndexed { i, b -> entries += Entry(b, i, 0, 0) }
        columnBlocks.forEachIndexed { col, blocks ->
            for (b in blocks) {
                val band = fullBlocks.count { f -> f.relY + f.relHeight <= b.relY + 0.001f }
                entries += Entry(b, band, 1, col)
            }
        }
        return entries
            .sortedWith(compareBy({ it.band }, { it.kind }, { it.col }, { it.block.relY }, { it.block.relX }))
            .map { it.block }
    }

    private data class ColumnSplit(
        val fullWidthLines: List<TextLine>,
        val columns: List<List<TextLine>>
    )

    /** Splits lines into full-width lines plus 1 or 2 column groups. */
    private fun splitColumns(lines: List<TextLine>): ColumnSplit {
        val single = ColumnSplit(emptyList(), listOf(lines))
        if (lines.size < 4) return single
        // The coverage histogram only counts lines narrow enough to live in a
        // column; wide lines (titles, abstracts) would fill the gutter bins
        // and hide the gutter.
        val narrow = lines.filter { it.relWidth <= 0.6f }
        if (narrow.size < 4) return single
        // Histogram of horizontal coverage in 40 bins.
        val bins = 40
        val coverage = IntArray(bins)
        for (line in narrow) {
            val from = (line.relX * bins).toInt().coerceIn(0, bins - 1)
            val to = ((line.relX + line.relWidth) * bins).toInt().coerceIn(0, bins - 1)
            for (b in from..to) coverage[b]++
        }
        // A gutter is a run of near-empty bins in the middle 60% of the page,
        // with substantial line mass on both sides.
        val threshold = max(1, (narrow.size * 0.04).toInt())
        var bestStart = -1
        var bestWidth = 0
        var runStart = -1
        for (b in 8 until bins - 8) {
            if (coverage[b] <= threshold) {
                if (runStart == -1) runStart = b
                val width = b - runStart + 1
                if (width > bestWidth) {
                    bestWidth = width
                    bestStart = runStart
                }
            } else {
                runStart = -1
            }
        }
        if (bestStart == -1 || bestWidth < 2) return single
        val gutterStart = bestStart.toFloat() / bins
        val gutterEnd = (bestStart + bestWidth).toFloat() / bins
        val gutterCenter = (gutterStart + gutterEnd) / 2f
        fun crossesGutter(line: TextLine) =
            line.relX < gutterStart && line.relX + line.relWidth > gutterEnd
        val fullWidth = lines.filter { crossesGutter(it) }
        val rest = lines.filter { !crossesGutter(it) }
        val left = rest.filter { it.relX + it.relWidth / 2f < gutterCenter }
        val right = rest.filter { it.relX + it.relWidth / 2f >= gutterCenter }
        if (left.isEmpty() || right.isEmpty()) return single
        // Both sides need a meaningful share of the text to call it two columns.
        if (min(left.size, right.size) < lines.size * 0.15f) return single
        return ColumnSplit(
            fullWidthLines = fullWidth.sortedWith(compareBy({ it.relY }, { it.relX })),
            columns = listOf(
                left.sortedWith(compareBy({ it.relY }, { it.relX })),
                right.sortedWith(compareBy({ it.relY }, { it.relX }))
            )
        )
    }

    /**
     * Merges translated blocks whose rectangles overlap heavily (intersection
     * covers at least [overlapThreshold] of the smaller block). Overlapping
     * blocks are painted twice over the same glyphs and produce the garbled
     * double-drawn areas seen around titles; merging keeps one rectangle and
     * the concatenated text in reading order instead.
     */
    fun mergeOverlappingBlocks(
        blocks: List<TranslatedBlock>,
        overlapThreshold: Float = 0.35f
    ): List<TranslatedBlock> {
        if (blocks.size < 2) return blocks
        val work = blocks.toMutableList()
        var changed = true
        while (changed) {
            changed = false
            outer@ for (i in work.indices) {
                for (j in i + 1 until work.size) {
                    if (rectOverlapRatio(work[i].block, work[j].block) >= overlapThreshold) {
                        work[i] = mergePair(work[i], work[j])
                        work.removeAt(j)
                        changed = true
                        break@outer
                    }
                }
            }
        }
        return work
    }

    /** Intersection area divided by the smaller block's area (0 = disjoint). */
    fun rectOverlapRatio(a: TextBlock, b: TextBlock): Float {
        val iw = min(a.relX + a.relWidth, b.relX + b.relWidth) - max(a.relX, b.relX)
        val ih = min(a.relY + a.relHeight, b.relY + b.relHeight) - max(a.relY, b.relY)
        if (iw <= 0f || ih <= 0f) return 0f
        val smaller = min(a.relWidth * a.relHeight, b.relWidth * b.relHeight)
        if (smaller <= 0f) return 0f
        return (iw * ih) / smaller
    }

    private fun mergePair(first: TranslatedBlock, second: TranslatedBlock): TranslatedBlock {
        val (a, b) = if (
            second.block.relY < first.block.relY ||
            (second.block.relY == first.block.relY && second.block.relX < first.block.relX)
        ) {
            second to first
        } else {
            first to second
        }
        val left = min(a.block.relX, b.block.relX)
        val top = min(a.block.relY, b.block.relY)
        val right = max(a.block.relX + a.block.relWidth, b.block.relX + b.block.relWidth)
        val bottom = max(a.block.relY + a.block.relHeight, b.block.relY + b.block.relHeight)
        val totalLines = a.block.lineCount + b.block.lineCount
        val merged = TextBlock(
            blockIndex = a.block.blockIndex,
            text = joinTranslatedText(a.block.text, b.block.text),
            relX = left,
            relY = top,
            relWidth = right - left,
            relHeight = bottom - top,
            lineCount = totalLines,
            avgLineHeight = if (totalLines > 0) {
                (a.block.avgLineHeight * a.block.lineCount +
                    b.block.avgLineHeight * b.block.lineCount) / totalLines
            } else {
                0f
            }
        )
        return TranslatedBlock(merged, joinTranslatedText(a.translatedText, b.translatedText))
    }

    /** Joins two text fragments: no space at CJK boundaries, one space otherwise. */
    private fun joinTranslatedText(a: String, b: String): String {
        if (a.isBlank()) return b
        if (b.isBlank()) return a
        val cjk = PdfTextExtractor.isCjkChar(a.last()) || PdfTextExtractor.isCjkChar(b.first())
        return if (cjk) a + b else "$a $b"
    }

    private fun clusterColumn(lines: List<TextLine>): List<TextBlock> {
        if (lines.isEmpty()) return emptyList()
        val blocks = mutableListOf<TextBlock>()
        var current = mutableListOf<TextLine>()

        fun flush() {
            if (current.isEmpty()) return
            blocks += makeBlock(current, blocks.size)
            current = mutableListOf()
        }

        for (line in lines) {
            val prev = current.lastOrNull()
            if (prev != null) {
                val gap = line.relY - (prev.relY + prev.relHeight)
                val largeGap = gap > prev.relHeight * 1.1f
                val heightRatio = line.relHeight / prev.relHeight.coerceAtLeast(0.001f)
                val heightJump = heightRatio > 1.35f || heightRatio < 0.74f
                val overlap = horizontalOverlap(prev, line)
                val disjoint = overlap < 0.12f
                if (largeGap || heightJump || disjoint) flush()
            }
            current.add(line)
        }
        flush()
        return blocks
    }

    private fun horizontalOverlap(a: TextLine, b: TextLine): Float {
        val left = max(a.relX, b.relX)
        val right = min(a.relX + a.relWidth, b.relX + b.relWidth)
        val overlap = (right - left).coerceAtLeast(0f)
        val narrower = min(a.relWidth, b.relWidth).coerceAtLeast(0.001f)
        return overlap / narrower
    }

    private fun makeBlock(lines: List<TextLine>, index: Int): TextBlock {
        val left = lines.minOf { it.relX }
        val top = lines.minOf { it.relY }
        val right = lines.maxOf { it.relX + it.relWidth }
        val bottom = lines.maxOf { it.relY + it.relHeight }
        return TextBlock(
            blockIndex = index,
            text = joinLines(lines.map { it.text.trim() }),
            relX = left,
            relY = top,
            relWidth = right - left,
            relHeight = bottom - top,
            lineCount = lines.size,
            avgLineHeight = lines.sumOf { it.relHeight.toDouble() }.toFloat() / lines.size
        )
    }

    /** Joins the lines of a block into flowing text (hyphenation repair, CJK-aware). */
    fun joinLines(rawLines: List<String>): String {
        val lines = rawLines.map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return ""
        val sb = StringBuilder()
        for (i in lines.indices) {
            val line = lines[i]
            if (i > 0) {
                val prev = lines[i - 1]
                val prevLast = prev.lastOrNull()
                val curFirst = line.firstOrNull()
                val isPrevCjk = prevLast != null && PdfTextExtractor.isCjkChar(prevLast)
                val isCurCjk = curFirst != null && PdfTextExtractor.isCjkChar(curFirst)
                when {
                    prev.endsWith("-") && !isPrevCjk && !isCurCjk -> sb.setLength(sb.length - 1)
                    !isPrevCjk && !isCurCjk -> sb.append(" ")
                }
            }
            sb.append(line)
        }
        return sb.toString()
    }

    /**
     * Splits a block's text into sentence-sized chunks so a single translation
     * request never overflows the model's context. Sentences are packed up to
     * [maxChars]; an over-long sentence is hard-split.
     */
    fun splitIntoChunks(text: String, maxChars: Int = 500): List<String> {
        if (text.length <= maxChars) return if (text.isBlank()) emptyList() else listOf(text)
        val sentences = mutableListOf<String>()
        val regex = Regex("""(?<=[.!?。！？])\s+""")
        for (part in text.split(regex)) {
            val p = part.trim()
            if (p.isEmpty()) continue
            if (p.length <= maxChars) {
                sentences.add(p)
            } else {
                var i = 0
                while (i < p.length) {
                    val end = min(i + maxChars, p.length)
                    sentences.add(p.substring(i, end))
                    i = end
                }
            }
        }
        // Pack consecutive sentences into chunks up to maxChars.
        val chunks = mutableListOf<String>()
        val sb = StringBuilder()
        for (s in sentences) {
            if (sb.isNotEmpty() && sb.length + 1 + s.length > maxChars) {
                chunks.add(sb.toString())
                sb.setLength(0)
            }
            if (sb.isNotEmpty()) sb.append(" ")
            sb.append(s)
        }
        if (sb.isNotEmpty()) chunks.add(sb.toString())
        return chunks
    }
}
