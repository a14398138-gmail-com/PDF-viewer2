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
    fun sameRowFragmentsOfOneLineStayOneBlock() {
        // One visual row emitted as two chunks (title split into two
        // horizontally disjoint fragments at the same relY): they must form
        // ONE block, otherwise each fragment is translated separately and
        // painted as its own garbled overlay.
        val lines = listOf(
            line(0, "Rapid blue-light", 0.08f, 0.05f, 0.2f, h = 0.03f),
            line(1, "induction of protein", 0.35f, 0.05f, 0.2f, h = 0.03f),
            line(2, "interactions in living cells", 0.08f, 0.09f, 0.3f, h = 0.03f)
        )
        val blocks = PdfBlockExtractor.blocksFromPageText(page(lines))
        assertEquals(1, blocks.size)
        assertTrue(blocks[0].text.contains("Rapid"))
        assertTrue(blocks[0].text.contains("induction"))
        assertTrue(blocks[0].text.contains("living cells"))
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
        // Slightly touching rects (well below the 0.75 threshold) also stay apart.
        val c = TranslatedBlock(block(2, "Next", 0.1f, 0.125f, 0.4f, 0.03f), "次")
        assertEquals(2, PdfBlockExtractor.mergeOverlappingBlocks(listOf(a, c)).size)
    }

    @Test
    fun mergeOverlappingBlocksKeepsDistinctTouchingBlocksApart() {
        // A byline rect slightly overlapping the title band above it (the
        // large title glyphs extend past the byline's top edge). The union
        // rectangle would span title + byline and smear the title's
        // translation over the author line, so the blocks must stay apart.
        fun block(idx: Int, text: String, x: Float, y: Float, w: Float, h: Float) = TextBlock(
            blockIndex = idx, text = text, relX = x, relY = y,
            relWidth = w, relHeight = h, lineCount = 1, avgLineHeight = h
        )
        val title = TranslatedBlock(block(0, "A Long Paper Title", 0.08f, 0.05f, 0.84f, 0.05f), "タイトル")
        val byline = TranslatedBlock(block(1, "Matthew J Kennedy, Robert M Hughes", 0.08f, 0.092f, 0.84f, 0.014f), "著者ら")
        val merged = PdfBlockExtractor.mergeOverlappingBlocks(listOf(title, byline))
        assertEquals(2, merged.size)
        assertEquals("タイトル", merged[0].translatedText)
        assertEquals("著者ら", merged[1].translatedText)
    }

    @Test
    fun multiWordSmallFigureFragmentsAreExcluded() {
        val lines = listOf(
            line(0, "Body text begins here with", 0.1f, 0.10f, 0.8f),
            line(1, "a second line of the body", 0.1f, 0.125f, 0.8f),
            line(2, "and a third body line.", 0.1f, 0.15f, 0.8f),
            // Vector-figure annotations: small height, 4-5 words.
            line(3, "488 nm (25 μW)", 0.7f, 0.50f, 0.15f, h = 0.011f),
            line(4, "Scale bar, 5 μm.", 0.72f, 0.53f, 0.14f, h = 0.011f),
            line(5, "Time after pulse (s)", 0.71f, 0.56f, 0.16f, h = 0.011f)
        )
        val blocks = PdfBlockExtractor.blocksFromPageText(page(lines))
        assertEquals(1, blocks.size)
        assertTrue(blocks.none { it.text.contains("488") || it.text.contains("Scale") })
    }

    @Test
    fun blockImageCoverRatioDetectsBlocksInsideFigures() {
        fun block(x: Float, y: Float, w: Float, h: Float) = TextBlock(
            blockIndex = 0, text = "text", relX = x, relY = y,
            relWidth = w, relHeight = h, lineCount = 1, avgLineHeight = h
        )
        val image = RelRect(0.1f, 0.45f, 0.5f, 0.2f)
        // A caption block sitting just under the image: barely covered.
        val caption = block(0.1f, 0.66f, 0.5f, 0.03f)
        assertTrue(PdfBlockExtractor.blockImageCoverRatio(caption, image) < 0.5f)
        // A block laid out across the figure panels: mostly covered.
        val inFigure = block(0.15f, 0.50f, 0.4f, 0.10f)
        assertTrue(PdfBlockExtractor.blockImageCoverRatio(inFigure, image) >= 0.5f)
        // Disjoint blocks are never covered.
        val body = block(0.1f, 0.10f, 0.5f, 0.03f)
        assertEquals(0f, PdfBlockExtractor.blockImageCoverRatio(body, image))
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

    // ---- v1.2.2: figure exclusion, title bands, bylines, partition ------

    private fun pageWithImages(lines: List<TextLine>, images: List<RelRect>) = PageText(
        pageIndex = 0,
        fullText = lines.joinToString("\n") { it.text },
        lines = lines,
        isNativeTextLayer = true,
        imageRects = images
    )

    @Test
    fun figureLinesInsideImagesAreExcluded() {
        val lines = listOf(
            line(0, "Body text begins here with", 0.1f, 0.10f, 0.8f),
            line(1, "a second line of the body", 0.1f, 0.125f, 0.8f),
            line(2, "and a third body line.", 0.1f, 0.15f, 0.8f),
            // A label drawn on top of a placed image (microscopy panel).
            line(3, "CIBN-pmEGFP", 0.2f, 0.50f, 0.2f)
        )
        val images = listOf(RelRect(relX = 0.1f, relY = 0.45f, relWidth = 0.5f, relHeight = 0.2f))
        val blocks = PdfBlockExtractor.blocksFromPageText(pageWithImages(lines, images))
        assertEquals(1, blocks.size)
        assertTrue(blocks.none { it.text.contains("CIBN") })
    }

    @Test
    fun shortSmallFragmentsAreExcludedAsFigureLabels() {
        val lines = listOf(
            line(0, "Body text begins here with", 0.1f, 0.10f, 0.8f),
            line(1, "a second line of the body", 0.1f, 0.125f, 0.8f),
            line(2, "and a third body line.", 0.1f, 0.15f, 0.8f),
            // Axis / scale fragment of a vector-drawn figure: small + short.
            line(3, "488 nm", 0.7f, 0.50f, 0.1f, h = 0.011f)
        )
        val blocks = PdfBlockExtractor.blocksFromPageText(page(lines))
        assertEquals(1, blocks.size)
        assertTrue(blocks.none { it.text.contains("488") })
    }

    @Test
    fun smallButLongLinesAreKept() {
        val lines = listOf(
            line(0, "Body text begins here with", 0.1f, 0.10f, 0.8f),
            line(1, "a second line of the body", 0.1f, 0.125f, 0.8f),
            line(2, "and a third body line.", 0.1f, 0.15f, 0.8f),
            // Footnote-size but a full sentence: must survive the fragment rule.
            line(
                3,
                "Correspondence should be addressed to the corresponding author at the institute.",
                0.1f, 0.90f, 0.8f, h = 0.013f
            )
        )
        val blocks = PdfBlockExtractor.blocksFromPageText(page(lines))
        assertEquals(2, blocks.size)
        assertTrue(blocks[1].text.contains("Correspondence"))
    }

    @Test
    fun titleBandWithTwoFontSizesMergesIntoOneBlock() {
        val lines = mutableListOf<TextLine>()
        // Two title lines whose sizes differ enough to trip the plain
        // height-jump rule (0.040 vs 0.029, body size 0.02).
        lines.add(line(0, "Rapid blue-light-mediated protein", 0.08f, 0.05f, 0.84f, h = 0.040f))
        lines.add(line(1, "interactions in living cells", 0.08f, 0.098f, 0.84f, h = 0.029f))
        for (i in 0 until 6) {
            lines.add(line(2 + i, "Body line number $i of the abstract", 0.08f, 0.20f + i * 0.025f, 0.84f))
        }
        val blocks = PdfBlockExtractor.blocksFromPageText(page(lines))
        assertEquals(2, blocks.size)
        assertEquals(2, blocks[0].lineCount)
        assertTrue(blocks[0].text.contains("living cells"))
        assertEquals(6, blocks[1].lineCount)
    }

    @Test
    fun bylineBetweenTitleAndAbstractStandsAlone() {
        val byline = "Matthew J Kennedy1,4, Robert M Hughes2,4, Leslie A Peteya2, " +
            "Joel W Schwartz1, Michael D Ehlers1,3 & Chandra L Tucker2"
        val lines = listOf(
            line(0, "A Grand Unified Paper Title", 0.08f, 0.05f, 0.84f, h = 0.034f),
            line(1, byline, 0.08f, 0.10f, 0.84f),
            line(2, "We report a new method for doing", 0.08f, 0.13f, 0.84f),
            line(3, "things with proteins in cells.", 0.08f, 0.155f, 0.84f),
            line(4, "The method is fast and reversible.", 0.08f, 0.18f, 0.84f)
        )
        val blocks = PdfBlockExtractor.blocksFromPageText(page(lines))
        assertEquals(3, blocks.size)
        assertEquals(byline, blocks[1].text)
        assertTrue(blocks[2].text.startsWith("We report"))
        assertTrue(!blocks[2].text.contains("Kennedy"))
    }

    @Test
    fun ordinarySentencesAreNotBylines() {
        assertTrue(
            !PdfBlockExtractor.isBylineLine(
                "However, the interaction requires a bilin cofactor, found only in plants, to function properly."
            )
        )
        assertTrue(
            PdfBlockExtractor.isBylineLine(
                "Matthew J Kennedy1,4, Robert M Hughes2,4 & Chandra L Tucker2"
            )
        )
    }

    @Test
    fun everyLineBelongsToExactlyOneGroup() {
        val lines = mutableListOf<TextLine>()
        lines.add(line(0, "A Very Long Paper Title", 0.08f, 0.05f, 0.84f, h = 0.03f))
        for (i in 0 until 4) {
            lines.add(line(1 + i * 2, "Left column line $i", 0.08f, 0.15f + i * 0.025f, 0.36f))
            lines.add(line(2 + i * 2, "Right column line $i", 0.56f, 0.15f + i * 0.025f, 0.36f))
        }
        val assigned = PdfBlockExtractor.groupLines(page(lines))
            .flatten().map { it.lineIndex }.sorted()
        assertEquals(lines.map { it.lineIndex }.sorted(), assigned)
    }

    @Test
    fun groupLinesExcludeOnlyFigureLines() {
        val body = (0 until 3).map {
            line(it, "Body line number $it here", 0.1f, 0.10f + it * 0.025f, 0.8f)
        }
        val label = line(3, "Panel A label", 0.2f, 0.50f, 0.2f)
        val images = listOf(RelRect(0.1f, 0.45f, 0.5f, 0.2f))
        val assigned = PdfBlockExtractor.groupLines(pageWithImages(body + label, images))
            .flatten().map { it.lineIndex }.sorted()
        assertEquals(listOf(0, 1, 2), assigned)
    }

    @Test
    fun multiLineBylineGroupedTogether() {
        val lines = listOf(
            line(0, "A Grand Unified Paper Title", 0.08f, 0.05f, 0.84f, h = 0.034f),
            line(1, "Matthew J Kennedy1,4, Robert M Hughes2,4, Leslie A Peteya2,", 0.08f, 0.09f, 0.84f),
            line(2, "Margaux B Margolis2, Michael E Allen2 &", 0.08f, 0.11f, 0.84f),
            line(3, "Chandra L Tucker2", 0.08f, 0.13f, 0.84f),
            line(4, "We report a new method for doing", 0.08f, 0.17f, 0.84f),
            line(5, "things with proteins in cells.", 0.08f, 0.195f, 0.84f)
        )
        val blocks = PdfBlockExtractor.blocksFromPageText(page(lines))
        assertEquals(3, blocks.size)
        assertEquals("A Grand Unified Paper Title", blocks[0].text)
        assertTrue(blocks[1].text.contains("Kennedy"))
        assertTrue(blocks[1].text.contains("Tucker"))
        assertTrue(PdfBlockExtractor.isBylineBlock(blocks[1].text))
        assertEquals(3, blocks[1].lineCount)
        assertTrue(blocks[2].text.startsWith("We report"))
        assertTrue(!PdfBlockExtractor.isBylineBlock(blocks[2].text))
    }

    @Test
    fun twoColumnTitleAndStandfirstSeparated() {
        val lines = listOf(
            // Left column: Title lines
            line(0, "Rapid blue-light-mediated", 0.08f, 0.10f, 0.36f, h = 0.03f),
            line(1, "induction of protein", 0.08f, 0.135f, 0.36f, h = 0.03f),
            line(2, "interactions in living cells", 0.08f, 0.17f, 0.36f, h = 0.03f),
            // Right column: Standfirst lines interleaved in Y
            line(3, "Reversible protein interactions on a", 0.56f, 0.10f, 0.36f, h = 0.02f),
            line(4, "subsecond timescale enable the study of", 0.56f, 0.125f, 0.36f, h = 0.02f),
            line(5, "dynamic cell biological processes.", 0.56f, 0.15f, 0.36f, h = 0.02f)
        )
        val blocks = PdfBlockExtractor.blocksFromPageText(page(lines))
        assertEquals(2, blocks.size)
        assertTrue(blocks[0].text.startsWith("Rapid blue-light-mediated"))
        assertTrue(blocks[0].relX < 0.5f)
        assertTrue(blocks[1].text.startsWith("Reversible protein interactions"))
        assertTrue(blocks[1].relX > 0.5f)
    }

    @Test
    fun figureCaptionStartsOwnBlock() {
        val lines = listOf(
            line(0, "Dimerizers allowing rapid and reversible control.", 0.08f, 0.30f, 0.36f),
            line(1, "Figure 2 | Rapid reversible dimerizer recruitment.", 0.08f, 0.33f, 0.36f),
            line(2, "(a) Schematic illustration of light-mediated dimerization.", 0.08f, 0.355f, 0.36f)
        )
        val blocks = PdfBlockExtractor.blocksFromPageText(page(lines))
        assertEquals(2, blocks.size)
        assertEquals("Dimerizers allowing rapid and reversible control.", blocks[0].text)
        assertTrue(blocks[1].text.startsWith("Figure 2"))
    }

    @Test
    fun orderGroupsPlacesBottomFullGroupAfterColumns() {
        val fullTop = line(0, "Top Full Title", 0.08f, 0.05f, 0.84f, h = 0.03f)
        val leftCol = (0 until 3).map { line(1 + it, "Left body line $it", 0.08f, 0.20f + it * 0.03f, 0.36f) }
        val rightCol = (0 until 3).map { line(4 + it, "Right body line $it", 0.56f, 0.20f + it * 0.03f, 0.36f) }
        val fullBottom = line(7, "Figure 2 | Full-width caption spanning both columns", 0.08f, 0.85f, 0.84f, h = 0.02f)

        val allLines = listOf(fullTop) + leftCol + rightCol + listOf(fullBottom)
        val blocks = PdfBlockExtractor.blocksFromPageText(page(allLines))
        assertEquals(4, blocks.size)
        assertEquals("Top Full Title", blocks[0].text)
        assertTrue(blocks[1].text.startsWith("Left body line"))
        assertTrue(blocks[2].text.startsWith("Right body line"))
        assertTrue(blocks[3].text.startsWith("Figure 2"))
    }
}
