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
     * absolutePath -> (size:mtime, result) of probes in this process. Opening a
     * document probes the same file more than once (viewer engine, thumbnail
     * engine) and on every reopen; each probe reads the whole file.
     */
    private val encryptionProbeMemo = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Boolean>>()

    /**
     * Cheap encryption probe: scans the raw bytes for the "/Encrypt" entry of the
     * trailer dictionary instead of fully parsing the document with PDFBox.
     * Used so the expensive PDFBox load only happens when encryption is suspected.
     */
    fun looksEncrypted(source: File): Boolean {
        if (!source.exists() || source.length() == 0L) return false
        val path = source.absolutePath
        val fingerprint = "${source.length()}:${source.lastModified()}"
        encryptionProbeMemo[path]?.let { (memoFp, result) -> if (memoFp == fingerprint) return result }
        val result = scanForEncryptMarker(source)
        encryptionProbeMemo[path] = fingerprint to result
        return result
    }

    private fun scanForEncryptMarker(source: File): Boolean {
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
                    // Every start position of this chunk is checked, reading up to
                    // marker.size - 1 bytes into the overlap. (The old bound stopped
                    // marker.size bytes early, so a marker straddling a chunk
                    // boundary was never found.)
                    val lastStart = minOf(read - marker.size, chunkSize - 1)
                    val first = marker[0]
                    var i = 0
                    while (i <= lastStart) {
                        if (buffer[i] == first) {
                            var matched = true
                            for (j in 1 until marker.size) {
                                if (buffer[i + j] != marker[j]) { matched = false; break }
                            }
                            if (matched) return true
                        }
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
