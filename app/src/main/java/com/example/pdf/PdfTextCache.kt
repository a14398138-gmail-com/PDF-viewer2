package com.example.pdf

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * On-disk cache for extracted page text, keyed by a SHA-256 hash of the PDF
 * file contents. Reopening the same document reuses native/OCR results
 * instead of re-running the whole extraction (and OCR) pipeline.
 *
 * Only the text layer is cached (never the document itself). Entries live in
 * cacheDir, so the system may evict them at any time; all reads fail soft.
 */
object PdfTextCache {
    private const val TAG = "PdfTextCache"
    private const val DIR_NAME = "text_cache"

    fun keyFor(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun pageFile(context: Context, key: String, pageIndex: Int): File {
        val dir = File(File(context.cacheDir, DIR_NAME), key)
        return File(dir, "page_$pageIndex.json")
    }

    fun loadPage(context: Context, key: String, pageIndex: Int): PageText? {
        val file = pageFile(context, key, pageIndex)
        if (!file.exists() || file.length() == 0L) return null
        return try {
            val json = JSONObject(file.readText())
            val words = mutableListOf<TextWord>()
            val wordsArray = json.optJSONArray("words") ?: JSONArray()
            for (i in 0 until wordsArray.length()) {
                val w = wordsArray.getJSONObject(i)
                words.add(
                    TextWord(
                        index = w.optInt("index", i),
                        text = w.optString("text"),
                        relX = w.optDouble("relX", 0.0).toFloat(),
                        relY = w.optDouble("relY", 0.0).toFloat(),
                        relWidth = w.optDouble("relWidth", 0.0).toFloat(),
                        relHeight = w.optDouble("relHeight", 0.0).toFloat(),
                        lineIndex = w.optInt("lineIndex", 0)
                    )
                )
            }
            val fullText = json.optString("fullText")
            // Rebuild lines by grouping words per lineIndex (order preserved).
            val lines = words.groupBy { it.lineIndex }
                .entries.sortedBy { it.key }
                .map { (lineIndex, lineWords) ->
                    TextLine(
                        lineIndex = lineIndex,
                        text = lineWords.joinToString("") { it.text },
                        relX = lineWords.minOf { it.relX },
                        relY = lineWords.minOf { it.relY },
                        relWidth = lineWords.maxOf { it.relX + it.relWidth } - lineWords.minOf { it.relX },
                        relHeight = lineWords.maxOf { it.relY + it.relHeight } - lineWords.minOf { it.relY },
                        words = lineWords
                    )
                }
            PageText(
                pageIndex = pageIndex,
                fullText = fullText,
                paragraphs = if (fullText.isBlank()) emptyList() else listOf(fullText),
                lines = lines,
                words = words,
                isNativeTextLayer = json.optBoolean("isNative", false)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read cached text for page $pageIndex: ${e.message}")
            null
        }
    }

    // ---- Block translations (PLaMo paper translation) ----------------------

    private fun translationFile(context: Context, key: String, pageIndex: Int): File {
        val dir = File(File(context.cacheDir, DIR_NAME), key)
        return File(dir, "trans_$pageIndex.json")
    }

    /**
     * Returns blockIndex -> (source text, translated text) for [pageIndex].
     * Callers must verify the stored source still matches the block they are
     * about to display (the layout may shift if the extractor changes).
     */
    @Synchronized
    fun loadTranslations(context: Context, key: String, pageIndex: Int): Map<Int, Pair<String, String>> {
        val file = translationFile(context, key, pageIndex)
        if (!file.exists() || file.length() == 0L) return emptyMap()
        return try {
            val json = JSONObject(file.readText())
            val result = mutableMapOf<Int, Pair<String, String>>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val entry = json.optJSONObject(k) ?: continue
                val src = entry.optString("src")
                val tgt = entry.optString("tgt")
                if (src.isNotEmpty() && tgt.isNotEmpty()) {
                    k.toIntOrNull()?.let { result[it] = src to tgt }
                }
            }
            result
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read cached translations for page $pageIndex: ${e.message}")
            emptyMap()
        }
    }

    @Synchronized
    fun saveTranslation(
        context: Context,
        key: String,
        pageIndex: Int,
        blockIndex: Int,
        sourceText: String,
        translatedText: String
    ) {
        if (translatedText.isBlank()) return
        try {
            val file = translationFile(context, key, pageIndex)
            file.parentFile?.mkdirs()
            val json = if (file.exists() && file.length() > 0L) {
                try { JSONObject(file.readText()) } catch (_: Exception) { JSONObject() }
            } else {
                JSONObject()
            }
            json.put(
                blockIndex.toString(),
                JSONObject().apply {
                    put("src", sourceText)
                    put("tgt", translatedText)
                }
            )
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.toString())
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cache translation for page $pageIndex block $blockIndex: ${e.message}")
        }
    }

    fun savePage(context: Context, key: String, pageText: PageText) {
        // Empty results are not cached: they may be a transient OCR failure and
        // must be retried on the next open.
        if (pageText.words.isEmpty() || pageText.fullText.isBlank()) return
        try {
            val file = pageFile(context, key, pageText.pageIndex)
            file.parentFile?.mkdirs()
            val wordsArray = JSONArray()
            for (word in pageText.words) {
                wordsArray.put(
                    JSONObject().apply {
                        put("index", word.index)
                        put("text", word.text)
                        put("relX", word.relX.toDouble())
                        put("relY", word.relY.toDouble())
                        put("relWidth", word.relWidth.toDouble())
                        put("relHeight", word.relHeight.toDouble())
                        put("lineIndex", word.lineIndex)
                    }
                )
            }
            val json = JSONObject().apply {
                put("fullText", pageText.fullText)
                put("isNative", pageText.isNativeTextLayer)
                put("words", wordsArray)
            }
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.toString())
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cache text for page ${pageText.pageIndex}: ${e.message}")
        }
    }
}
