package com.example.pdf

/**
 * Batches consecutive layout blocks into a single translation request.
 *
 * Translating block-by-block costs one model invocation (prompt prefill
 * included) per block, which dominates the total time on pages with many
 * small blocks. Joining neighbouring blocks with a visible separator lets
 * one request cover several blocks; the reply is split back on the same
 * separator. If the model does not preserve the separators (the split
 * count differs, or a piece comes back empty), [split] returns null and
 * the caller falls back to per-block translation for that batch.
 */
object TranslationBatch {

    /** Marker placed between blocks, on its own line. */
    const val SEPARATOR = "@@@"

    /** Joined batches stay below the per-request chunk size used elsewhere. */
    const val MAX_BATCH_CHARS = 450

    private val JOINER = "\n$SEPARATOR\n"

    fun join(texts: List<String>): String = texts.joinToString(JOINER)

    /** Total length of [texts] once joined (texts plus separators). */
    fun joinedLength(texts: List<String>): Int =
        texts.sumOf { it.length } + JOINER.length * (texts.size - 1).coerceAtLeast(0)

    /**
     * Groups consecutive indices of [texts] into batches. A new batch starts
     * when the translation direction changes or when adding the next text
     * would push the joined length past [maxChars]. A single text longer
     * than [maxChars] forms a singleton batch (the caller sends it through
     * the sentence-chunking path instead).
     */
    fun planBatches(
        texts: List<String>,
        directions: List<String>,
        maxChars: Int = MAX_BATCH_CHARS
    ): List<List<Int>> {
        require(texts.size == directions.size) { "texts/directions size mismatch" }
        val batches = mutableListOf<List<Int>>()
        var current = mutableListOf<Int>()
        var currentLen = 0
        fun flush() {
            if (current.isNotEmpty()) {
                batches.add(current)
                current = mutableListOf()
                currentLen = 0
            }
        }
        for (i in texts.indices) {
            val len = texts[i].length
            if (current.isEmpty()) {
                current.add(i)
                currentLen = len
                continue
            }
            val sameDirection = directions[i] == directions[current.first()]
            val newLen = currentLen + JOINER.length + len
            if (!sameDirection || newLen > maxChars) {
                flush()
                current.add(i)
                currentLen = len
            } else {
                current.add(i)
                currentLen = newLen
            }
        }
        flush()
        return batches
    }

    /**
     * Splits a batched translation back into one piece per block, or null
     * when the model did not preserve the separators (wrong piece count or
     * an empty piece) — the caller then falls back to per-block requests.
     */
    fun split(translated: String, expected: Int): List<String>? {
        if (expected <= 0) return null
        if (expected == 1) return listOf(translated.trim()).takeIf { it[0].isNotEmpty() }
        val parts = translated.split(SEPARATOR).map { it.trim() }
        if (parts.size != expected) return null
        if (parts.any { it.isEmpty() }) return null
        return parts
    }
}
