package com.example.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the layout block clustering used by paper translation. */
class PdfBlockExtractorTest {

    private fun line(idx: Int, text: String, x: Float, y: Float, w: Float, h: Float = 0.02f) = TextLine(
        lineIndex = idx,
        text = text,
        relX = x,
        relY = y,
        relWidth = w,
        relHeight = h
    )

    private fun page(lines: List<TextLine>) = PageText(
        pageIndex = 0,
        fullText = lines.joinToString("\n") { it.text },
        lines = lines,
        isNativeTextLayer = true
    )

    @Test
    fun singleColumnParagraphBecomesOneBlock() {
        val lines = listOf(
            line(0, "This is the first line", 0.1f, 0.10f, 0.8f),
            line(1, "of a single paragraph", 0.1f, 0.125f, 0.8f),
            line(2, "with three lines total.", 0.1f, 0.15f, 0.6f)
        )
        val blocks = PdfBlockExtractor.blocksFromPageText(page(lines))
        assertEquals(1, blocks.size)
        assertEquals("This is the first line of a single paragraph with three lines total.", blocks[0].text)
        assertEquals(3, blocks[0].lineCount)
    }

    @Test
    fun largeVerticalGapSplitsBlocks() {
        val lines = listOf(
            line(0, "Paragraph one line.", 0.1f, 0.10f, 0.8f),
            line(1, "Paragraph two starts", 0.1f, 0.40f, 0.8f),
            line(2, "and continues here.", 0.1f, 0.425f, 0.8f)
        )
        val blocks = PdfBlockExtractor.blocksFromPageText(page(lines))
        assertEquals(2, blocks.size)
        assertEquals("Paragraph one line.", blocks[0].text)
        assertEquals("Paragraph two starts and continues here.", blocks[1].text)
    }

    @Test
    fun twoColumnsAreNotMerged() {
        val lines = mutableListOf<TextLine>()
        // Left column x 0.08..0.44, right column x 0.56..0.92, interleaved in y.
        for (i in 0 until 4) {
            lines.add(line(i * 2, "Left column line $i", 0.08f, 0.10f + i * 0.025f, 0.36f))
            lines.add(line(i * 2 + 1, "Right column line $i", 0.56f, 0.10f + i * 0.025f, 0.36f))
        }
        val blocks = PdfBlockExtractor.blocksFromPageText(page(lines))
        assertEquals(2, blocks.size)
        assertTrue(blocks[0].text.startsWith("Left column line 0"))
        assertTrue(blocks[0].text.contains("Left column line 3"))
        assertTrue(blocks[1].text.startsWith("Right column line 0"))
        assertTrue(blocks[1].relX > 0.5f)
    }

    @Test
    fun headingWithDifferentHeightBecomesOwnBlock() {
        val lines = listOf(
            line(0, "1. Introduction", 0.1f, 0.10f, 0.5f, h = 0.035f),
            line(1, "Body text starts here", 0.1f, 0.15f, 0.8f, h = 0.02f),
            line(2, "and continues normally.", 0.1f, 0.175f, 0.8f, h = 0.02f)
        )
        val blocks = PdfBlockExtractor.blocksFromPageText(page(lines))
        assertEquals(2, blocks.size)
        assertEquals("1. Introduction", blocks[0].text)
    }

    @Test
    fun avgLineHeightIsRecorded() {
        val lines = listOf(
            line(0, "Line one", 0.1f, 0.10f, 0.8f, h = 0.02f),
            line(1, "Line two", 0.1f, 0.125f, 0.8f, h = 0.024f)
        )
        val blocks = PdfBlockExtractor.blocksFromPageText(page(lines))
        assertEquals(1, blocks.size)
        assertEquals(0.022f, blocks[0].avgLineHeight, 0.0001f)
    }

    @Test
    fun fullWidthTitleStaysOutOfColumns() {
        val lines = mutableListOf<TextLine>()
        // Full-width title above a two-column body (journal first page).
        lines.add(line(0, "A Very Long Paper Title", 0.08f, 0.05f, 0.84f, h = 0.03f))
        for (i in 0 until 4) {
            lines.add(line(1 + i * 2, "Left column line $i", 0.08f, 0.15f + i * 0.025f, 0.36f))
            lines.add(line(2 + i * 2, "Right column line $i", 0.56f, 0.15f + i * 0.025f, 0.36f))
        }
        val blocks = PdfBlockExtractor.blocksFromPageText(page(lines))
        assertEquals(3, blocks.size)
        // Title is its own full-width block and comes first in reading order.
        assertEquals("A Very Long Paper Title", blocks[0].text)
        assertTrue(blocks[0].relWidth > 0.8f)
        assertTrue(blocks[1].text.startsWith("Left column line 0"))
        assertTrue(blocks[2].text.startsWith("Right column line 0"))
    }

    @Test
    fun mergeOverlappingBlocksCombinesHeavilyOverlappingRects() {
        fun block(idx: Int, text: String, x: Float, y: Float, w: Float, h: Float) = TextBlock(
            blockIndex = idx, text = text, relX = x, relY = y,
            relWidth = w, relHeight = h, lineCount = 1, avgLineHeight = h
        )
        val a = TranslatedBlock(block(0, "Title part one", 0.1f, 0.1f, 0.8f, 0.05f), "タイトル前半")
        val b = TranslatedBlock(block(1, "Title part two", 0.1f, 0.11f, 0.8f, 0.05f), "タイトル後半")
        val merged = PdfBlockExtractor.mergeOverlappingBlocks(listOf(a, b))
        assertEquals(1, merged.size)
        assertEquals("タイトル前半タイトル後半", merged[0].translatedText)
    }

    @Test
    fun mergeOverlappingBlocksKeepsDisjointBlocks() {
        fun block(idx: Int, text: String, x: Float, y: Float, w: Float, h: Float) = TextBlock(
            blockIndex = idx, text = text, relX = x, relY = y,
            relWidth = w, relHeight = h, lineCount = 1, avgLineHeight = h
        )
        val a = TranslatedBlock(block(0, "Top", 0.1f, 0.1f, 0.4f, 0.03f), "上")
        val b = TranslatedBlock(block(1, "Bottom", 0.1f, 0.6f, 0.4f, 0.03f), "下")
        assertEquals(2, PdfBlockExtractor.mergeOverlappingBlocks(listOf(a, b)).size)
        // Slightly touching rects (well below the 0.35 threshold) also stay apart.
        val c = TranslatedBlock(block(2, "Next", 0.1f, 0.125f, 0.4f, 0.03f), "次")
        assertEquals(2, PdfBlockExtractor.mergeOverlappingBlocks(listOf(a, c)).size)
    }

    @Test
    fun joinLinesRepairsHyphenation() {
        assertEquals(
            "international conference",
            PdfBlockExtractor.joinLines(listOf("inter-", "national conference"))
        )
        assertEquals(
            "日本語の文章です",
            PdfBlockExtractor.joinLines(listOf("日本語の", "文章です"))
        )
    }

    @Test
    fun splitIntoChunksRespectsLimitAndSentences() {
        val text = "First sentence here. Second sentence follows! " +
            "Third one is short. Fourth sentence wraps things up?"
        val chunks = PdfBlockExtractor.splitIntoChunks(text, maxChars = 50)
        assertTrue(chunks.size >= 2)
        assertTrue(chunks.all { it.length <= 50 })
        assertEquals(text, chunks.joinToString(" "))
        // Short text stays a single chunk.
        assertEquals(listOf("Short."), PdfBlockExtractor.splitIntoChunks("Short.", maxChars = 500))
        assertTrue(PdfBlockExtractor.splitIntoChunks("", maxChars = 500).isEmpty())
    }

    @Test
    fun splitIntoChunksHardSplitsOverlongSentence() {
        val text = "a".repeat(1200)
        val chunks = PdfBlockExtractor.splitIntoChunks(text, maxChars = 500)
        assertEquals(3, chunks.size)
        assertTrue(chunks.all { it.length <= 500 })
        assertEquals(text, chunks.joinToString(""))
    }
}
