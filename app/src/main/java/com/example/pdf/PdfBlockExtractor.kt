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
 * 0. Figure text is removed first: lines sitting inside a placed image
 *    (see [PageText.imageRects]) and short, small label fragments are
 *    never blocked, translated, or painted over.
 * 1. A vertical "gutter" (an x-band almost no line crosses) splits the page
 *    into columns; lines are assigned to a column by their centre.
 * 2. Lines that cross the whole gutter (titles, author lines, a full-width
 *    abstract) are pulled out as full-width lines first — forcing them into
 *    one column used to produce blocks whose rectangles overlapped the other
 *    column and garbled the overlay around titles.
 * 3. Inside a column, lines are visited top-to-bottom and a new block starts
 *    when the vertical gap is large, the line height changes a lot (heading /
 *    caption vs. body), or the horizontal overlap with the running block is
 *    tiny (e.g. a centred caption under a figure). Two refinements apply:
 *    consecutive display-size lines merge into one title band even across
 *    a size change, and an author byline always stands alone so it cannot
 *    be absorbed into the title or the abstract.
 *
 * This is a pure function of [PageText] so it is unit-testable and reuses the
 * existing extraction pipeline (and its disk cache) instead of re-parsing the
 * PDF with PDFBox.
 */
object PdfBlockExtractor {

    /**
     * Version of the block-splitting schema. Cached translations are keyed
     * by block index, which only has meaning within one schema, so any
     * change to how lines are grouped must bump this — [PdfTextCache]
     * invalidates older translation caches on a mismatch.
     * v3: multi-line byline grouping, figure caption splitting, robust band ordering.
     * v4: multi-word small figure fragments excluded (vector-figure axis
     * labels and scale-bar annotations were translated and painted over
     * the figure panels).
     * v5: image-overlap threshold back to 0.5 — at 0.2 figure captions
     * slightly overlapping a panel image were excluded and stayed
     * untranslated.
     */
    const val LAYOUT_SCHEMA_VERSION = 5

    /** A line counts as figure text when at least this share of it lies
     *  inside a placed image. 0.5: mostly inside the image. Lower values
     *  swallowed caption lines that merely touch a panel image. */
    private const val IMAGE_OVERLAP_THRESHOLD = 0.50f

    /** Fallback figure-label test: a line shorter (in height) than this
     *  share of the body size, narrower than [FIGURE_FRAGMENT_MAX_WIDTH]
     *  of the page, with at most [FIGURE_FRAGMENT_MAX_WORDS] words is
     *  treated as an axis tick / panel label fragment. The width bound is
     *  what keeps small real text (footnotes, standfirsts, last lines of
     *  paragraphs) out of the fragment rule. */
    private const val FIGURE_FRAGMENT_HEIGHT_RATIO = 0.8f
    private const val FIGURE_FRAGMENT_MAX_WIDTH = 0.25f
    private const val FIGURE_FRAGMENT_MAX_WORDS = 5

    /** Lines at least this much larger than the body size are display
     *  type; consecutive ones form a single title/heading band. */
    private const val TITLE_BAND_MIN_RATIO = 1.4f

    private val WHITESPACE = Regex("\\s+")
    private val NAME_WORD = Regex("[A-Z][a-z]+")
    private val INITIAL = Regex("[A-Z]\\.?")
    private val FIGURE_CAPTION_START = Regex("^(Figure|Fig\\.|Table)\\s*\\d+", RegexOption.IGNORE_CASE)

    fun blocksFromPageText(page: PageText): List<TextBlock> =
        groupLines(page).mapIndexed { i, lines -> makeBlock(lines, i) }

    /**
     * Partitions the page's translatable lines into block-sized groups in
     * reading order. The partition is exact: every line that survives the
     * figure exclusion belongs to precisely one group, so no body line can
     * be silently dropped (leaving untranslated source text inside the
     * translated overlay) or translated twice.
     */
    fun groupLines(page: PageText): List<List<TextLine>> {
        val lines = translatableLines(page)
        if (lines.isEmpty()) return emptyList()
        val bodyHeight = medianHeight(lines)
        val split = splitColumns(lines)
        val fullGroups = clusterGroup(split.fullWidthLines, bodyHeight)
        val columnGroups = split.columns.map { clusterGroup(it, bodyHeight) }
        return orderGroups(fullGroups, columnGroups)
    }

    /**
     * The page's text lines minus figure content, sorted for clustering.
     * Figure text (labels drawn on or around figures) is excluded from
     * blocking entirely: it is never translated and never painted over.
     */
    private fun translatableLines(page: PageText): List<TextLine> {
        val lines = page.lines
            .filter { it.text.isNotBlank() && it.relWidth > 0f && it.relHeight > 0f }
            .sortedWith(compareBy({ it.relY }, { it.relX }))
        if (lines.isEmpty()) return lines
        val bodyHeight = medianHeight(lines)
        return lines.filterNot { isFigureLine(it, page.imageRects, bodyHeight) }
    }

    /** Median line height of [lines] — the proxy for the body font size. */
    fun medianHeight(lines: List<TextLine>): Float {
        if (lines.isEmpty()) return 0f
        val sorted = lines.map { it.relHeight }.sorted()
        return sorted[sorted.size / 2]
    }

    /**
     * True for text that belongs to a figure rather than the article body:
     * either at least half of the line lies inside a placed image, or it
     * is a short, small fragment (axis tick, panel letter, scale-bar
     * label) of the kind figures are labelled with. Fragment exclusion is
     * deliberately conservative — a full sentence at body size never
     * matches, whatever its word count.
     */
    fun isFigureLine(line: TextLine, imageRects: List<RelRect>, bodyHeight: Float): Boolean {
        if (imageRects.any { imageOverlapRatio(line, it) >= IMAGE_OVERLAP_THRESHOLD }) {
            return true
        }
        if (bodyHeight <= 0f) return false
        if (line.relHeight >= bodyHeight * FIGURE_FRAGMENT_HEIGHT_RATIO) return false
        if (line.relWidth > FIGURE_FRAGMENT_MAX_WIDTH) return false
        val wordCount = line.text.trim().split(WHITESPACE).count { it.isNotEmpty() }
        return wordCount <= FIGURE_FRAGMENT_MAX_WORDS
    }

    /** Intersection area of [line] and [image], divided by the line's area. */
    fun imageOverlapRatio(line: TextLine, image: RelRect): Float {
        val iw = min(line.relX + line.relWidth, image.relX + image.relWidth) -
            max(line.relX, image.relX)
        val ih = min(line.relY + line.relHeight, image.relY + image.relHeight) -
            max(line.relY, image.relY)
        if (iw <= 0f || ih <= 0f) return 0f
        val area = line.relWidth * line.relHeight
        if (area <= 0f) return 0f
        return (iw * ih) / area
    }

    /**
     * Heuristic for an author byline: a comma-separated run of person
     * names (capitalized words and initials, often with superscript
     * affiliation digits). Bylines always become standalone blocks —
     * letting one join the title band above or the abstract below smears
     * the author names through the neighbouring translation.
     */
    /**
     * Heuristic for an author byline: a run of person names (capitalized words
     * and initials, often with superscript affiliation digits and separators like commas or &).
     * Bylines always become standalone blocks — letting one join the title band above
     * or the abstract below smears the author names through the neighbouring translation.
     */
    fun isBylineLine(text: String): Boolean {
        val t = text.trim()
        if (t.length < 10) return false
        val hasSeparator = t.contains(',') || t.contains('&') || t.contains('＆')
        if (!hasSeparator) return false
        val tokens = t.split(WHITESPACE).map { it.trim(',', '&', '＆', '*', '†', '‡') }.filter { it.isNotEmpty() }
        if (tokens.size < 3) return false
        val nameLike = tokens.count { token ->
            val core = token.trimEnd { ch -> ch.isDigit() || ch == ',' || ch == '*' || ch == '†' || ch == '‡' }
            core.matches(NAME_WORD) || core.matches(INITIAL)
        }
        return nameLike >= 3 && nameLike * 2 >= tokens.size
    }

    /**
     * Heuristic for trailing lines of an author byline (e.g. "Chandra L Tucker2").
     */
    fun isBylineContinuation(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || t.length > 100) return false
        val tokens = t.split(WHITESPACE).map { it.trim(',', '&', '＆', '*', '†', '‡') }.filter { it.isNotEmpty() }
        if (tokens.isEmpty() || tokens.size > 10) return false
        val nameLike = tokens.count { token ->
            val core = token.trimEnd { ch -> ch.isDigit() || ch == ',' || ch == '*' || ch == '†' || ch == '‡' }
            core.matches(NAME_WORD) || core.matches(INITIAL)
        }
        return nameLike >= 2 && nameLike >= tokens.size - 1
    }

    /**
     * True if the block consists entirely of author byline lines.
     */
    fun isBylineBlock(text: String): Boolean {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return false
        return lines.all { isBylineLine(it) || isBylineContinuation(it) } &&
            lines.any { isBylineLine(it) }
    }

    /**
     * Reading order with full-width groups: a full-width group (title,
     * abstract, figure caption) acts as a horizontal band boundary — column groups below
     * it come after it, and within one band the left column is read
     * before the right column.
     */
    private fun orderGroups(
        fullGroups: List<List<TextLine>>,
        columnGroups: List<List<List<TextLine>>>
    ): List<List<TextLine>> {
        if (fullGroups.isEmpty()) return columnGroups.flatten()
        if (columnGroups.all { it.isEmpty() }) return fullGroups
        fun topOf(g: List<TextLine>) = g.minOf { it.relY }
        fun bottomOf(g: List<TextLine>) = g.maxOf { it.relY + it.relHeight }
        fun leftOf(g: List<TextLine>) = g.minOf { it.relX }
        data class Entry(
            val group: List<TextLine>,
            val band: Int,
            val kind: Int,
            val col: Int,
            val top: Float,
            val left: Float
        )
        val sortedFull = fullGroups.sortedBy { topOf(it) }
        val entries = mutableListOf<Entry>()
        sortedFull.forEachIndexed { i, g ->
            // Full-width groups get odd bands: 1, 3, 5, ...
            entries += Entry(g, 2 * i + 1, 0, 0, topOf(g), leftOf(g))
        }
        columnGroups.forEachIndexed { col, groups ->
            for (g in groups) {
                // Column groups get even bands based on how many full-width groups are above them: 0, 2, 4, ...
                val aboveCount = sortedFull.count { f -> bottomOf(f) <= topOf(g) + 0.001f }
                entries += Entry(g, 2 * aboveCount, 1, col, topOf(g), leftOf(g))
            }
        }
        return entries
            .sortedWith(compareBy({ it.band }, { it.kind }, { it.col }, { it.top }, { it.left }))
            .map { it.group }
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
     * Merges translated blocks whose rectangles are near-duplicates
     * (intersection covers at least [overlapThreshold] of the smaller block).
     * Truly overlapping blocks are painted twice over the same glyphs and
     * produce the garbled double-drawn areas seen around titles; merging
     * keeps one rectangle and the concatenated text in reading order instead.
     * Distinct blocks that merely touch — a byline under a title band, a
     * caption under a figure — must NOT merge: their union rectangle would
     * erase and redraw a region far larger than either block, smearing the
     * translation of one block across the other's source glyphs. That is
     * why the default threshold is high (0.75): only blocks covering almost
     * the same area merge.
     */
    fun mergeOverlappingBlocks(
        blocks: List<TranslatedBlock>,
        overlapThreshold: Float = 0.75f
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

    /** Intersection area of [block] and [image], divided by the block's area. */
    fun blockImageCoverRatio(block: TextBlock, image: RelRect): Float {
        val iw = min(block.relX + block.relWidth, image.relX + image.relWidth) -
            max(block.relX, image.relX)
        val ih = min(block.relY + block.relHeight, image.relY + image.relHeight) -
            max(block.relY, image.relY)
        if (iw <= 0f || ih <= 0f) return 0f
        val area = block.relWidth * block.relHeight
        if (area <= 0f) return 0f
        return (iw * ih) / area
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

    /**
     * Groups one sorted run of lines (one column, or the full-width band)
     * into consecutive clusters. Because the clusters are consecutive runs
     * of the input, they form an exact partition of it by construction.
     */
    private fun clusterGroup(lines: List<TextLine>, bodyHeight: Float): List<List<TextLine>> {
        if (lines.isEmpty()) return emptyList()
        val groups = mutableListOf<List<TextLine>>()
        var current = mutableListOf<TextLine>()
        var inByline = false

        fun flush() {
            if (current.isNotEmpty()) {
                groups += current.toList()
                current = mutableListOf()
            }
            inByline = false
        }

        for (line in lines) {
            val text = line.text
            if (isBylineLine(text)) {
                if (!inByline) {
                    flush()
                    inByline = true
                }
                current.add(line)
                continue
            } else if (inByline && isBylineContinuation(text)) {
                val prev = current.lastOrNull()
                val gap = if (prev != null) line.relY - (prev.relY + prev.relHeight) else 0f
                if (prev != null && gap < prev.relHeight * 1.5f) {
                    current.add(line)
                    continue
                }
            }

            if (inByline) {
                flush()
            }

            val prev = current.lastOrNull()
            if (prev != null && shouldSplit(current, prev, line, bodyHeight)) flush()
            current.add(line)
        }
        flush()
        return groups
    }

    private fun shouldSplit(
        current: List<TextLine>,
        prev: TextLine,
        line: TextLine,
        bodyHeight: Float
    ): Boolean {
        if (FIGURE_CAPTION_START.containsMatchIn(line.text.trim())) return true
        val gap = line.relY - (prev.relY + prev.relHeight)
        // A line whose size differs sharply from the running cluster (or
        // from its immediate neighbour) starts a new block — this is what
        // keeps headings, captions, and bylines out of body paragraphs.
        val ratioCluster = line.relHeight / medianHeight(current).coerceAtLeast(0.001f)
        val ratioPrev = line.relHeight / prev.relHeight.coerceAtLeast(0.001f)
        val heightJump = ratioCluster > 1.35f || ratioCluster < 0.74f ||
            ratioPrev > 1.35f || ratioPrev < 0.74f
        if (heightJump) {
            // Title-band exception: consecutive display-size lines are one
            // heading even when their sizes differ enough to trip the jump
            // rule (a two-size title), provided they sit close together.
            val bothLarge = bodyHeight > 0f &&
                prev.relHeight >= bodyHeight * TITLE_BAND_MIN_RATIO &&
                line.relHeight >= bodyHeight * TITLE_BAND_MIN_RATIO
            val bandGapLimit = 0.6f * (prev.relHeight + line.relHeight) / 2f
            if (!(bothLarge && gap < bandGapLimit)) return true
        }
        if (gap > prev.relHeight * 1.1f) return true
        if (horizontalOverlap(prev, line) < 0.12f) return true
        return false
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
