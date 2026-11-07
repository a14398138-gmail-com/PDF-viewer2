package com.example.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure functions of [PdfTextExtractor]:
 * search / formatSelectedWords / cleanPdfExtractedText / search highlight mapping.
 * These run on the JVM (no Android runtime needed).
 */
class PdfTextExtractorTest {

    private fun word(index: Int, text: String, lineIndex: Int = 0) = TextWord(
        index = index,
        text = text,
        relX = index * 0.05f,
        relY = lineIndex * 0.05f,
        relWidth = 0.04f,
        relHeight = 0.03f,
        lineIndex = lineIndex
    )

    private fun page(pageIndex: Int, fullText: String, words: List<TextWord> = emptyList()) = PageText(
        pageIndex = pageIndex,
        fullText = fullText,
        words = words
    )

    // ---- segmentWordsByHorizontalGap() ------------------------------------

    private fun ew(text: String, x: Float, w: Float, h: Float = 0.02f) = ExtractedWord(
        text = text, relX = x, relY = 0.1f, relWidth = w, relHeight = h
    )

    @Test
    fun segmentKeepsTitleWordSpacingTogether() {
        // Large display type (h = 0.03): word gaps of ~0.015 must NOT split the
        // title into fragments (the old 1.2x-height threshold did).
        val words = listOf(
            ew("Rapid", 0.08f, 0.05f, h = 0.03f),
            ew("blue-light-mediated", 0.145f, 0.16f, h = 0.03f),
            ew("induction", 0.32f, 0.07f, h = 0.03f),
            ew("of", 0.405f, 0.015f, h = 0.03f),
            ew("protein", 0.435f, 0.055f, h = 0.03f)
        )
        val segments = PdfTextExtractor.segmentWordsByHorizontalGap(words)
        assertEquals(1, segments.size)
    }

    @Test
    fun segmentSplitsColumnGutterInOneLine() {
        // A title line and a standfirst line extracted in one writeString:
        // the gutter gap (~0.1) splits them apart.
        val words = listOf(
            ew("Rapid", 0.08f, 0.05f),
            ew("induction", 0.15f, 0.07f),
            ew("Reversible", 0.56f, 0.06f),
            ew("interactions", 0.64f, 0.08f)
        )
        val segments = PdfTextExtractor.segmentWordsByHorizontalGap(words)
        assertEquals(2, segments.size)
        assertEquals(listOf("Rapid", "induction"), segments[0].map { it.text })
        assertEquals(listOf("Reversible", "interactions"), segments[1].map { it.text })
    }

    @Test
    fun segmentKeepsJustifiedBodyWordSpacingTogether() {
        // Body size (h = 0.02): sentence-width gaps stay in one segment.
        val words = listOf(
            ew("Dimerizers", 0.1f, 0.06f),
            ew("allowing", 0.17f, 0.05f),
            ew("inducible", 0.23f, 0.05f),
            ew("control", 0.29f, 0.04f),
            ew("of", 0.34f, 0.012f),
            ew("interactions.", 0.36f, 0.08f)
        )
        assertEquals(1, PdfTextExtractor.segmentWordsByHorizontalGap(words).size)
    }

    // ---- search() -----------------------------------------------------------

    @Test
    fun searchFindsCaseInsensitiveMatchesAcrossPages() {
        val pages = listOf(
            page(0, "Hello World, hello PDF"),
            page(1, "nothing here"),
            page(2, "say HELLO again")
        )
        val matches = PdfTextExtractor.search(pages, "hello")
        assertEquals(3, matches.size)
        assertEquals(listOf(0, 0, 2), matches.map { it.pageIndex })
        assertEquals(0, matches[0].charOffset)
        assertEquals(13, matches[1].charOffset)
    }

    @Test
    fun searchReturnsEmptyForBlankQuery() {
        val pages = listOf(page(0, "some text"))
        assertTrue(PdfTextExtractor.search(pages, "").isEmpty())
        assertTrue(PdfTextExtractor.search(pages, "   ").isEmpty())
        assertTrue(PdfTextExtractor.search(pages, "absent").isEmpty())
    }

    @Test
    fun searchSnippetIncludesContextAndEllipsis() {
        val text = "a".repeat(50) + "TARGET" + "b".repeat(50)
        val matches = PdfTextExtractor.search(listOf(page(0, text)), "target")
        assertEquals(1, matches.size)
        assertTrue(matches[0].snippet.startsWith("..."))
        assertTrue(matches[0].snippet.endsWith("..."))
        assertTrue(matches[0].snippet.contains("TARGET"))
    }

    // ---- formatSelectedWords() ----------------------------------------------

    @Test
    fun formatSelectedWordsJoinsLatinWordsWithSpace() {
        val words = listOf(word(0, "Hello"), word(1, "world"))
        assertEquals("Hello world", PdfTextExtractor.formatSelectedWords(words, setOf(0, 1)))
    }

    @Test
    fun formatSelectedWordsJoinsCjkWithoutSpace() {
        val words = listOf(word(0, "日"), word(1, "本"), word(2, "語"))
        assertEquals("日本語", PdfTextExtractor.formatSelectedWords(words, setOf(0, 1, 2)))
    }

    @Test
    fun formatSelectedWordsRepairsHyphenation() {
        val words = listOf(word(0, "inter-"), word(1, "national"))
        assertEquals("international", PdfTextExtractor.formatSelectedWords(words, setOf(0, 1)))
    }

    @Test
    fun formatSelectedWordsRespectsIndexOrderAndEmpties() {
        val words = listOf(word(0, "one"), word(1, "two"), word(2, "three"))
        assertEquals("one three", PdfTextExtractor.formatSelectedWords(words, setOf(2, 0)))
        assertEquals("", PdfTextExtractor.formatSelectedWords(words, emptySet()))
        assertEquals("", PdfTextExtractor.formatSelectedWords(words, setOf(99)))
    }

    // ---- joinWords() --------------------------------------------------------

    @Test
    fun joinWordsJoinsLatinWithSpaceAndRepairsHyphenation() {
        val words = listOf(
            word(0, "Rapid"),
            word(1, "blue-light-"),
            word(2, "mediated"),
            word(3, "induction")
        )
        assertEquals("Rapid blue-light-mediated induction", PdfTextExtractor.joinWords(words))
    }

    @Test
    fun joinWordsJoinsCjkWithoutSpaces() {
        val words = listOf(word(0, "日"), word(1, "本"), word(2, "語"))
        assertEquals("日本語", PdfTextExtractor.joinWords(words))
    }

    // ---- cleanPdfExtractedText() --------------------------------------------

    @Test
    fun cleanJoinsLatinLinesWithSpace() {
        assertEquals("Hello world", PdfTextExtractor.cleanPdfExtractedText("Hello\nworld"))
    }

    @Test
    fun cleanJoinsCjkLinesWithoutSpace() {
        assertEquals("日本語の文章", PdfTextExtractor.cleanPdfExtractedText("日本語の\n文章"))
    }

    @Test
    fun cleanRepairsHyphenationAndKeepsParagraphs() {
        val raw = "inter-\nnational text\n\nSecond paragraph"
        assertEquals(
            "international text\n\nSecond paragraph",
            PdfTextExtractor.cleanPdfExtractedText(raw)
        )
    }

    @Test
    fun cleanReturnsEmptyForBlankInput() {
        assertEquals("", PdfTextExtractor.cleanPdfExtractedText(""))
        assertEquals("", PdfTextExtractor.cleanPdfExtractedText("   \n  "))
    }

    // ---- search highlight mapping -------------------------------------------

    @Test
    fun highlightIndicesMapLatinQueryToWord() {
        val words = listOf(word(0, "Hello"), word(1, "world"), word(2, "again"))
        val p = page(0, "Hello world again", words)
        val indices = PdfTextExtractor.searchHighlightIndices(p, "world", 0)
        assertEquals(setOf(1), indices)
    }

    @Test
    fun highlightIndicesMapCjkQueryAcrossCharWords() {
        val words = listOf(word(0, "日"), word(1, "本"), word(2, "語"), word(3, "で"), word(4, "す"))
        val p = page(0, "日本語です", words)
        val indices = PdfTextExtractor.searchHighlightIndices(p, "日本語", 0)
        assertEquals(setOf(0, 1, 2), indices)
    }

    @Test
    fun highlightIndicesRespectOccurrenceOrdinal() {
        val words = listOf(word(0, "cat"), word(1, "dog"), word(2, "cat"))
        val p = page(0, "cat dog cat", words)
        assertEquals(setOf(0), PdfTextExtractor.searchHighlightIndices(p, "cat", 0))
        assertEquals(setOf(2), PdfTextExtractor.searchHighlightIndices(p, "cat", 1))
        // occurrenceOrdinal derived from a SearchMatch must agree with search()
        val matches = PdfTextExtractor.search(listOf(p), "cat")
        assertEquals(2, matches.size)
        assertEquals(0, PdfTextExtractor.occurrenceOrdinal(p, matches[0], "cat"))
        assertEquals(1, PdfTextExtractor.occurrenceOrdinal(p, matches[1], "cat"))
    }
}
