package com.example.pdf

import android.content.Context
import android.util.Log
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import java.io.File
import java.io.RandomAccessFile

/** Keeps the original encrypted document intact; passwords are never persisted. */
object PdfSecurity {
    private const val TAG = "PdfSecurity"

    fun pageCount(context: Context, source: File, password: String): Int {
        PDFBoxResourceLoader.init(context.applicationContext)
        return PDDocument.load(source, password).use { it.numberOfPages }
    }

    /**
     * Cheap encryption probe: scans the raw bytes for the "/Encrypt" entry of the
     * trailer dictionary instead of fully parsing the document with PDFBox.
     * Used so the expensive PDFBox load only happens when encryption is suspected.
     */
    fun looksEncrypted(source: File): Boolean {
        if (!source.exists() || source.length() == 0L) return false
        val marker = "/Encrypt".toByteArray(Charsets.US_ASCII)
        return try {
            RandomAccessFile(source, "r").use { raf ->
                val chunkSize = 64 * 1024
                val buffer = ByteArray(chunkSize + marker.size)
                var offset = 0L
                val length = raf.length()
                while (offset < length) {
                    raf.seek(offset)
                    val read = raf.read(buffer)
                    if (read <= 0) break
                    // Search the bytes actually read (plus overlap into the marker tail).
                    val limit = minOf(read, buffer.size - marker.size)
                    var i = 0
                    while (i <= limit - marker.size) {
                        var matched = true
                        for (j in marker.indices) {
                            if (buffer[i + j] != marker[j]) { matched = false; break }
                        }
                        if (matched) return true
                        i++
                    }
                    if (read < buffer.size) break
                    offset += chunkSize
                }
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Encryption probe failed, assuming encrypted: ${e.message}")
            true
        }
    }

    fun prepareForRendering(context: Context, source: File, password: String): File {
        // Fast path: no password supplied and no encryption marker -> render the
        // original file directly without a full PDFBox parse.
        if (password.isEmpty() && !looksEncrypted(source)) {
            return source
        }
        PDFBoxResourceLoader.init(context.applicationContext)
        return PDDocument.load(source, password).use { document ->
            if (!document.isEncrypted) return@use source
            val temporary = File.createTempFile("unlocked_", ".pdf", context.cacheDir)
            try {
                document.setAllSecurityToBeRemoved(true)
                document.save(temporary)
                temporary
            } catch (error: Throwable) {
                temporary.delete()
                throw error
            }
        }
    }

    /**
     * Deletes decrypted plaintext PDFs (and export temp files) left behind in the
     * cache directory by a process kill or crash. Files younger than [maxAgeMs]
     * are kept, because another activity instance (documentLaunchMode="always")
     * may still be rendering from one; on Linux/Android an already-open file
     * descriptor stays valid even if the path is deleted afterwards.
     */
    fun cleanupStaleTempFiles(context: Context, maxAgeMs: Long = 60 * 60 * 1000L) {
        try {
            val cacheDir = context.cacheDir ?: return
            val cutoff = System.currentTimeMillis() - maxAgeMs
            cacheDir.listFiles()?.forEach { file ->
                val name = file.name
                if ((name.startsWith("unlocked_") || name.startsWith("export_")) &&
                    file.isFile && file.lastModified() in 1..cutoff
                ) {
                    if (file.delete()) {
                        Log.i(TAG, "Removed stale temp file: $name")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Stale temp cleanup failed: ${e.message}")
        }
    }

    fun saveWithoutPassword(context: Context, source: File, destination: File) {
        PDFBoxResourceLoader.init(context.applicationContext)
        PDDocument.load(source).use { document ->
            document.setAllSecurityToBeRemoved(true)
            document.save(destination)
        }
    }
}
