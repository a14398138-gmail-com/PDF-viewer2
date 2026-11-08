package com.example.pdf

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLongArray
import kotlin.math.roundToInt

/**
 * Type-safe, structured cache key for rendered PDF page bitmaps.
 * Avoids fragile string concatenation and delimiter parsing.
 */
data class PageCacheKey(
    val filePath: String,
    val pageIndex: Int,
    val targetWidth: Int
)

class PdfEngine(
    private val context: Context,
    private val localPdfFile: File,
    password: String = ""
) : AutoCloseable {

    companion object {
        private const val TAG = "PdfEngine"

        /** Hard cap on rendered pixels per page bitmap (~40MB at ARGB_8888). */
        private const val MAX_BITMAP_PIXELS = 10_000_000L
        private const val MAX_RENDER_ATTEMPTS = 4

        // Dynamic memory-aware LRU Cache
        private val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        // Default to 1/6th of available memory or max 128MB
        private val defaultCacheCapacityKb = (maxMemoryKb / 6).coerceIn(16 * 1024, 128 * 1024)

        private val bitmapCache = object : LruCache<PageCacheKey, Bitmap>(defaultCacheCapacityKb) {
            override fun sizeOf(key: PageCacheKey, bitmap: Bitmap): Int {
                return (bitmap.byteCount / 1024).coerceAtLeast(1)
            }

            override fun entryRemoved(
                evicted: Boolean,
                key: PageCacheKey,
                oldValue: Bitmap,
                newValue: Bitmap?
            ) {
                super.entryRemoved(evicted, key, oldValue, newValue)
                if (evicted) {
                    Log.d(TAG, "LRU Evicted page bitmap from cache: page ${key.pageIndex} of ${key.filePath} (size: ${oldValue.byteCount / 1024} KB)")
                }
            }
        }

        private var isComponentCallbacksRegistered = false

        /**
         * Initializes automatic memory pressure callbacks to release off-screen/cached bitmaps.
         */
        fun initMemoryPressureCallbacks(appContext: Context) {
            if (isComponentCallbacksRegistered) return
            synchronized(this) {
                if (isComponentCallbacksRegistered) return
                val app = appContext.applicationContext
                app.registerComponentCallbacks(object : ComponentCallbacks2 {
                    override fun onTrimMemory(level: Int) {
                        handleTrimMemory(level)
                    }

                    override fun onConfigurationChanged(newConfig: Configuration) {}

                    override fun onLowMemory() {
                        handleLowMemory()
                    }
                })
                isComponentCallbacksRegistered = true
                Log.d(TAG, "Initialized PdfEngine memory pressure callbacks (Cache Capacity: ${defaultCacheCapacityKb / 1024}MB)")
            }
        }

        fun handleTrimMemory(level: Int) {
            Log.i(TAG, "onTrimMemory triggered with level: $level (current cache size: ${bitmapCache.size() / 1024}MB)")
            // NOTE: the constants are not ordered by severity across the two families
            // (RUNNING_* = 5/10/15, UI_HIDDEN = 20, BACKGROUND = 40, MODERATE = 60,
            // COMPLETE = 80), so each level must be matched explicitly, highest first.
            // The previous "level >= RUNNING_LOW" branch silently absorbed COMPLETE.
            when {
                level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                    bitmapCache.evictAll()
                    Log.w(TAG, "Evicted all PDF bitmap caches due to TRIM_MEMORY_COMPLETE")
                }
                level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE -> {
                    bitmapCache.trimToSize(defaultCacheCapacityKb / 4)
                    Log.i(TAG, "Trimmed PDF bitmap cache to 25% (${bitmapCache.size() / 1024}MB)")
                }
                level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ||
                level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
                    bitmapCache.trimToSize(defaultCacheCapacityKb / 2)
                    Log.i(TAG, "Trimmed PDF bitmap cache to 50% (${bitmapCache.size() / 1024}MB)")
                }
                level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                    bitmapCache.trimToSize(defaultCacheCapacityKb / 4)
                    Log.i(TAG, "Trimmed PDF bitmap cache to 25% (${bitmapCache.size() / 1024}MB)")
                }
                level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                    bitmapCache.trimToSize(defaultCacheCapacityKb / 2)
                    Log.i(TAG, "Trimmed PDF bitmap cache to 50% (${bitmapCache.size() / 1024}MB)")
                }
                level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> {
                    bitmapCache.trimToSize(defaultCacheCapacityKb * 3 / 4)
                    Log.i(TAG, "Trimmed PDF bitmap cache to 75% (${bitmapCache.size() / 1024}MB)")
                }
            }
        }

        fun handleLowMemory() {
            Log.w(TAG, "onLowMemory triggered: clearing entire PDF bitmap LRU cache")
            bitmapCache.evictAll()
        }

        /**
         * Evicts cached bitmaps for pages that are not in the provided set of visible/nearby pages.
         */
        fun evictOffScreenBitmaps(filePath: String, keepPages: Set<Int>) {
            val snapshot = bitmapCache.snapshot()
            for ((key, _) in snapshot) {
                if (key.filePath == filePath && key.pageIndex !in keepPages) {
                    bitmapCache.remove(key)
                }
            }
        }

        /**
         * Clears all cached bitmaps for a specific PDF file.
         */
        fun clearCacheForFile(filePath: String) {
            val snapshot = bitmapCache.snapshot()
            for ((key, _) in snapshot) {
                if (key.filePath == filePath) {
                    bitmapCache.remove(key)
                }
            }
        }

        // Reference counting for the shared static bitmap cache: with
        // documentLaunchMode="always" several engines may render the same file,
        // and one engine closing must not wipe the pages another is displaying.
        private val openEngineCounts = mutableMapOf<String, Int>()

        @Synchronized
        fun registerEngine(filePath: String) {
            openEngineCounts[filePath] = (openEngineCounts[filePath] ?: 0) + 1
        }

        /** Returns true when this was the last open engine for [filePath]. */
        @Synchronized
        fun unregisterEngine(filePath: String): Boolean {
            val remaining = (openEngineCounts[filePath] ?: 1) - 1
            if (remaining <= 0) {
                openEngineCounts.remove(filePath)
                return true
            }
            openEngineCounts[filePath] = remaining
            return false
        }

        suspend fun copyUriToLocalCache(context: Context, uri: Uri): File = withContext(Dispatchers.IO) {
            val savedDir = File(context.filesDir, "saved_pdfs")
            if (!savedDir.exists()) savedDir.mkdirs()

            val hash = md5(uri.toString())
            val targetFile = File(savedDir, "pdf_$hash.pdf")
            val metaFile = File(savedDir, "pdf_$hash.meta")

            // Validate a cached copy against the source size (and mtime for file://
            // URIs) so a replaced document on the provider side is not shadowed
            // forever by the first copy we made.
            val sourceSize = querySourceSize(context, uri)
            val sourceMtime = if (uri.scheme == "file") {
                uri.path?.let { File(it) }?.takeIf { it.exists() }?.lastModified() ?: -1L
            } else -1L

            if (targetFile.exists() && targetFile.length() > 0L) {
                val cachedMatches = if (sourceSize > 0L) {
                    targetFile.length() == sourceSize && readMetaLong(metaFile, 0) == sourceSize &&
                        (sourceMtime <= 0L || readMetaLong(metaFile, 1) == sourceMtime)
                } else {
                    // Source size unknown: only trust the cache if we recorded one.
                    readMetaLong(metaFile, 0) == targetFile.length()
                }
                if (cachedMatches) {
                    return@withContext targetFile
                }
                Log.i(TAG, "Cached copy of $uri is stale (size/mtime changed); re-copying")
            }

            // Also check legacy cacheDir
            val legacyCacheDir = File(context.cacheDir, "opened_pdfs")
            val legacyFile = File(legacyCacheDir, "pdf_$hash.pdf")
            if (!targetFile.exists() && legacyFile.exists() && legacyFile.length() > 0L &&
                (sourceSize <= 0L || legacyFile.length() == sourceSize)
            ) {
                try {
                    legacyFile.copyTo(targetFile, overwrite = true)
                    writeMeta(metaFile, targetFile.length(), sourceMtime)
                    return@withContext targetFile
                } catch (_: Exception) {
                    return@withContext legacyFile
                }
            }

            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output, bufferSize = 65536)
                }
            } ?: throw IllegalStateException("Could not open input stream from URI: $uri")

            writeMeta(metaFile, targetFile.length(), sourceMtime)
            targetFile
        }

        private fun querySourceSize(context: Context, uri: Uri): Long {
            if (uri.scheme == "file") {
                return uri.path?.let { File(it) }?.takeIf { it.exists() }?.length() ?: -1L
            }
            return try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (idx != -1 && cursor.moveToFirst() && !cursor.isNull(idx)) cursor.getLong(idx) else -1L
                } ?: -1L
            } catch (e: Exception) {
                Log.w(TAG, "Could not query size for $uri: ${e.message}")
                -1L
            }
        }

        private fun readMetaLong(metaFile: File, index: Int): Long {
            return try {
                if (!metaFile.exists()) -1L
                else metaFile.readLines().getOrNull(index)?.trim()?.toLongOrNull() ?: -1L
            } catch (_: Exception) {
                -1L
            }
        }

        private fun writeMeta(metaFile: File, size: Long, mtime: Long) {
            try {
                metaFile.writeText("$size\n$mtime\n")
            } catch (_: Exception) {
                // Metadata is only a freshness hint; failing to write it must not
                // break opening the document.
            }
        }

        private fun md5(input: String): String {
            val md = MessageDigest.getInstance("MD5")
            return md.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
        }

        /** Screen width in px exactly as the viewer computes it (`screenWidthDp.dp.roundToPx()`). */
        fun screenWidthPx(context: Context): Int {
            val res = context.resources
            return (res.configuration.screenWidthDp * res.displayMetrics.density).roundToInt()
        }

        /**
         * Bitmap width the viewer requests for a page at [zoomScale]. Shared by
         * the page composable and the open-time pre-render so both hit the same
         * cache entry (the pre-render used to use a different width, so the
         * first page was rendered twice before it appeared).
         */
        fun displayTargetWidth(screenWidthPx: Int, zoomScale: Float): Int {
            // Each zoom tier renders at the largest zoom shown within it, so the
            // page stays sharp up to the next tier. (Previously tier x 1.5,
            // which oversampled the middle tiers by up to 1.5x per axis and
            // made the re-render after a pinch slower and heavier.) At 1x the
            // width is unchanged: 1.5x covers zooming up to the first tier.
            val renderScale = when {
                zoomScale >= 2.5f -> 3.75f
                zoomScale >= 2f -> 2.5f
                zoomScale >= 1.5f -> 2f
                else -> 1.5f
            }
            return (screenWidthPx * renderScale).toInt()
        }

        private fun packSize(width: Int, height: Int): Long =
            (width.toLong() shl 32) or (height.toLong() and 0xFFFFFFFFL)
    }

    private var fileDescriptor: ParcelFileDescriptor? = null
    @Volatile
    private var pdfRenderer: PdfRenderer? = null

    /**
     * Serializes page renders as a suspending queue: waiting requests no
     * longer block Dispatchers.Default threads on the engine monitor, and a
     * request cancelled while queued (its page scrolled away) is dropped
     * before rendering. The monitor still guards every PdfRenderer access.
     */
    private val renderMutex = Mutex()

    val file: File = PdfSecurity.prepareForRendering(context, localPdfFile, password)
    val pageCount: Int

    /** Page sizes in points, packed by [packSize]; 0 = not measured yet. */
    private val pageSizes: AtomicLongArray

    init {
        try {
            fileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            pdfRenderer = PdfRenderer(fileDescriptor!!)
            pageCount = pdfRenderer?.pageCount ?: 0
            pageSizes = AtomicLongArray(pageCount.coerceAtLeast(0))
            registerEngine(localPdfFile.absolutePath)
        } catch (error: Throwable) {
            fileDescriptor?.close()
            if (file != localPdfFile) file.delete()
            throw error
        }
        // The first page's size is the placeholder for pages not measured yet.
        if (pageCount > 0) getPageDimensions(0)
    }

    private fun recordPageSize(pageIndex: Int, width: Int, height: Int) {
        if (pageIndex in 0 until pageCount && width > 0 && height > 0) {
            pageSizes.set(pageIndex, packSize(width, height))
        }
    }

    /**
     * Page size if already known, without touching the renderer. Safe to call
     * from composition: [getPageDimensions] has to wait for the engine monitor,
     * which a render running on a background thread holds for its whole
     * duration, so calling it on the main thread stalled scrolling.
     */
    fun peekPageDimensions(pageIndex: Int): Pair<Int, Int>? {
        if (pageIndex < 0 || pageIndex >= pageCount) return null
        val packed = pageSizes.get(pageIndex)
        if (packed == 0L) return null
        return Pair((packed ushr 32).toInt(), packed.toInt())
    }

    /** Best guess for a page not measured yet: the first page's size (most documents use one size). */
    fun estimatedPageDimensions(): Pair<Int, Int> = peekPageDimensions(0) ?: Pair(595, 842)

    fun getPageDimensions(pageIndex: Int): Pair<Int, Int> {
        val renderer = pdfRenderer ?: return Pair(595, 842)
        if (pageIndex < 0 || pageIndex >= pageCount) return Pair(595, 842)
        peekPageDimensions(pageIndex)?.let { return it }

        return synchronized(this) {
            try {
                renderer.openPage(pageIndex).use { page ->
                    recordPageSize(pageIndex, page.width, page.height)
                    Pair(page.width, page.height)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting page dimensions: ${e.message}")
                Pair(595, 842)
            }
        }
    }

    /**
     * Measures every page in the background (each measurement holds the engine
     * monitor only briefly, so renders interleave), so pages scrolled into
     * view get their real aspect ratio immediately.
     */
    suspend fun preloadPageDimensions() = withContext(Dispatchers.IO) {
        for (pageIndex in 0 until pageCount) {
            ensureActive()
            if (pdfRenderer == null) return@withContext
            if (pageSizes.get(pageIndex) == 0L) getPageDimensions(pageIndex)
        }
    }

    fun getCachedBitmap(pageIndex: Int, targetWidth: Int = 1080): Bitmap? {
        val cacheKey = PageCacheKey(localPdfFile.absolutePath, pageIndex, targetWidth)
        val cached = bitmapCache.get(cacheKey)
        return if (cached != null && !cached.isRecycled) cached else null
    }

    suspend fun renderPageBitmap(
        pageIndex: Int,
        targetWidth: Int = 1080,
        densityDpi: Float = 2.0f
    ): Bitmap? = withContext(Dispatchers.Default) {
        if (pageIndex < 0 || pageIndex >= pageCount) return@withContext null
        val cacheKey = PageCacheKey(localPdfFile.absolutePath, pageIndex, targetWidth)

        bitmapCache.get(cacheKey)?.let { cached ->
            if (!cached.isRecycled) return@withContext cached
        }

        renderMutex.withLock {
            // Dropped if the requester went away while queued.
            ensureActive()
            // Another request may have rendered this page while we waited.
            bitmapCache.get(cacheKey)?.let { cached ->
                if (!cached.isRecycled) return@withLock cached
            }
            renderLocked(pageIndex, targetWidth, cacheKey)
        }
    }

    /**
     * Runs [block] with a bitmap of [pageIndex] for OCR. Reuses a cached
     * render requested at least [minWidth] wide (normally the page the user is
     * looking at) instead of rendering the page a second time; otherwise
     * renders one at [minWidth] that is *not* put into the shared LRU cache
     * (OCR renders used to evict the pages on screen) and is recycled after
     * [block]. [block] must not keep the bitmap. Null if no bitmap is available.
     */
    suspend fun <T> withOcrBitmap(pageIndex: Int, minWidth: Int = 1080, block: suspend (Bitmap) -> T): T? {
        if (pageIndex < 0 || pageIndex >= pageCount) return null
        findCachedBitmap(pageIndex, minWidth)?.let { return block(it) }
        val rendered = withContext(Dispatchers.Default) {
            renderMutex.withLock {
                ensureActive()
                renderLocked(pageIndex, minWidth, cacheKey = null)
            }
        } ?: return null
        return try {
            block(rendered)
        } finally {
            if (!rendered.isRecycled) rendered.recycle()
        }
    }

    /** Smallest cached render of [pageIndex] requested at >= [minWidth] px. */
    private fun findCachedBitmap(pageIndex: Int, minWidth: Int): Bitmap? {
        val path = localPdfFile.absolutePath
        var best: Bitmap? = null
        var bestWidth = Int.MAX_VALUE
        for ((key, bitmap) in bitmapCache.snapshot()) {
            if (key.filePath == path && key.pageIndex == pageIndex && key.targetWidth >= minWidth &&
                key.targetWidth < bestWidth && !bitmap.isRecycled
            ) {
                best = bitmap
                bestWidth = key.targetWidth
            }
        }
        return best
    }

    /** Renders [pageIndex]; caller holds [renderMutex]. Cached under [cacheKey] unless it is null. */
    private fun renderLocked(pageIndex: Int, targetWidth: Int, cacheKey: PageCacheKey?): Bitmap? {
        val renderer = pdfRenderer ?: return null

        return synchronized(this@PdfEngine) {
            // OOM-safe rendering: size is chosen from the requested width without a
            // 1.0 lower bound (small requests may render below the page's point size),
            // capped by a maximum pixel area, and on OutOfMemoryError the cache is
            // trimmed and the render retried at progressively lower resolutions.
            // OutOfMemoryError is an Error, not an Exception, so it must be caught
            // explicitly or it escapes and crashes the app on large-format pages.
            var attemptWidth = targetWidth
            var lastError: Throwable? = null
            for (attempt in 0 until MAX_RENDER_ATTEMPTS) {
                try {
                    return@synchronized renderer.openPage(pageIndex).use { page ->
                        recordPageSize(pageIndex, page.width, page.height)
                        val originalWidth = page.width.coerceAtLeast(1)
                        val originalHeight = page.height.coerceAtLeast(1)

                        var scale = attemptWidth.toFloat() / originalWidth.toFloat()
                        val areaScale = kotlin.math.sqrt(
                            MAX_BITMAP_PIXELS.toDouble() / (originalWidth.toDouble() * originalHeight.toDouble())
                        ).toFloat()
                        if (scale > areaScale) scale = areaScale
                        if (scale <= 0f || scale.isNaN()) scale = 0.1f

                        val width = (originalWidth * scale).toInt().coerceAtLeast(1)
                        val height = (originalHeight * scale).toInt().coerceAtLeast(1)

                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        try {
                            // Pre-fill with clean white background
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        } catch (t: Throwable) {
                            if (!bitmap.isRecycled) bitmap.recycle()
                            throw t
                        }

                        if (cacheKey != null) bitmapCache.put(cacheKey, bitmap)
                        bitmap
                    }
                } catch (e: OutOfMemoryError) {
                    lastError = e
                    Log.w(TAG, "OOM rendering page $pageIndex at width $attemptWidth (attempt $attempt); trimming cache and retrying smaller", e)
                    bitmapCache.trimToSize(defaultCacheCapacityKb / 4)
                    System.gc()
                    attemptWidth = (attemptWidth * 0.6f).toInt().coerceAtLeast(240)
                } catch (e: Exception) {
                    Log.e(TAG, "Error rendering page $pageIndex: ${e.message}", e)
                    return@synchronized null
                } catch (e: Error) {
                    Log.e(TAG, "Fatal error rendering page $pageIndex: ${e.message}", e)
                    return@synchronized null
                }
            }
            Log.e(TAG, "Giving up rendering page $pageIndex after retries: ${lastError?.message}")
            null
        }
    }

    suspend fun generateAndSaveThumbnail(): String? = withContext(Dispatchers.IO) {
        if (pageCount <= 0) return@withContext null

        val thumbDir = File(context.filesDir, "thumbnails")
        if (!thumbDir.exists()) thumbDir.mkdirs()

        val thumbFile = File(thumbDir, "thumb_${md5(localPdfFile.absolutePath)}.png")
        if (thumbFile.exists() && thumbFile.length() > 0) {
            return@withContext thumbFile.absolutePath
        }

        val renderer = pdfRenderer ?: return@withContext null
        synchronized(this@PdfEngine) {
            try {
                renderer.openPage(0).use { page ->
                    val thumbWidth = 240
                    val scale = thumbWidth.toFloat() / page.width.toFloat()
                    val thumbHeight = (page.height * scale).toInt().coerceAtLeast(1)

                    val bitmap = Bitmap.createBitmap(thumbWidth, thumbHeight, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                    FileOutputStream(thumbFile).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
                    }
                    thumbFile.absolutePath
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error generating thumbnail: ${e.message}", e)
                null
            } catch (e: Error) {
                Log.e(TAG, "Fatal error generating thumbnail: ${e.message}", e)
                null
            }
        }
    }

    @Synchronized
    override fun close() {
        // Only drop the shared bitmap cache when no other engine instance is
        // still showing the same file in another task/activity.
        if (unregisterEngine(localPdfFile.absolutePath)) {
            clearCacheForFile(localPdfFile.absolutePath)
        }
        if (file != localPdfFile) file.delete()
        try {
            pdfRenderer?.close()
            pdfRenderer = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing PdfRenderer: ${e.message}")
        }
        try {
            fileDescriptor?.close()
            fileDescriptor = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing ParcelFileDescriptor: ${e.message}")
        }
    }
}
