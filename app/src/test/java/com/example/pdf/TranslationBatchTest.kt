package com.example.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationBatchTest {

    @Test
    fun planBatchesGroupsSmallConsecutiveTexts() {
        val texts = listOf("aaa", "bbb", "ccc")
        val dirs = listOf("en", "en", "en")
        assertEquals(listOf(listOf(0, 1, 2)), TranslationBatch.planBatches(texts, dirs, maxChars = 100))
    }

    @Test
    fun planBatchesSplitsOnDirectionChange() {
        val texts = listOf("aaa", "あああ", "bbb")
        val dirs = listOf("en", "ja", "en")
        assertEquals(
            listOf(listOf(0), listOf(1), listOf(2)),
            TranslationBatch.planBatches(texts, dirs, maxChars = 100)
        )
    }

    @Test
    fun planBatchesSplitsWhenJoinedLengthExceedsCap() {
        // Joined length includes the 5-char separator between pieces.
        val texts = listOf("a".repeat(10), "b".repeat(10), "c".repeat(10))
        val dirs = listOf("en", "en", "en")
        // 10 fits; 10+5+10=25 fits a cap of 25; adding the third (35) does not.
        assertEquals(
            listOf(listOf(0, 1), listOf(2)),
            TranslationBatch.planBatches(texts, dirs, maxChars = 25)
        )
    }

    @Test
    fun planBatchesOversizeTextIsSingleton() {
        val texts = listOf("x".repeat(600), "short")
        val dirs = listOf("en", "en")
        assertEquals(
            listOf(listOf(0), listOf(1)),
            TranslationBatch.planBatches(texts, dirs, maxChars = 450)
        )
    }

    @Test
    fun planBatchesEmptyInput() {
        assertTrue(TranslationBatch.planBatches(emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun joinSplitRoundTrip() {
        val texts = listOf("Hello world.", "Second block here.")
        val joined = TranslationBatch.join(texts)
        val translated = joined.replace("Hello world.", "こんにちは世界。")
            .replace("Second block here.", "2番目のブロック。")
        assertEquals(
            listOf("こんにちは世界。", "2番目のブロック。"),
            TranslationBatch.split(translated, 2)
        )
    }

    @Test
    fun splitReturnsNullOnCountMismatch() {
        // Model dropped the separator entirely.
        assertNull(TranslationBatch.split("訳文だけが返ってきた", 2))
        // Model produced too many separators.
        assertNull(TranslationBatch.split("a @@@ b @@@ c", 2))
    }

    @Test
    fun splitReturnsNullOnEmptyPiece() {
        assertNull(TranslationBatch.split("訳文1 @@@ ", 2))
    }

    @Test
    fun splitSingleBlock() {
        assertEquals(listOf("訳文"), TranslationBatch.split(" 訳文 ", 1))
        assertNull(TranslationBatch.split("   ", 1))
    }
}
