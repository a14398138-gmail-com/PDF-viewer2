package com.example.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.PdfItem
import com.example.data.PdfRepository
import com.example.pdf.PageText
import com.example.pdf.PdfBlockExtractor
import com.example.pdf.PdfEngine
import com.example.pdf.PdfTextCache
import com.example.pdf.PdfTextExtractor
import com.example.pdf.SearchMatch
import com.example.pdf.TranslatedBlock
import com.example.translate.TranslateController
import com.example.ui.theme.ThemeMode
import com.example.util.LastShareAppManager
import com.example.util.LastSharedApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Extraction progress for the search UI: pages fully analysed out of [total]. */
data class ExtractionProgress(val done: Int, val total: Int)

class PdfViewerViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: PdfRepository
    private var currentEngine: PdfEngine? = null

    private val prefs = application.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    private val _themeMode = MutableStateFlow(loadSavedThemeMode())
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        prefs.edit().putString("theme_mode", mode.name).apply()
    }

    private fun loadSavedThemeMode(): ThemeMode {
        val name = prefs.getString("theme_mode", ThemeMode.SYSTEM.name)
        return try {
            ThemeMode.valueOf(name ?: ThemeMode.SYSTEM.name)
        } catch (_: Exception) {
            ThemeMode.SYSTEM
        }
    }

    val historyList: StateFlow<List<PdfItem>>

    private val _activePdfItem = MutableStateFlow<PdfItem?>(null)
    val activePdfItem: StateFlow<PdfItem?> = _activePdfItem.asStateFlow()

    private val _activePagesText = MutableStateFlow<List<PageText>>(emptyList())
    val activePagesText: StateFlow<List<PageText>> = _activePagesText.asStateFlow()

    private val _currentPage = MutableStateFlow(0)
    val currentPage: StateFlow<Int> = _currentPage.asStateFlow()

    private val _isControlsVisible = MutableStateFlow(true)
    val isControlsVisible: StateFlow<Boolean> = _isControlsVisible.asStateFlow()

    // Search State
    private val _isSearchActive = MutableStateFlow(false)
    val isSearchActive: StateFlow<Boolean> = _isSearchActive.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<SearchMatch>>(emptyList())
    val searchResults: StateFlow<List<SearchMatch>> = _searchResults.asStateFlow()

    private val _currentMatchIndex = MutableStateFlow(0)
    val currentMatchIndex: StateFlow<Int> = _currentMatchIndex.asStateFlow()

    // Text Selection State
    private val _selectedText = MutableStateFlow<String?>(null)
    val selectedText: StateFlow<String?> = _selectedText.asStateFlow()

    // Text extraction progress (for the "解析中 n/m" search indicator)
    private val _extractionProgress = MutableStateFlow<ExtractionProgress?>(null)
    val extractionProgress: StateFlow<ExtractionProgress?> = _extractionProgress.asStateFlow()

    // Dialogs
    private val _isNoteDialogOpen = MutableStateFlow(false)
    val isNoteDialogOpen: StateFlow<Boolean> = _isNoteDialogOpen.asStateFlow()

    private val _isRenameDialogOpen = MutableStateFlow(false)
    val isRenameDialogOpen: StateFlow<Boolean> = _isRenameDialogOpen.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isPendingIntentOpen = MutableStateFlow(false)
    val isPendingIntentOpen: StateFlow<Boolean> = _isPendingIntentOpen.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _isOcrPreparing = MutableStateFlow(false)
    val isOcrPreparing: StateFlow<Boolean> = _isOcrPreparing.asStateFlow()

    // Quick-share apps loaded asynchronously on IO thread to prevent UI thread jank
    private val _recentSharedApps = MutableStateFlow<List<LastSharedApp>>(emptyList())
    val recentSharedApps: StateFlow<List<LastSharedApp>> = _recentSharedApps.asStateFlow()

    fun prepareIncomingIntent() {
        _isPendingIntentOpen.value = true
        _isLoading.value = true
    }

    init {
        PdfEngine.initMemoryPressureCallbacks(application)
        PdfTextExtractor.init(application)
        PdfTextExtractor.onOcrModelStatusListener = { isPreparing, _ ->
            // Only track the flag. The old code also copied the "preparing"
            // message into _errorMessage, which surfaced it as a toast even
            // though nothing had gone wrong.
            _isOcrPreparing.value = isPreparing
        }
        val db = AppDatabase.getDatabase(application)
        repository = PdfRepository(application, db.pdfHistoryDao())
        historyList = repository.allHistory.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
        loadRecentSharedApps()
    }

    fun loadRecentSharedApps() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apps = LastShareAppManager.getRecentSharedApps(getApplication())
                _recentSharedApps.value = apps
            } catch (e: Exception) {
                // Ignore failure
            }
        }
    }

    private var pendingPdfUri: Uri? = null
    private val _passwordRequired = MutableStateFlow(false)
    val passwordRequired = _passwordRequired.asStateFlow()
    private val _passwordError = MutableStateFlow<String?>(null)
    val passwordError = _passwordError.asStateFlow()
    private var openJob: Job? = null

    fun cancelPassword() {
        pendingPdfUri = null
        _passwordRequired.value = false
        _passwordError.value = null
    }

    fun submitPassword(password: String) {
        pendingPdfUri?.let { openPdf(it, password) }
    }

    // Latest-wins opening: a new open request cancels the previous one instead
    // of being silently ignored (the old early-return made the app look broken
    // when a second file was opened while the first was still loading).
    private var openGeneration = 0

    fun openPdf(uri: Uri, password: String? = null) {
        if (password == null) cancelPassword()
        val generation = ++openGeneration
        openJob?.cancel()
        openJob = viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            var newEngine: PdfEngine? = null
            try {
                val (item, extractedPages) = repository.openAndRecordPdf(uri, password = password ?: "")
                val source = File(requireNotNull(item.filePath))
                val engine = withContext(Dispatchers.IO) {
                    PdfEngine(getApplication(), source, password ?: "")
                }
                newEngine = engine
                extractionJob?.cancel()
                extractionJob?.join()
                if (generation != openGeneration) {
                    engine.close()
                    newEngine = null
                    return@launch
                }
                currentEngine?.close()
                currentEngine = engine
                newEngine = null
                currentCacheKey = withContext(Dispatchers.IO) {
                    try { PdfTextCache.keyFor(source) } catch (_: Exception) { null }
                }
                resetExtractionState()
                resetTranslationState()
                val targetPage = item.lastOpenedPage.coerceIn(0, (item.pageCount - 1).coerceAtLeast(0))
                engine.renderPageBitmap(targetPage)
                _activePdfItem.value = item
                _activePagesText.value = extractedPages
                _currentPage.value = targetPage
                _isControlsVisible.value = true
                closeSearch()
                _selectedText.value = null
                cancelPassword()
                scheduleExtraction(targetPage)
            } catch (e: com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException) {
                if (generation == openGeneration) {
                    pendingPdfUri = uri
                    _passwordRequired.value = true
                    _passwordError.value = if (password == null) null else "パスワードが違います。もう一度入力してください。"
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == openGeneration) {
                    cancelPassword()
                    _errorMessage.value = "PDFを開けませんでした: ${e.localizedMessage}"
                }
            } finally {
                newEngine?.close()
                if (generation == openGeneration) {
                    _isPendingIntentOpen.value = false
                    _isLoading.value = false
                }
            }
        }
    }

    fun openPdfItem(historyItem: PdfItem) {
        openPdf(Uri.parse(historyItem.uriString))
    }

    fun saveWithoutPassword(destination: Uri) {
        val engine = currentEngine ?: return
        val item = _activePdfItem.value ?: return
        if (destination.toString() == item.uriString) {
            _errorMessage.value = "元のPDFとは別のファイル名で保存してください。"
            return
        }
        viewModelScope.launch {
            _isLoading.value = true
            try {
                withContext(Dispatchers.IO) {
                    val context = getApplication<Application>()
                    val temporary = File.createTempFile("export_", ".pdf", context.cacheDir)
                    try {
                        com.example.pdf.PdfSecurity.saveWithoutPassword(context, engine.file, temporary)
                        val output = context.contentResolver.openOutputStream(destination, "wt")
                            ?: throw java.io.IOException("保存先を開けません")
                        output.use { out -> temporary.inputStream().use { it.copyTo(out) } }
                    } finally {
                        temporary.delete()
                    }
                }
                _errorMessage.value = "パスワードなしのPDFを保存しました"
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _errorMessage.value = "保存できませんでした: ${e.localizedMessage}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun openSamplePdf() {
        // Supersede any in-flight openPdf, and build the engine on IO: the old
        // code constructed PdfEngine (PDFBox work in its initializer) directly
        // on the main thread, unlike every other open path.
        openGeneration++
        openJob?.cancel()
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            try {
                extractionJob?.cancel()
                extractionJob?.join()
                currentEngine?.close()
                currentEngine = null

                val (item, extractedPages) = repository.openSamplePdf()
                val file = item.filePath?.let { File(it) }
                if (file != null && file.exists()) {
                    val engine = withContext(Dispatchers.IO) {
                        PdfEngine(getApplication(), file)
                    }
                    currentEngine = engine
                    currentCacheKey = withContext(Dispatchers.IO) {
                        try { PdfTextCache.keyFor(file) } catch (_: Exception) { null }
                    }
                    resetExtractionState()
                    resetTranslationState()

                    // Pre-render page 0 immediately
                    engine.renderPageBitmap(0)

                    _activePdfItem.value = item
                    _activePagesText.value = extractedPages
                    _currentPage.value = 0
                    _isControlsVisible.value = true
                    closeSearch()
                    _selectedText.value = null

                    scheduleExtraction(0)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _errorMessage.value = "サンプルPDFを開けませんでした: ${e.localizedMessage}"
            } finally {
                _isPendingIntentOpen.value = false
                _isLoading.value = false
            }
        }
    }

    // ---- Demand-driven text extraction -------------------------------------
    // Phase 1: the visible page ±3, with OCR fallback (what the user is reading).
    // Phase 2: every remaining page, native text only (cheap; makes search
    //          complete for text PDFs without rendering/OCR-ing hundreds of pages).
    // Phase 3: only when a search is running, OCR the still-empty pages so
    //          scanned documents become searchable on demand.
    // Results are cached on disk (PdfTextCache, keyed by file content hash) and
    // reused on the next open.
    private var extractionJob: Job? = null
    private var currentCacheKey: String? = null
    private val nativeCheckedPages = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    private val ocrCheckedPages = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    @Volatile private var searchOcrActive = false

    private fun resetExtractionState() {
        nativeCheckedPages.clear()
        ocrCheckedPages.clear()
        searchOcrActive = false
        _extractionProgress.value = null
    }

    private fun scheduleExtraction(focusPage: Int, ocrAllPending: Boolean = false) {
        val engine = currentEngine ?: return
        val item = _activePdfItem.value ?: return
        val pageCount = item.pageCount
        if (pageCount <= 0) return
        if (ocrAllPending) searchOcrActive = true
        val cacheKey = currentCacheKey
        val renderFile = engine.file

        extractionJob?.cancel()
        extractionJob = viewModelScope.launch(Dispatchers.Default) {
            // Give UI immediate rendering priority for the first frame
            kotlinx.coroutines.delay(80)

            val session = if (renderFile.exists()) PdfTextExtractor.openSession(renderFile) else null
            val list = _activePagesText.value.toMutableList()
            while (list.size < pageCount) {
                list.add(PageText(pageIndex = list.size, fullText = ""))
            }

            fun publishProgress() {
                val settled = (0 until pageCount).count { p ->
                    list.getOrNull(p)?.words?.isNotEmpty() == true ||
                        p in ocrCheckedPages ||
                        (p in nativeCheckedPages && !searchOcrActive)
                }
                _extractionProgress.value =
                    if (settled >= pageCount) null else ExtractionProgress(settled, pageCount)
            }

            suspend fun process(pageIdx: Int, allowOcr: Boolean) {
                if (list[pageIdx].words.isNotEmpty() && pageIdx in nativeCheckedPages) return
                // 0. Disk cache first.
                if (cacheKey != null && pageIdx !in nativeCheckedPages) {
                    val cached = withContext(Dispatchers.IO) {
                        PdfTextCache.loadPage(getApplication(), cacheKey, pageIdx)
                    }
                    if (cached != null && cached.words.isNotEmpty()) {
                        nativeCheckedPages.add(pageIdx)
                        ocrCheckedPages.add(pageIdx)
                        list[pageIdx] = cached
                        _activePagesText.value = list.toList()
                        publishProgress()
                        refreshSearchResults()
                        return
                    }
                }
                // 1. Native text layer.
                var pageText: PageText? = null
                if (pageIdx !in nativeCheckedPages) {
                    pageText = session?.extractNativeText(pageIdx)
                    nativeCheckedPages.add(pageIdx)
                }
                // 2. OCR fallback (only where allowed).
                if ((pageText == null || pageText.words.isEmpty()) && allowOcr && pageIdx !in ocrCheckedPages) {
                    val bitmap = engine.renderPageBitmap(pageIdx, targetWidth = 1080)
                    if (bitmap != null) {
                        pageText = PdfTextExtractor.extractPageFromBitmap(bitmap, pageIdx)
                    }
                    ocrCheckedPages.add(pageIdx)
                }
                if (pageText != null) {
                    list[pageIdx] = pageText
                    if (pageText.words.isNotEmpty() && cacheKey != null) {
                        withContext(Dispatchers.IO) {
                            PdfTextCache.savePage(getApplication(), cacheKey, pageText)
                        }
                    }
                }
                _activePagesText.value = list.toList()
                publishProgress()
                refreshSearchResults()
            }

            try {
                session.use {
                    // Phase 1: focus window with OCR.
                    for (delta in 0..3) {
                        for (p in listOf(focusPage + delta, focusPage - delta)) {
                            if (p in 0 until pageCount) process(p, allowOcr = true)
                        }
                    }
                    // Phase 2: remaining pages, native text only.
                    for (p in 0 until pageCount) {
                        if (p !in nativeCheckedPages) process(p, allowOcr = false)
                    }
                    // Phase 3: search-driven OCR of still-empty pages.
                    if (searchOcrActive) {
                        for (p in 0 until pageCount) {
                            if (p !in ocrCheckedPages && list[p].words.isEmpty()) {
                                process(p, allowOcr = true)
                            }
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("PdfViewerViewModel", "Text extraction interrupted: ${e.message}")
            } finally {
                publishProgress()
            }
        }
    }

    // ---- PLaMo paper translation (layout-preserving overlay) ---------------
    // Only pages with a live native text layer are supported; scanned/OCR
    // pages are reported as unsupported by the UI. Translation runs per layout
    // block, starting from the visible page outwards, and results are cached
    // on disk per block (PdfTextCache) so reopening never re-translates.
    private val translateController by lazy { TranslateController(getApplication()) }

    private val _isTranslateMode = MutableStateFlow(false)
    val isTranslateMode: StateFlow<Boolean> = _isTranslateMode.asStateFlow()

    private val _pageTranslations = MutableStateFlow<Map<Int, List<TranslatedBlock>>>(emptyMap())
    val pageTranslations: StateFlow<Map<Int, List<TranslatedBlock>>> = _pageTranslations.asStateFlow()

    private val _translatingPages = MutableStateFlow<Set<Int>>(emptySet())
    val translatingPages: StateFlow<Set<Int>> = _translatingPages.asStateFlow()

    private val _showTranslateModelSetup = MutableStateFlow(false)
    val showTranslateModelSetup: StateFlow<Boolean> = _showTranslateModelSetup.asStateFlow()

    private val _translateDownloadProgress = MutableStateFlow<Float?>(null)
    val translateDownloadProgress: StateFlow<Float?> = _translateDownloadProgress.asStateFlow()

    private val _isTranslateModelLoading = MutableStateFlow(false)
    val isTranslateModelLoading: StateFlow<Boolean> = _isTranslateModelLoading.asStateFlow()

    private val claimedTranslationPages = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

    private fun resetTranslationState() {
        _isTranslateMode.value = false
        _pageTranslations.value = emptyMap()
        _translatingPages.value = emptySet()
        claimedTranslationPages.clear()
    }

    fun setTranslateMode(on: Boolean) {
        if (!on) {
            _isTranslateMode.value = false
            return
        }
        if (!translateController.modelExists()) {
            // Guide the user to the model download / file import first.
            _showTranslateModelSetup.value = true
            return
        }
        _isTranslateMode.value = true
        ensureModelLoadedAndSchedule(_currentPage.value)
    }

    fun dismissTranslateModelSetup() {
        _showTranslateModelSetup.value = false
    }

    fun downloadTranslateModel() {
        viewModelScope.launch {
            _translateDownloadProgress.value = 0f
            val ok = translateController.downloadModel { frac ->
                _translateDownloadProgress.value = frac
            }
            _translateDownloadProgress.value = null
            if (ok) {
                _showTranslateModelSetup.value = false
                setTranslateMode(true)
            } else {
                _errorMessage.value = "翻訳モデルのダウンロードに失敗しました"
            }
        }
    }

    fun importTranslateModel(uri: Uri) {
        viewModelScope.launch {
            val ok = translateController.importModel(uri)
            if (ok) {
                _showTranslateModelSetup.value = false
                setTranslateMode(true)
            } else {
                _errorMessage.value = "選択したファイルは翻訳モデルとして使えませんでした"
            }
        }
    }

    private fun ensureModelLoadedAndSchedule(focusPage: Int) {
        viewModelScope.launch {
            if (translateController.handle == 0L) {
                _isTranslateModelLoading.value = true
                val result = translateController.loadModel()
                _isTranslateModelLoading.value = false
                if (result is TranslateController.LoadResult.Error) {
                    _errorMessage.value = result.message
                    _isTranslateMode.value = false
                    return@launch
                }
            }
            schedulePageTranslations(focusPage)
        }
    }

    /** Queues translation for the focus page and its neighbours (never all pages). */
    fun schedulePageTranslations(focusPage: Int) {
        if (!_isTranslateMode.value) return
        val pageCount = currentItemPageCount()
        if (pageCount <= 0) return
        val order = listOf(focusPage, focusPage + 1, focusPage - 1, focusPage + 2, focusPage - 2)
            .filter { it in 0 until pageCount }
            .distinct()
        for (page in order) {
            if (page in claimedTranslationPages) continue
            viewModelScope.launch(Dispatchers.Default) {
                translatePageInternal(page)
            }
        }
    }

    private fun isTranslatableText(text: String): Boolean =
        text.any { it.isLetter() } && text.trim().length >= 3 && !PdfBlockExtractor.isBylineBlock(text)

    private suspend fun translatePageInternal(pageIdx: Int) {
        if (!claimedTranslationPages.add(pageIdx)) return
        _translatingPages.value = _translatingPages.value + pageIdx
        var completed = false
        try {
            val pageText = _activePagesText.value.getOrNull(pageIdx) ?: return
            // Native text layer only: OCR pages are out of scope by design.
            if (!pageText.isNativeTextLayer) return
            val blocks = PdfBlockExtractor.blocksFromPageText(pageText)
                .filter { isTranslatableText(it.text) }
            if (blocks.isEmpty()) return

            val cacheKey = currentCacheKey
            val cached = if (cacheKey != null) {
                withContext(Dispatchers.IO) {
                    PdfTextCache.loadTranslations(getApplication(), cacheKey, pageIdx)
                }
            } else {
                emptyMap()
            }

            val results = mutableListOf<TranslatedBlock>()
            var failed = false
            for (block in blocks) {
                if (!_isTranslateMode.value) break
                val hit = cached[block.blockIndex]
                if (hit != null && hit.first == block.text) {
                    results.add(TranslatedBlock(block, hit.second))
                    continue
                }
                val src = TranslateController.detectLang(block.text)
                val tgt = if (src == "ja") "en" else "ja"
                val separator = if (tgt == "ja") "" else " "
                val translated = StringBuilder()
                var blockFailed = false
                for (chunk in PdfBlockExtractor.splitIntoChunks(block.text)) {
                    when (val r = translateController.translateStream(chunk, src, tgt) {}) {
                        is TranslateController.TranslateResult.Ok -> {
                            if (translated.isNotEmpty()) translated.append(separator)
                            translated.append(r.text)
                        }
                        is TranslateController.TranslateResult.Error -> {
                            android.util.Log.w("PdfViewerViewModel", "Block translation failed (page $pageIdx): ${r.message}")
                            blockFailed = true
                            failed = true
                            break
                        }
                    }
                }
                if (blockFailed) continue
                val translatedText = translated.toString().trim()
                if (translatedText.isNotEmpty()) {
                    results.add(TranslatedBlock(block, translatedText))
                    if (cacheKey != null) {
                        withContext(Dispatchers.IO) {
                            PdfTextCache.saveTranslation(
                                getApplication(), cacheKey, pageIdx,
                                block.blockIndex, block.text, translatedText
                            )
                        }
                    }
                }
                // Publish progressively so the page fills in block by block.
                _pageTranslations.value = _pageTranslations.value + (pageIdx to results.toList())
            }
            completed = !failed && results.isNotEmpty()
            if (results.isNotEmpty()) {
                _pageTranslations.value = _pageTranslations.value + (pageIdx to results.toList())
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("PdfViewerViewModel", "Page translation failed (page $pageIdx): ${e.message}")
        } finally {
            _translatingPages.value = _translatingPages.value - pageIdx
            if (!completed) {
                // Allow a later visit to retry unfinished pages.
                claimedTranslationPages.remove(pageIdx)
            }
        }
    }

    /** Re-runs the current search over newly extracted pages (without jumping). */
    private fun refreshSearchResults() {
        val query = _searchQuery.value
        if (query.isBlank() || !_isSearchActive.value) return
        val results = PdfTextExtractor.search(_activePagesText.value, query)
        _searchResults.value = results
        if (_currentMatchIndex.value >= results.size) {
            _currentMatchIndex.value = (results.size - 1).coerceAtLeast(0)
        }
    }

    fun updatePageText(pageIndex: Int, pageText: PageText) {
        val current = _activePagesText.value.toMutableList()
        if (pageIndex < current.size) {
            current[pageIndex] = pageText
        } else {
            while (current.size < pageIndex) {
                current.add(PageText(pageIndex = current.size, fullText = ""))
            }
            current.add(pageText)
        }
        _activePagesText.value = current
    }

    fun getPdfEngine(): PdfEngine? = currentEngine

    fun closePdf() {
        // Closing must work even while a document is still loading: cancel the
        // in-flight open instead of ignoring the back action entirely.
        openGeneration++
        openJob?.cancel()
        openJob = null
        _isLoading.value = false
        _isPendingIntentOpen.value = false
        extractionJob?.cancel()
        resetExtractionState()
        resetTranslationState()
        currentCacheKey = null
        cancelPassword()
        val currentItem = _activePdfItem.value
        if (currentItem != null) {
            viewModelScope.launch {
                repository.updateLastPage(currentItem.uriString, _currentPage.value)
            }
        }
        currentEngine?.close()
        currentEngine = null
        _activePdfItem.value = null
        _activePagesText.value = emptyList()
        closeSearch()
        _selectedText.value = null
        _isControlsVisible.value = true
    }

    fun toggleControls() {
        _isControlsVisible.value = !_isControlsVisible.value
    }

    fun setControlsVisible(visible: Boolean) {
        _isControlsVisible.value = visible
    }

    fun onPageChanged(page: Int) {
        if (_currentPage.value != page) {
            _currentPage.value = page
            val currentItem = _activePdfItem.value
            if (currentItem != null) {
                viewModelScope.launch {
                    repository.updateLastPage(currentItem.uriString, page)
                }
            }
        }
        // Translation follows the reader: translate the newly visible page
        // and its neighbours (cached pages return instantly).
        if (_isTranslateMode.value) {
            schedulePageTranslations(page)
        }
        // Demand-driven OCR: when the reader lands on pages that have no text
        // yet and no extraction is running, analyse the new neighbourhood.
        if (extractionJob?.isActive != true && currentEngine != null) {
            val needsWork = (page - 3..page + 3).any { p ->
                p in 0 until (currentItemPageCount()) &&
                    _activePagesText.value.getOrNull(p)?.words?.isEmpty() != false &&
                    p !in ocrCheckedPages
            }
            if (needsWork) scheduleExtraction(page)
        }
    }

    private fun currentItemPageCount(): Int = _activePdfItem.value?.pageCount ?: 0

    fun jumpToPage(page: Int) {
        val maxPage = (_activePdfItem.value?.pageCount ?: 1) - 1
        _currentPage.value = page.coerceIn(0, maxPage.coerceAtLeast(0))
    }

    // Note operations
    fun openNoteDialog() {
        _isNoteDialogOpen.value = true
    }

    fun closeNoteDialog() {
        _isNoteDialogOpen.value = false
    }

    fun saveNote(note: String) {
        val item = _activePdfItem.value ?: return
        viewModelScope.launch {
            repository.updateNote(item.uriString, note)
            _activePdfItem.value = item.copy(noteContent = note)
            closeNoteDialog()
        }
    }

    // Rename operations
    fun openRenameDialog() {
        _isRenameDialogOpen.value = true
    }

    fun closeRenameDialog() {
        _isRenameDialogOpen.value = false
    }

    fun renameFile(newName: String) {
        val item = _activePdfItem.value ?: return
        if (newName.isBlank()) return
        viewModelScope.launch {
            repository.updateFileName(item.uriString, newName)
            val trimmed = newName.trim()
            val finalName = if (trimmed.endsWith(".pdf", ignoreCase = true)) trimmed else "$trimmed.pdf"
            _activePdfItem.value = item.copy(fileName = finalName)
            closeRenameDialog()
        }
    }

    // Search operations
    fun openSearch() {
        _isSearchActive.value = true
        _isControlsVisible.value = true
    }

    fun closeSearch() {
        _isSearchActive.value = false
        _searchQuery.value = ""
        _searchResults.value = emptyList()
        _currentMatchIndex.value = 0
    }

    private var searchJob: Job? = null
    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
        searchJob?.cancel()
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            _currentMatchIndex.value = 0
            return
        }
        searchJob = viewModelScope.launch {
            kotlinx.coroutines.delay(250)
            val results = PdfTextExtractor.search(_activePagesText.value, query)
            _searchResults.value = results
            _currentMatchIndex.value = 0
            if (results.isNotEmpty()) {
                jumpToPage(results[0].pageIndex)
            }
            // Make not-yet-analysed pages searchable: native sweep + OCR of the
            // still-empty pages. Results refresh as pages complete, and the
            // search UI shows "解析中 n/m" meanwhile instead of a misleading 0件.
            val pageCount = currentItemPageCount()
            val hasPending = (0 until pageCount).any { p ->
                p !in ocrCheckedPages && _activePagesText.value.getOrNull(p)?.words?.isEmpty() != false
            }
            if (hasPending) {
                scheduleExtraction(_currentPage.value, ocrAllPending = true)
            }
        }
    }

    fun nextSearchMatch() {
        val results = _searchResults.value
        if (results.isEmpty()) return
        val nextIdx = (_currentMatchIndex.value + 1) % results.size
        _currentMatchIndex.value = nextIdx
        jumpToPage(results[nextIdx].pageIndex)
    }

    fun prevSearchMatch() {
        val results = _searchResults.value
        if (results.isEmpty()) return
        val prevIdx = if (_currentMatchIndex.value - 1 < 0) results.size - 1 else _currentMatchIndex.value - 1
        _currentMatchIndex.value = prevIdx
        jumpToPage(results[prevIdx].pageIndex)
    }

    // Text Selection
    fun onTextSelected(text: String?) {
        _selectedText.value = if (text.isNullOrBlank()) null else text.trim()
    }

    // History operations
    fun deleteHistoryItem(item: PdfItem) {
        viewModelScope.launch {
            repository.deleteHistoryItem(item)
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            repository.clearAllHistory()
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    override fun onCleared() {
        super.onCleared()
        currentEngine?.close()
        currentEngine = null
    }
}
