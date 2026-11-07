package com.example.renamer

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PdfScanner(private val context: Context, private val crossref: CrossrefClient = CrossrefClient()) {
    private val history = context.getSharedPreferences("paper_history_v1", Context.MODE_PRIVATE)

    private fun key(file: DocumentFile) = file.uri.toString()
    private fun fingerprint(file: DocumentFile) =
        "${file.name}|${file.length()}|${file.lastModified()}"

    fun rememberRenamed(file: DocumentFile) {
        history.edit().putString(key(file), JSONObject()
            .put("fingerprint", fingerprint(file)).put("done", true).toString()).apply()
    }

    suspend fun scan(directory: DocumentFile, force: Boolean = false, onProgress: suspend (Int, Int) -> Unit): List<PaperCandidate> {
        val files = withContext(Dispatchers.IO) {
            directory.listFiles().filter { it.isFile && (it.name?.endsWith(".pdf", true) == true) }
        }
        return withContext(Dispatchers.IO) {
            files.mapIndexedNotNull { index, file ->
                onProgress(index + 1, files.size)
                val saved = if (force) null else runCatching {
                    JSONObject(history.getString(key(file), null) ?: "")
                }.getOrNull()?.takeIf { it.optString("fingerprint") == fingerprint(file) }
                if (saved?.optBoolean("done") == true) return@mapIndexedNotNull null
                if (saved != null && saved.has("target")) {
                    return@mapIndexedNotNull PaperCandidate(file, file.name ?: "unknown.pdf",
                        saved.optString("doi"), saved.getString("target"))
                }
                if (force) history.edit().remove(key(file)).apply()
                val candidate = inspect(file)
                val target = candidate.newName
                if (target != null) {
                    val done = target == candidate.originalName
                    history.edit().putString(key(file), JSONObject()
                        .put("fingerprint", fingerprint(file))
                        .put("done", done).put("target", target)
                        .put("doi", candidate.doi).toString()).apply()
                    if (done) return@mapIndexedNotNull null
                }
                candidate
            }
        }
    }

    private suspend fun inspect(file: DocumentFile): PaperCandidate = withContext(Dispatchers.IO) {
        val original = file.name ?: "unknown.pdf"
        runCatching {
            val doi = extractDoi(file) ?: error("DOIを検出できません")
            val metadata = crossref.lookup(doi)
            PaperCandidate(file, original, doi, NameFormatter.format(metadata))
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            PaperCandidate(file, original, error = error.message ?: "解析に失敗しました")
        }
    }

    private fun extractDoi(file: DocumentFile): String? {
        context.contentResolver.openInputStream(file.uri)?.use { input ->
            PDDocument.load(input).use { document ->
                val metadataText = listOfNotNull(
                    document.documentInformation?.subject,
                    document.documentInformation?.keywords,
                    document.documentInformation?.title,
                ).joinToString(" ")
                DoiExtractor.from(metadataText)?.let { return it }
                val pages = document.numberOfPages.coerceAtMost(3)
                if (pages > 0) {
                    val text = PDFTextStripper().apply {
                        startPage = 1
                        endPage = pages
                    }.getText(document)
                    return DoiExtractor.from(text)
                }
            }
        }
        return null
    }
}
