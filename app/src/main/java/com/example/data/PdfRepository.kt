package com.example.data

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import com.example.pdf.PageText
import com.example.pdf.PdfEngine
import com.example.pdf.PdfTextExtractor
import com.example.pdf.SamplePdfGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class PdfRepository(
    private val context: Context,
    private val dao: PdfHistoryDao
) {
    companion object {
        private const val TAG = "PdfRepository"
    }

    val allHistory: Flow<List<PdfItem>> = dao.getAllHistory()

    suspend fun openAndRecordPdf(uri: Uri, overrideFileName: String? = null, password: String = ""): Pair<PdfItem, List<PageText>> = withContext(Dispatchers.IO) {
        val uriString = uri.toString()
        val existingItem = dao.getByUriSync(uriString)

        val localFile: File = when (uri.scheme) {
            "file" -> {
                val f = File(uri.path ?: "")
                if (f.exists() && f.length() > 0L) f else PdfEngine.copyUriToLocalCache(context, uri)
            }
            else -> {
                val existingFile = existingItem?.filePath?.let { File(it) }
                if (existingFile != null && existingFile.exists() && existingFile.length() > 0L) {
                    existingFile
                } else {
                    PdfEngine.copyUriToLocalCache(context, uri)
                }
            }
        }

        val resolvedFileName = overrideFileName
            ?: existingItem?.fileName
            ?: getFileNameFromUri(uri)
            ?: localFile.name
            ?: "Document.pdf"

        val fileSize = localFile.length()

        // Page count comes from PdfRenderer (cheap metadata read). The PDFBox
        // full parse in PdfSecurity.pageCount is only a fallback for files the
        // renderer cannot open at all; password authentication itself happens in
        // PdfEngine/PdfSecurity.prepareForRendering, which only loads PDFBox when
        // the file actually looks encrypted (or a password was supplied).
        val pageCount = probePageCountWithRenderer(localFile)
            ?: com.example.pdf.PdfSecurity.pageCount(context, localFile, password)
        val thumbPath = existingItem?.thumbnailPath

        val initialPages = (0 until pageCount).map { i ->
            PageText(pageIndex = i, fullText = "", paragraphs = emptyList(), lines = emptyList())
        }

        val itemToSave = (existingItem ?: PdfItem(
            uriString = uriString,
            fileName = resolvedFileName,
            filePath = localFile.absolutePath,
            pageCount = pageCount,
            thumbnailPath = thumbPath,
            fileSizeBytes = fileSize,
            noteContent = ""
        )).copy(
            fileName = resolvedFileName,
            filePath = localFile.absolutePath,
            lastViewedTimestamp = System.currentTimeMillis(),
            pageCount = pageCount,
            thumbnailPath = thumbPath,
            fileSizeBytes = fileSize
        )

        dao.insert(itemToSave)

        // Defer thumbnail generation to background so it doesn't block PDF opening
        if (thumbPath.isNullOrEmpty() || !File(thumbPath).exists()) {
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                try {
                    val engine = PdfEngine(context, localFile)
                    val generatedThumb = engine.generateAndSaveThumbnail()
                    engine.close()
                    if (!generatedThumb.isNullOrEmpty()) {
                        dao.updateThumbnail(itemToSave.uriString, generatedThumb)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Async thumbnail generation failed: ${e.message}")
                }
            }
        }

        Pair(itemToSave, initialPages)
    }

    /** Cheap page-count probe via PdfRenderer; null when the file cannot be opened that way. */
    private fun probePageCountWithRenderer(file: File): Int? {
        if (!file.exists() || file.length() == 0L) return null
        return try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                android.graphics.pdf.PdfRenderer(pfd).use { renderer -> renderer.pageCount }
            }
        } catch (e: Exception) {
            Log.w(TAG, "PdfRenderer page-count probe failed, falling back to PDFBox: ${e.message}")
            null
        }
    }

    suspend fun openSamplePdf(): Pair<PdfItem, List<PageText>> = withContext(Dispatchers.IO) {
        val sampleFile = SamplePdfGenerator.getOrCreateSamplePdf(context)
        val uri = Uri.fromFile(sampleFile)
        openAndRecordPdf(uri, overrideFileName = "Sample_Guide.pdf")
    }

    suspend fun updateNote(uriString: String, note: String) = withContext(Dispatchers.IO) {
        dao.updateNote(uriString, note)
    }

    suspend fun updateFileName(uriString: String, newName: String) = withContext(Dispatchers.IO) {
        val trimmed = newName.trim()
        val finalName = if (trimmed.endsWith(".pdf", ignoreCase = true)) trimmed else "$trimmed.pdf"
        dao.updateFileName(uriString, finalName)
    }

    suspend fun updateLastPage(uriString: String, page: Int) = withContext(Dispatchers.IO) {
        dao.updateLastPage(uriString, page, System.currentTimeMillis())
    }

    suspend fun deleteHistoryItem(item: PdfItem) = withContext(Dispatchers.IO) {
        dao.deleteById(item.id)
        item.thumbnailPath?.let { path ->
            try {
                val f = File(path)
                if (f.exists()) f.delete()
            } catch (_: Exception) {}
        }
        // Also remove the saved_pdfs copy, unless another history entry still
        // references the same file. Files outside saved_pdfs are the user's own
        // originals and are never touched.
        val path = item.filePath
        if (path != null && isInSavedPdfs(path)) {
            try {
                val stillReferenced = dao.getAllHistorySync().any { it.filePath == path }
                if (!stillReferenced) {
                    val f = File(path)
                    if (f.exists()) f.delete()
                    // Remove the freshness sidecar written by copyUriToLocalCache.
                    val meta = File(f.parentFile, f.name.removeSuffix(".pdf") + ".meta")
                    if (meta.exists()) meta.delete()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete saved copy $path: ${e.message}")
            }
        }
    }

    suspend fun clearAllHistory() = withContext(Dispatchers.IO) {
        // Collect owned files first, then drop the rows.
        val items = try { dao.getAllHistorySync() } catch (_: Exception) { emptyList() }
        dao.clearAll()
        for (item in items) {
            item.thumbnailPath?.let { path ->
                try {
                    val f = File(path)
                    if (f.exists()) f.delete()
                } catch (_: Exception) {}
            }
            val path = item.filePath
            if (path != null && isInSavedPdfs(path)) {
                try {
                    val f = File(path)
                    if (f.exists()) f.delete()
                    val meta = File(f.parentFile, f.name.removeSuffix(".pdf") + ".meta")
                    if (meta.exists()) meta.delete()
                } catch (_: Exception) {}
            }
        }
    }

    private fun isInSavedPdfs(path: String): Boolean {
        val savedDir = File(context.filesDir, "saved_pdfs")
        return try {
            File(path).canonicalPath.startsWith(savedDir.canonicalPath + File.separator)
        } catch (_: Exception) {
            false
        }
    }

    private fun getFileNameFromUri(uri: Uri): String? {
        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1 && cursor.moveToFirst()) {
                        return cursor.getString(nameIndex)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting filename from URI: ${e.message}")
            }
        }
        val path = uri.path ?: return null
        val cut = path.lastIndexOf('/')
        return if (cut != -1) path.substring(cut + 1) else path
    }
}
