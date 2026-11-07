package com.example.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the translation overlay's measure → shrink → clip layout math. */
class TranslatedTextLayoutTest {

    /** Deterministic measurer: Latin 0.5em, space 0.3em, CJK 1.0em, font height 1.15em. */
    private val measurer = object : TranslatedTextLayout.Measurer {
        override fun textWidth(text: String, fontSizePx: Float): Float {
            var w = 0.0
            for (ch in text) {
                w += when {
                    ch == ' ' -> 0.3
                    ch.code > 0x2E7F -> 1.0
                    else -> 0.5
                }
            }
            return (w * fontSizePx).toFloat()
        }

        override fun fontHeight(fontSizePx: Float): Float = fontSizePx * 1.15f
    }

    @Test
    fun wrapKeepsEveryLineWithinMaxWidth() {
        val text = "これはテスト文章です。段組みを保ったまま翻訳します。"
        val lines = TranslatedTextLayout.wrap(text, fontSizePx = 10f, maxWidthPx = 55f, measurer)
        assertTrue(lines.size >= 3)
        for (line in lines) {
            assertTrue(
                "line too wide: $line",
                measurer.textWidth(line, 10f) <= 55f + 0.01f
            )
        }
        assertEquals(text, lines.joinToString(""))
    }

    @Test
    fun wrapPrefersSpaceBreaksForLatinText() {
        val text = "alpha beta gamma delta"
        val lines = TranslatedTextLayout.wrap(text, fontSizePx = 10f, maxWidthPx = 55f, measurer)
        // "alpha beta" is 10 glyphs ≈ 48px and fits; breaks happen at spaces.
        assertEquals(listOf("alpha beta", "gamma delta"), lines)
    }

    @Test
    fun wrapHonoursExplicitNewlines() {
        val lines = TranslatedTextLayout.wrap("一行目\n二行目", 10f, 500f, measurer)
        assertEquals(listOf("一行目", "二行目"), lines)
    }

    @Test
    fun lineHeightIsAtLeastOnePointTwoFiveTimesFontSize() {
        // Fake font height is 1.15em, so the 1.25 floor must win.
        assertEquals(12.5f, TranslatedTextLayout.lineHeightPx(10f, measurer), 0.001f)
        assertEquals(25f, TranslatedTextLayout.lineHeightPx(20f, measurer), 0.001f)
    }

    @Test
    fun layoutShrinksFontUntilLinesFitHeight() {
        val text = "これは翻訳された文章です。元のレイアウトを保ちながら表示されます。"
        val result = TranslatedTextLayout.layout(
            text = text,
            maxWidthPx = 100f,
            maxHeightPx = 40f,
            startFontSizePx = 20f,
            minFontSizePx = 5f,
            measurer = measurer
        )
        assertTrue(!result.truncated)
        assertTrue(result.fontSizePx < 20f)
        assertTrue(result.lines.size * result.lineHeightPx <= 40f + 0.5f)
        for (line in result.lines) {
            assertTrue(measurer.textWidth(line, result.fontSizePx) <= 100f + 0.01f)
        }
        assertEquals(text, result.lines.joinToString(""))
    }

    @Test
    fun layoutKeepsStartSizeWhenTextAlreadyFits() {
        val result = TranslatedTextLayout.layout(
            text = "短い文。",
            maxWidthPx = 200f,
            maxHeightPx = 100f,
            startFontSizePx = 14f,
            minFontSizePx = 5f,
            measurer = measurer
        )
        assertEquals(14f, result.fontSizePx, 0.001f)
        assertEquals(listOf("短い文。"), result.lines)
        assertTrue(!result.truncated)
    }

    @Test
    fun layoutTruncatesAtMinimumSizeInsteadOfOverflowing() {
        val text = "とても長い翻訳文がここに入ります。".repeat(10)
        val result = TranslatedTextLayout.layout(
            text = text,
            maxWidthPx = 60f,
            maxHeightPx = 30f,
            startFontSizePx = 12f,
            minFontSizePx = 10f,
            measurer = measurer
        )
        assertTrue(result.truncated)
        assertEquals(10f, result.fontSizePx, 0.001f)
        // Lines kept must fit the height budget at the guaranteed line height.
        assertTrue(result.lines.size * result.lineHeightPx <= 30f + 0.5f)
        assertTrue(result.lines.last().endsWith("…"))
    }

    @Test
    fun layoutHandlesBlankText() {
        val result = TranslatedTextLayout.layout("", 100f, 100f, 12f, 5f, measurer)
        assertTrue(result.lines.isEmpty())
        assertTrue(!result.truncated)
    }
}
