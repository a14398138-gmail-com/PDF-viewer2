package com.example.pdf

import kotlin.math.max

/**
 * Pure text-layout math for the translation overlay: given a target
 * rectangle, wrap the translation and pick the largest font size whose
 * wrapped lines fit the rectangle height. If even the minimum size does not
 * fit, the line list is truncated to what fits — the renderer additionally
 * clips to the rectangle, so a translation can never spill into a
 * neighbouring block, figure, or footnote area.
 *
 * All Android dependencies are hidden behind [Measurer], so the sizing
 * logic itself is unit-testable on the JVM.
 */
object TranslatedTextLayout {

    /** Line height is always at least this multiple of the font size, so
     *  baselines can never crowd into each other. */
    const val MIN_LINE_HEIGHT_FACTOR = 1.25f

    /** Shrink step used while searching for a fitting font size. */
    private const val SHRINK_FACTOR = 0.92f

    interface Measurer {
        /** Width of [text] rendered at [fontSizePx], in pixels. */
        fun textWidth(text: String, fontSizePx: Float): Float

        /** Full font height (ascent + descent, positive) at [fontSizePx]. */
        fun fontHeight(fontSizePx: Float): Float
    }

    data class Result(
        val fontSizePx: Float,
        val lineHeightPx: Float,
        val lines: List<String>,
        /** True when text was cut because even the minimum size overflowed. */
        val truncated: Boolean
    )

    fun lineHeightPx(fontSizePx: Float, measurer: Measurer): Float =
        max(measurer.fontHeight(fontSizePx), fontSizePx * MIN_LINE_HEIGHT_FACTOR)

    /**
     * Greedy wrapping at [fontSizePx] so every line is at most [maxWidthPx]
     * wide. Breaks prefer the last space that still fits (Latin words stay
     * whole); CJK runs can break between any characters. Explicit newlines
     * are honoured. A single character wider than the line is kept on its
     * own line (the renderer's clip handles that degenerate case).
     */
    fun wrap(text: String, fontSizePx: Float, maxWidthPx: Float, measurer: Measurer): List<String> {
        if (text.isBlank() || maxWidthPx <= 0f) return emptyList()
        val out = mutableListOf<String>()
        for (paragraph in text.split('\n')) {
            if (paragraph.isBlank()) continue
            var lineStart = 0
            var lastSpace = -1 // index of last space inside the current line
            var i = 0
            while (i < paragraph.length) {
                val ch = paragraph[i]
                if (ch == ' ') lastSpace = i
                val candidate = paragraph.substring(lineStart, i + 1)
                if (measurer.textWidth(candidate, fontSizePx) > maxWidthPx && i > lineStart) {
                    if (lastSpace > lineStart) {
                        out += paragraph.substring(lineStart, lastSpace).trimEnd()
                        i = lastSpace + 1
                        lineStart = i
                        lastSpace = -1
                    } else {
                        out += paragraph.substring(lineStart, i)
                        lineStart = i
                        lastSpace = -1
                    }
                } else {
                    i++
                }
            }
            if (lineStart < paragraph.length) {
                out += paragraph.substring(lineStart).trimEnd()
            } else if (lineStart == 0) {
                out += paragraph
            }
        }
        return out.filter { it.isNotEmpty() }
    }

    /**
     * Lays [text] out inside a [maxWidthPx] × [maxHeightPx] box, starting at
     * [startFontSizePx] and shrinking by [SHRINK_FACTOR] until the wrapped
     * lines fit the height, never going below [minFontSizePx]. At the
     * minimum size the result is truncated to the lines that fit and the
     * last kept line is ellipsized.
     */
    fun layout(
        text: String,
        maxWidthPx: Float,
        maxHeightPx: Float,
        startFontSizePx: Float,
        minFontSizePx: Float,
        measurer: Measurer
    ): Result {
        if (text.isBlank() || maxWidthPx <= 0f || maxHeightPx <= 0f) {
            return Result(minFontSizePx, lineHeightPx(minFontSizePx, measurer), emptyList(), truncated = false)
        }
        val min = minFontSizePx.coerceAtLeast(1f)
        var size = startFontSizePx.coerceAtLeast(min)
        while (true) {
            val lineHeight = lineHeightPx(size, measurer)
            val lines = wrap(text, size, maxWidthPx, measurer)
            val fits = lines.size * lineHeight <= maxHeightPx + 0.5f
            if (fits) {
                return Result(size, lineHeight, lines, truncated = false)
            }
            if (size <= min + 0.01f) {
                // Even the minimum size overflows: keep only the lines that
                // fit and mark the cut with an ellipsis.
                val maxLines = max(1, (maxHeightPx / lineHeight).toInt())
                val kept = lines.take(maxLines).toMutableList()
                if (kept.size < lines.size && kept.isNotEmpty()) {
                    kept[kept.lastIndex] = ellipsize(kept.last(), size, maxWidthPx, measurer)
                }
                return Result(size, lineHeight, kept, truncated = kept.size < lines.size)
            }
            size = max(min, size * SHRINK_FACTOR)
        }
    }

    private fun ellipsize(line: String, fontSizePx: Float, maxWidthPx: Float, measurer: Measurer): String {
        var candidate = line
        while (candidate.isNotEmpty() &&
            measurer.textWidth(candidate + "…", fontSizePx) > maxWidthPx
        ) {
            candidate = candidate.dropLast(1)
        }
        return candidate + "…"
    }
}
