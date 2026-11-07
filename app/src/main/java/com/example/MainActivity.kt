package com.example

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import com.example.renamer.RenamerScreen
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.example.ui.HomeScreen
import com.example.ui.PdfViewerScreen
import com.example.ui.PdfViewerViewModel
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.ThemeMode

class MainActivity : ComponentActivity() {

    private val viewModel: PdfViewerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Sweep decrypted plaintext PDFs left in the cache by a killed/crashed
        // previous process (PdfEngine.close() normally deletes its own file).
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            com.example.pdf.PdfSecurity.cleanupStaleTempFiles(this@MainActivity)
        }

        if (isPdfIntent(intent)) {
            viewModel.prepareIncomingIntent()
        }

        handleIntent(intent)

        setContent {
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            val isDark = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            MyApplicationTheme(darkTheme = isDark) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    PdfApp(
                        viewModel = viewModel,
                        themeMode = themeMode,
                        onThemeModeChanged = { viewModel.setThemeMode(it) }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (isPdfIntent(intent)) {
            viewModel.prepareIncomingIntent()
        }
        handleIntent(intent)
    }

    private fun isPdfIntent(intent: Intent?): Boolean {
        if (intent == null) return false
        val action = intent.action
        val type = intent.type
        return action == Intent.ACTION_VIEW && intent.data != null ||
                (action == Intent.ACTION_SEND && type != null && (type == "application/pdf" || type.startsWith("application/")))
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return

        val action = intent.action
        val type = intent.type

        when (action) {
            Intent.ACTION_VIEW -> {
                intent.data?.let { uri ->
                    viewModel.openPdf(uri)
                }
            }
            Intent.ACTION_SEND -> {
                if (type != null && (type == "application/pdf" || type.startsWith("application/"))) {
                    val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(Intent.EXTRA_STREAM)
                    } ?: intent.clipData?.getItemAt(0)?.uri ?: intent.data

                    uri?.let { viewModel.openPdf(it) }
                }
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        com.example.pdf.PdfEngine.handleTrimMemory(level)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        com.example.pdf.PdfEngine.handleLowMemory()
    }
}

@Composable
fun PdfApp(
    viewModel: PdfViewerViewModel,
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    onThemeModeChanged: (ThemeMode) -> Unit = {}
) {
    var showRenamer by rememberSaveable { mutableStateOf(false) }
    val historyList by viewModel.historyList.collectAsStateWithLifecycle()
    val activePdfItem by viewModel.activePdfItem.collectAsStateWithLifecycle()
    val isPendingIntentOpen by viewModel.isPendingIntentOpen.collectAsStateWithLifecycle()
    val activePagesText by viewModel.activePagesText.collectAsStateWithLifecycle()
    val currentPage by viewModel.currentPage.collectAsStateWithLifecycle()
    val isControlsVisible by viewModel.isControlsVisible.collectAsStateWithLifecycle()
    val isSearchActive by viewModel.isSearchActive.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()
    val currentMatchIndex by viewModel.currentMatchIndex.collectAsStateWithLifecycle()
    val isNoteDialogOpen by viewModel.isNoteDialogOpen.collectAsStateWithLifecycle()
    val isRenameDialogOpen by viewModel.isRenameDialogOpen.collectAsStateWithLifecycle()
    val extractionProgress by viewModel.extractionProgress.collectAsStateWithLifecycle()
    val isTranslateMode by viewModel.isTranslateMode.collectAsStateWithLifecycle()
    val pageTranslations by viewModel.pageTranslations.collectAsStateWithLifecycle()
    val translatingPages by viewModel.translatingPages.collectAsStateWithLifecycle()
    val pageTranslationProgress by viewModel.pageTranslationProgress.collectAsStateWithLifecycle()
    val showTranslateModelSetup by viewModel.showTranslateModelSetup.collectAsStateWithLifecycle()
    val translateDownloadProgress by viewModel.translateDownloadProgress.collectAsStateWithLifecycle()
    val isTranslateModelLoading by viewModel.isTranslateModelLoading.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val recentSharedApps by viewModel.recentSharedApps.collectAsStateWithLifecycle()

    val passwordRequired by viewModel.passwordRequired.collectAsStateWithLifecycle()
    val passwordError by viewModel.passwordError.collectAsStateWithLifecycle()
    val savePdfLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri -> uri?.let { viewModel.saveWithoutPassword(it) } }

    val translateModelPickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.importTranslateModel(it) } }

    if (showTranslateModelSetup) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { viewModel.dismissTranslateModelSetup() },
            title = { androidx.compose.material3.Text("和訳モデルの準備") },
            text = {
                androidx.compose.foundation.layout.Column {
                    androidx.compose.material3.Text(
                        "論文和訳には翻訳モデル（PLaMo 3 Translate、約550MB）が必要です。" +
                            "ダウンロードするか、既に端末にあるモデルファイルを選択してください。"
                    )
                    if (translateDownloadProgress != null) {
                        androidx.compose.foundation.layout.Spacer(modifier = Modifier.size(12.dp))
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { translateDownloadProgress ?: 0f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        androidx.compose.material3.Text(
                            "ダウンロード中… ${((translateDownloadProgress ?: 0f) * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    enabled = translateDownloadProgress == null,
                    onClick = { viewModel.downloadTranslateModel() }
                ) { androidx.compose.material3.Text("ダウンロード") }
            },
            dismissButton = {
                androidx.compose.foundation.layout.Row {
                    androidx.compose.material3.TextButton(
                        enabled = translateDownloadProgress == null,
                        onClick = { translateModelPickerLauncher.launch(arrayOf("*/*")) }
                    ) { androidx.compose.material3.Text("ファイルを選択") }
                    androidx.compose.material3.TextButton(
                        onClick = { viewModel.dismissTranslateModelSetup() }
                    ) { androidx.compose.material3.Text("キャンセル") }
                }
            }
        )
    }

    if (isTranslateModelLoading) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {},
            title = { androidx.compose.material3.Text("翻訳モデルを読み込み中") },
            text = {
                androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.size(12.dp))
                    androidx.compose.material3.Text("初回はモデルの読み込みに少し時間がかかります。")
                }
            },
            confirmButton = {}
        )
    }

    if (passwordRequired) {
        var password by androidx.compose.runtime.remember { mutableStateOf("") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { if (!isLoading) viewModel.cancelPassword() },
            title = { androidx.compose.material3.Text("PDFのパスワード") },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { androidx.compose.material3.Text("パスワード") },
                    singleLine = true,
                    enabled = !isLoading,
                    isError = passwordError != null,
                    supportingText = { androidx.compose.material3.Text(passwordError ?: "開くためのパスワードを入力してください") },
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Password
                    )
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    enabled = !isLoading,
                    onClick = { viewModel.submitPassword(password); password = "" }
                ) { androidx.compose.material3.Text(if (isLoading) "確認中…" else "開く") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    enabled = !isLoading,
                    onClick = { viewModel.cancelPassword() }
                ) { androidx.compose.material3.Text("キャンセル") }
            }
        )
    }

    // Back handler: if PDF is open, close it and return to history
    BackHandler(enabled = activePdfItem != null) {
        if (isSearchActive) {
            viewModel.closeSearch()
        } else {
            viewModel.closePdf()
        }
    }

    val context = androidx.compose.ui.platform.LocalContext.current

    // Show error toast if any
    LaunchedEffect(errorMessage) {
        errorMessage?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            viewModel.clearError()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = when {
                activePdfItem != null -> "viewer"
                isPendingIntentOpen -> "intent_loading"
                showRenamer -> "renamer"
                else -> "home"
            },
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "ScreenTransition"
        ) { screenState ->
            when (screenState) {
                "renamer" -> RenamerScreen(
                    onBack = { showRenamer = false },
                    onOpenPdf = { viewModel.openPdf(it) }
                )
                "viewer" -> {
                    val currentItem = activePdfItem
                    if (currentItem != null) {
                        PdfViewerScreen(
                            pdfItem = currentItem,
                            pdfEngine = viewModel.getPdfEngine(),
                            pageTexts = activePagesText,
                            currentPage = currentPage,
                            isControlsVisible = isControlsVisible,
                            isSearchActive = isSearchActive,
                            searchQuery = searchQuery,
                            searchResults = searchResults,
                            currentMatchIndex = currentMatchIndex,
                            isNoteDialogOpen = isNoteDialogOpen,
                            isRenameDialogOpen = isRenameDialogOpen,
                            themeMode = themeMode,
                            onThemeModeChanged = onThemeModeChanged,
                            onBack = { viewModel.closePdf() },
                            onToggleControls = { viewModel.toggleControls() },
                            onSetControlsVisible = { viewModel.setControlsVisible(it) },
                            onPageChanged = { viewModel.onPageChanged(it) },
                            onOpenNoteDialog = { viewModel.openNoteDialog() },
                            onCloseNoteDialog = { viewModel.closeNoteDialog() },
                            onSaveNote = { viewModel.saveNote(it) },
                            onOpenRenameDialog = { viewModel.openRenameDialog() },
                            onCloseRenameDialog = { viewModel.closeRenameDialog() },
                            onRenameFile = { viewModel.renameFile(it) },
                            onOpenSearch = { viewModel.openSearch() },
                            onCloseSearch = { viewModel.closeSearch() },
                            onSearchQueryChanged = { viewModel.onSearchQueryChanged(it) },
                            onNextMatch = { viewModel.nextSearchMatch() },
                            onPrevMatch = { viewModel.prevSearchMatch() },
                            extractionProgress = extractionProgress,
                            isTranslateMode = isTranslateMode,
                            onToggleTranslateMode = { viewModel.setTranslateMode(it) },
                            pageTranslations = pageTranslations,
                            translatingPages = translatingPages,
                            pageTranslationProgress = pageTranslationProgress,
                            onPageTextRecognized = { pageIdx, text -> viewModel.updatePageText(pageIdx, text) },
                            onSaveWithoutPassword = {
                                savePdfLauncher.launch(currentItem.fileName.substringBeforeLast('.', currentItem.fileName) + "_unlocked.pdf")
                            },
                            availableShareApps = recentSharedApps
                        )
                    }
                }
                "intent_loading" -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                }
                else -> {
                    HomeScreen(
                        onOpenRenamer = { showRenamer = true },
                        historyList = historyList,
                        themeMode = themeMode,
                        onThemeModeChanged = onThemeModeChanged,
                        onOpenPdfUri = { uri -> viewModel.openPdf(uri) },
                        onOpenHistoryItem = { item -> viewModel.openPdfItem(item) },
                        onOpenSamplePdf = { viewModel.openSamplePdf() },
                        onDeleteHistoryItem = { item -> viewModel.deleteHistoryItem(item) },
                        onClearAllHistory = { viewModel.clearAllHistory() }
                    )
                }
            }
        }

        if (isLoading && !isPendingIntentOpen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 8.dp,
                    shadowElevation = 8.dp,
                    modifier = Modifier.size(80.dp)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
            }
        }
    }
}
