package com.example.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Translate
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.ColorFilter
import com.example.ui.theme.PdfDarkColorMatrix
import com.example.ui.theme.ThemeMode
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.compose.ui.res.painterResource
import com.example.R
import com.example.data.PdfItem
import com.example.pdf.PageText
import com.example.pdf.PdfEngine
import com.example.pdf.PdfTextExtractor
import com.example.pdf.SearchMatch
import com.example.pdf.TextWord
import com.example.util.LastShareAppManager
import com.example.util.LastSharedApp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewerScreen(
    pdfItem: PdfItem,
    pdfEngine: PdfEngine?,
    pageTexts: List<PageText>,
    currentPage: Int,
    isControlsVisible: Boolean,
    isSearchActive: Boolean,
    searchQuery: String,
    searchResults: List<SearchMatch>,
    currentMatchIndex: Int,
    isNoteDialogOpen: Boolean,
    isRenameDialogOpen: Boolean,
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    onThemeModeChanged: (ThemeMode) -> Unit = {},
    onBack: () -> Unit,
    onToggleControls: () -> Unit,
    onSetControlsVisible: (Boolean) -> Unit,
    onPageChanged: (Int) -> Unit,
    onOpenNoteDialog: () -> Unit,
    onCloseNoteDialog: () -> Unit,
    onSaveNote: (String) -> Unit,
    onOpenRenameDialog: () -> Unit,
    onCloseRenameDialog: () -> Unit,
    onRenameFile: (String) -> Unit,
    onOpenSearch: () -> Unit,
    onCloseSearch: () -> Unit,
    onSearchQueryChanged: (String) -> Unit,
    onNextMatch: () -> Unit,
    onPrevMatch: () -> Unit,
    onPageTextRecognized: (Int, PageText) -> Unit = { _, _ -> },
    extractionProgress: ExtractionProgress? = null,
    isTranslateMode: Boolean = false,
    onToggleTranslateMode: (Boolean) -> Unit = {},
    pageTranslations: Map<Int, List<com.example.pdf.TranslatedBlock>> = emptyMap(),
    translatingPages: Set<Int> = emptySet(),
    availableShareApps: List<LastSharedApp> = emptyList(),
    onSaveWithoutPassword: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = currentPage)

    val isSystemDark = isSystemInDarkTheme()
    val isDark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    var menuExpanded by remember { mutableStateOf(false) }

    // Direct On-Page Word Selection State
    var activeSelectedText by remember { mutableStateOf<String?>(null) }
    var activeSelectedPage by remember { mutableStateOf<Int?>(null) }
    var activeSelectedWordIndices by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var isShareAppsDrawerVisible by remember { mutableStateOf(false) }

    // Reset share apps drawer when selection changes/clears
    LaunchedEffect(activeSelectedText) {
        if (activeSelectedText == null) {
            isShareAppsDrawerVisible = false
        }
    }

    // Zoom & Pan State (Pinch-to-zoom / Scale / Pan)
    var zoomScale by remember { mutableFloatStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }

    // Settled zoom (debounced) drives high-resolution re-rendering of pages,
    // so pinching does not trigger a render per gesture frame.
    var settledZoomScale by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(zoomScale) {
        kotlinx.coroutines.delay(300)
        settledZoomScale = zoomScale
    }

    // Current search hit's highlighted words on its page.
    val currentSearchMatch = searchResults.getOrNull(currentMatchIndex)
    val searchHighlightIndices = remember(currentSearchMatch, searchQuery, pageTexts) {
        val match = currentSearchMatch ?: return@remember emptySet<Int>()
        val page = pageTexts.getOrNull(match.pageIndex) ?: return@remember emptySet<Int>()
        val ordinal = PdfTextExtractor.occurrenceOrdinal(page, match, searchQuery)
        PdfTextExtractor.searchHighlightIndices(page, searchQuery, ordinal)
    }

    // Fast-scroll slider dragging state
    var isSliderDragging by remember { mutableStateOf(false) }
    var sliderValue by remember(currentPage) { mutableFloatStateOf(currentPage.toFloat()) }

    // Track scroll events to hide controls smoothly during reading
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress && !isSliderDragging) {
            onSetControlsVisible(false)
        }
    }

    // Synchronize scroll position with current page indicator.
    // Programmatic scrolls (jump/search/slider) set this flag so the resulting
    // firstVisibleItemIndex changes are not fed back into onPageChanged, which
    // used to make manual and programmatic scrolling fight each other.
    var isProgrammaticScroll by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { index ->
                if (isProgrammaticScroll) return@collect
                if (index < pdfItem.pageCount) {
                    onPageChanged(index)
                    if (!isSliderDragging) {
                        sliderValue = index.toFloat()
                    }
                }
            }
    }

    // Scroll to page when jump / search requested
    LaunchedEffect(currentPage) {
        if (listState.firstVisibleItemIndex != currentPage && !isSliderDragging) {
            isProgrammaticScroll = true
            try {
                listState.scrollToItem(currentPage)
            } finally {
                isProgrammaticScroll = false
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(if (isDark) Color(0xFF141518) else MaterialTheme.colorScheme.surfaceContainerLowest)
            .testTag("pdf_viewer_screen")
    ) {
        // PDF Pages Container with pinch-to-zoom and pan transformations
        var viewportSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { viewportSize = it }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val newScale = (zoomScale * zoom).coerceIn(1f, 4f)
                        zoomScale = newScale
                        if (zoomScale > 1f) {
                            // Pan limits derive from the actual viewport size
                            // (content is scaled around the centre), not from
                            // hardcoded pixel constants.
                            val maxOffsetX = viewportSize.width * (zoomScale - 1f) / 2f
                            val maxOffsetY = viewportSize.height * (zoomScale - 1f) / 2f
                            panOffset = Offset(
                                x = (panOffset.x + pan.x).coerceIn(-maxOffsetX, maxOffsetX),
                                y = (panOffset.y + pan.y).coerceIn(-maxOffsetY, maxOffsetY)
                            )
                        } else {
                            panOffset = Offset.Zero
                        }
                    }
                }
                .graphicsLayer {
                    scaleX = zoomScale
                    scaleY = zoomScale
                    translationX = panOffset.x
                    translationY = panOffset.y
                }
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("pdf_page_list"),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Top spacer for top app bar clearance
                item {
                    Spacer(modifier = Modifier.height(if (isControlsVisible) 72.dp else 0.dp))
                }

                items(pdfItem.pageCount, key = { it }) { pageIndex ->
                    val pageText = pageTexts.getOrNull(pageIndex)
                    PdfPageItem(
                        pageIndex = pageIndex,
                        pdfEngine = pdfEngine,
                        pageText = pageText,
                        isDarkMode = isDark,
                        zoomScale = settledZoomScale,
                        translatedBlocks = if (isTranslateMode) pageTranslations[pageIndex].orEmpty() else emptyList(),
                        showTranslateUnsupportedNotice = isTranslateMode && extractionProgress == null &&
                            pageText != null && !pageText.isNativeTextLayer,
                        isTranslatingPage = pageIndex in translatingPages,
                        searchHighlightIndices = if (currentSearchMatch?.pageIndex == pageIndex) searchHighlightIndices else emptySet(),
                        selectedWordIndices = if (activeSelectedPage == pageIndex) activeSelectedWordIndices else emptySet(),
                        onWordsSelected = { wordIndices, text ->
                            activeSelectedPage = pageIndex
                            activeSelectedWordIndices = wordIndices
                            activeSelectedText = text
                        },
                        onPageTextRecognized = { recognizedText ->
                            onPageTextRecognized(pageIndex, recognizedText)
                        },
                        onTap = {
                            if (activeSelectedText != null) {
                                // Dismiss selection on tap
                                activeSelectedText = null
                                activeSelectedPage = null
                                activeSelectedWordIndices = emptySet()
                            } else {
                                // Toggle immersive mode / controls visibility
                                onToggleControls()
                            }
                        },
                        onDoubleTap = {
                            // Double tap to toggle zoom level
                            if (zoomScale > 1.1f) {
                                zoomScale = 1f
                                panOffset = Offset.Zero
                            } else {
                                zoomScale = 2.2f
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Bottom spacer for bottom bar clearance
                item {
                    Spacer(modifier = Modifier.height(if (isControlsVisible) 100.dp else 24.dp))
                }
            }
        }

        // Top App Bar (Animated visibility on single tap)
        AnimatedVisibility(
            visible = isControlsVisible,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
                shadowElevation = 3.dp
            ) {
                Column(modifier = Modifier.fillMaxWidth().statusBarsPadding()) {
                    TopAppBar(
                        title = {
                            Text(
                                text = pdfItem.fileName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        navigationIcon = {
                            IconButton(
                                onClick = onBack,
                                modifier = Modifier.testTag("viewer_back_button")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "履歴に戻る",
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        },
                        actions = {
                            Box {
                                IconButton(
                                    onClick = { menuExpanded = true },
                                    modifier = Modifier.testTag("viewer_overflow_menu_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.MoreVert,
                                        contentDescription = "その他のオプション",
                                        tint = MaterialTheme.colorScheme.onSurface
                                    )
                                }

                                DropdownMenu(
                                    expanded = menuExpanded,
                                    onDismissRequest = { menuExpanded = false },
                                    shape = RoundedCornerShape(16.dp),
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("パスワードなしで保存") },
                                        onClick = {
                                            menuExpanded = false
                                            onSaveWithoutPassword()
                                        },
                                        modifier = Modifier.testTag("menu_save_unencrypted")
                                    )
                                    // 1. メモを書く
                                    DropdownMenuItem(
                                        text = { Text("メモを書く", fontWeight = FontWeight.Medium) },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.EditNote,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        },
                                        onClick = {
                                            menuExpanded = false
                                            onOpenNoteDialog()
                                        },
                                        modifier = Modifier.testTag("menu_item_write_note")
                                    )

                                    // 2. ファイル名を変更する
                                    DropdownMenuItem(
                                        text = { Text("ファイル名を変更する", fontWeight = FontWeight.Medium) },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.DriveFileRenameOutline,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        },
                                        onClick = {
                                            menuExpanded = false
                                            onOpenRenameDialog()
                                        },
                                        modifier = Modifier.testTag("menu_item_rename")
                                    )

                                    // 3. 和訳 (PLaMo layout-preserving translation overlay)
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = "和訳",
                                                fontWeight = if (isTranslateMode) FontWeight.Bold else FontWeight.Medium,
                                                color = if (isTranslateMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.Translate,
                                                contentDescription = null,
                                                tint = if (isTranslateMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        },
                                        trailingIcon = if (isTranslateMode) {
                                            {
                                                Icon(
                                                    imageVector = Icons.Default.Check,
                                                    contentDescription = "和訳オン",
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        } else null,
                                        onClick = {
                                            menuExpanded = false
                                            onToggleTranslateMode(!isTranslateMode)
                                        },
                                        modifier = Modifier.testTag("menu_item_translate")
                                    )

                                    androidx.compose.material3.HorizontalDivider(
                                        modifier = Modifier.padding(vertical = 4.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant
                                    )

                                    // 4. テーマモード切り替え (システム / ライト / ダーク)
                                    ThemeMode.values().forEach { mode ->
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = mode.title,
                                                    fontWeight = if (mode == themeMode) FontWeight.Bold else FontWeight.Normal,
                                                    color = if (mode == themeMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                                )
                                            },
                                            leadingIcon = {
                                                val icon = when (mode) {
                                                    ThemeMode.SYSTEM -> Icons.Default.BrightnessAuto
                                                    ThemeMode.LIGHT -> Icons.Default.LightMode
                                                    ThemeMode.DARK -> Icons.Default.DarkMode
                                                }
                                                Icon(
                                                    imageVector = icon,
                                                    contentDescription = null,
                                                    tint = if (mode == themeMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            },
                                            trailingIcon = if (mode == themeMode) {
                                                {
                                                    Icon(
                                                        imageVector = Icons.Default.Check,
                                                        contentDescription = "選択中",
                                                        tint = MaterialTheme.colorScheme.primary
                                                    )
                                                }
                                            } else null,
                                            onClick = {
                                                menuExpanded = false
                                                onThemeModeChanged(mode)
                                            },
                                            modifier = Modifier.testTag("viewer_theme_menu_${mode.name.lowercase()}")
                                        )
                                    }
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant)
                    )
                }
            }
        }

        // Floating Direct Selection Action Bar (Copy / Share / Quick Share Apps / Web Search)
        PdfSelectionActionOverlay(
            selectedText = activeSelectedText,
            // The ViewModel preloads this list on IO; never query PackageManager
            // synchronously during composition (it janks the frame when hit).
            availableApps = availableShareApps,
            isShareAppsDrawerVisible = isShareAppsDrawerVisible,
            clipboardManager = clipboardManager,
            onDismissSelection = {
                activeSelectedText = null
                activeSelectedPage = null
                activeSelectedWordIndices = emptySet()
                isShareAppsDrawerVisible = false
            },
            onSetShareAppsDrawerVisible = { isShareAppsDrawerVisible = it },
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        // Search Overlay UI (When in-document search is activated)
        PdfSearchOverlay(
            isVisible = isSearchActive,
            query = searchQuery,
            results = searchResults,
            currentIndex = currentMatchIndex,
            extractionProgress = extractionProgress,
            onQueryChanged = onSearchQueryChanged,
            onNextMatch = onNextMatch,
            onPrevMatch = onPrevMatch,
            onClose = onCloseSearch,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
        )

        // Floating Page Number Badge (Left-top: e.g. "3/4" - ONLY when controls are visible or actively dragging slider)
        AnimatedVisibility(
            visible = pdfItem.pageCount > 1 && (isControlsVisible || isSliderDragging),
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(top = 72.dp, start = 16.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0x992B2D30),
                shadowElevation = 4.dp
            ) {
                Text(
                    text = "${currentPage + 1}/${pdfItem.pageCount}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                )
            }
        }

        // Right-Edge Vertical Fast-Scroll Slider (Drag up/down along right edge - ONLY when controls are visible or actively dragging)
        if (pdfItem.pageCount > 1) {
            AnimatedVisibility(
                visible = isControlsVisible || isSliderDragging,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
            ) {
                BoxWithConstraints(
                    modifier = Modifier.fillMaxHeight()
                ) {
                    val density = LocalDensity.current
                    val totalHeightPx = constraints.maxHeight.toFloat()
                    val topInsetPx = with(density) { 80.dp.toPx() }
                    val bottomInsetPx = with(density) { 100.dp.toPx() }
                    val handleHeightPx = with(density) { 48.dp.toPx() }
                    val usableTrackHeight = (totalHeightPx - topInsetPx - bottomInsetPx - handleHeightPx).coerceAtLeast(1f)

                    var dragYOffset by remember { mutableFloatStateOf(0f) }

                    val pageFraction = currentPage.toFloat() / (pdfItem.pageCount - 1).coerceAtLeast(1)
                    val visualOffsetPx = if (isSliderDragging) {
                        dragYOffset.coerceIn(0f, usableTrackHeight)
                    } else {
                        pageFraction * usableTrackHeight
                    }

                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset {
                                androidx.compose.ui.unit.IntOffset(
                                    x = 0,
                                    y = (topInsetPx + visualOffsetPx).roundToInt()
                                )
                            }
                            .padding(end = 4.dp)
                            .shadow(6.dp, RoundedCornerShape(topStart = 10.dp, bottomStart = 10.dp, topEnd = 4.dp, bottomEnd = 4.dp))
                            .background(
                                color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.96f),
                                shape = RoundedCornerShape(topStart = 10.dp, bottomStart = 10.dp, topEnd = 4.dp, bottomEnd = 4.dp)
                            )
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                                RoundedCornerShape(topStart = 10.dp, bottomStart = 10.dp, topEnd = 4.dp, bottomEnd = 4.dp)
                            )
                            .pointerInput(pdfItem.pageCount, usableTrackHeight) {
                                detectVerticalDragGestures(
                                    onDragStart = {
                                        isSliderDragging = true
                                        val initialFraction = currentPage.toFloat() / (pdfItem.pageCount - 1).coerceAtLeast(1)
                                        dragYOffset = initialFraction * usableTrackHeight
                                    },
                                    onDragEnd = { isSliderDragging = false },
                                    onDragCancel = { isSliderDragging = false },
                                    onVerticalDrag = { change, dragAmount ->
                                        change.consume()
                                        dragYOffset = (dragYOffset + dragAmount).coerceIn(0f, usableTrackHeight)
                                        val newFraction = (dragYOffset / usableTrackHeight).coerceIn(0f, 1f)
                                        val targetPage = (newFraction * (pdfItem.pageCount - 1)).roundToInt().coerceIn(0, pdfItem.pageCount - 1)
                                        if (targetPage != currentPage) {
                                            onPageChanged(targetPage)
                                            coroutineScope.launch {
                                                listState.scrollToItem(targetPage)
                                            }
                                        }
                                    }
                                )
                            }
                            .padding(horizontal = 8.dp, vertical = 12.dp)
                            .testTag("vertical_fast_scroll_handle")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Menu,
                            contentDescription = "ページスクロールスライダー",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        // Bottom Controls Container: Floating Toolbar (Rotate, Share, Search)
        AnimatedVisibility(
            visible = isControlsVisible,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 360.dp)
                    .fillMaxWidth(0.86f)
                    .height(58.dp)
                    .testTag("pdf_bottom_toolbar"),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                border = BorderStroke(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant
                ),
                tonalElevation = 6.dp,
                shadowElevation = 12.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 1. 回転 (Rotate: Toggles portrait / landscape)
                    IconButton(
                        onClick = {
                            activity?.let { act ->
                                val currentOrientation = act.requestedOrientation
                                val newOrientation = if (currentOrientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
                                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                } else {
                                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                                }
                                act.requestedOrientation = newOrientation
                                val modeStr = if (newOrientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) "横画面" else "縦画面"
                                Toast.makeText(context, "$modeStr に切り替えました", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier
                            .size(44.dp)
                            .testTag("bottom_action_rotate")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ScreenRotation,
                            contentDescription = "回転",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // 2. シェア (Share: Share PDF file with other apps)
                    IconButton(
                        onClick = {
                            sharePdfFile(context, pdfItem)
                        },
                        modifier = Modifier
                            .size(44.dp)
                            .testTag("bottom_action_share")
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_share_box_arrow),
                            contentDescription = "共有",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    // 3. 検索 (Search: Text search within PDF)
                    IconButton(
                        onClick = onOpenSearch,
                        modifier = Modifier
                            .size(44.dp)
                            .testTag("bottom_action_search")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "検索",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }

    // Dialogs
    if (isNoteDialogOpen) {
        PdfNoteBottomSheet(
            fileName = pdfItem.fileName,
            initialNote = pdfItem.noteContent,
            onDismiss = onCloseNoteDialog,
            onSave = onSaveNote
        )
    }

    if (isRenameDialogOpen) {
        PdfRenameDialog(
            currentName = pdfItem.fileName,
            onDismiss = onCloseRenameDialog,
            onConfirm = onRenameFile
        )
    }
}

@Composable
fun PdfPageItem(
    pageIndex: Int,
    pdfEngine: PdfEngine?,
    pageText: PageText?,
    isDarkMode: Boolean = false,
    zoomScale: Float = 1f,
    translatedBlocks: List<com.example.pdf.TranslatedBlock> = emptyList(),
    showTranslateUnsupportedNotice: Boolean = false,
    isTranslatingPage: Boolean = false,
    searchHighlightIndices: Set<Int> = emptySet(),
    selectedWordIndices: Set<Int>,
    onWordsSelected: (Set<Int>, String) -> Unit,
    onPageTextRecognized: (PageText) -> Unit,
    onTap: () -> Unit,
    onDoubleTap: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val config = LocalConfiguration.current
    val screenWidthPx = with(density) { config.screenWidthDp.dp.roundToPx() }

    val pageDimensions = remember(pdfEngine, pageIndex) {
        pdfEngine?.getPageDimensions(pageIndex) ?: Pair(595, 842)
    }

    val aspectRatio = remember(pageDimensions) {
        pageDimensions.first.toFloat() / pageDimensions.second.toFloat().coerceAtLeast(1f)
    }

    // Re-render at higher resolution once pinch zoom settles past 1.5x, so
    // enlarged text stays sharp instead of just scaling the base bitmap.
    val targetWidth = remember(screenWidthPx, zoomScale) {
        val zoomFactor = when {
            zoomScale >= 2.5f -> 2.5f
            zoomScale >= 2f -> 2f
            zoomScale >= 1.5f -> 1.5f
            else -> 1f
        }
        (screenWidthPx * 1.5f * zoomFactor).toInt()
    }
    val initialCached = remember(pdfEngine, pageIndex, targetWidth) {
        pdfEngine?.getCachedBitmap(pageIndex, targetWidth)
    }

    val pageBitmapState = produceState<Bitmap?>(initialValue = initialCached, pdfEngine, pageIndex, targetWidth) {
        if (pdfEngine != null) {
            val rendered = pdfEngine.renderPageBitmap(pageIndex, targetWidth = targetWidth)
            if (rendered != null) value = rendered
        }
    }

    val words = remember(pageText) { pageText?.words.orEmpty() }
    val currentBitmap = pageBitmapState.value

    var dragStartWordIndex by remember { mutableStateOf<Int?>(null) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(aspectRatio)
            .testTag("pdf_page_$pageIndex"),
        color = if (isDarkMode) Color(0xFF222428) else Color.White
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val pageWPx = constraints.maxWidth.toFloat()
            val pageHPx = constraints.maxHeight.toFloat()

            // 1. High-Definition Vector Bitmap Render of PDF
            if (currentBitmap != null) {
                Image(
                    bitmap = currentBitmap.asImageBitmap(),
                    contentDescription = "${pageIndex + 1}ページ",
                    contentScale = ContentScale.FillBounds,
                    colorFilter = if (isDarkMode) ColorFilter.colorMatrix(PdfDarkColorMatrix) else null,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(36.dp),
                        strokeWidth = 3.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // 2. High-Precision Highlight Overlay Canvas (Word-level precision with handles - Chrome Style)
            val sortedSelectedWords = remember(selectedWordIndices, words) {
                if (selectedWordIndices.isNotEmpty() && words.isNotEmpty()) {
                    selectedWordIndices.sorted().mapNotNull { words.getOrNull(it) }
                } else emptyList()
            }

            // Search hit highlight (amber), drawn under the selection highlight.
            val searchHighlightWords = remember(searchHighlightIndices, words) {
                if (searchHighlightIndices.isNotEmpty() && words.isNotEmpty()) {
                    searchHighlightIndices.sorted().mapNotNull { words.getOrNull(it) }
                } else emptyList()
            }

            Canvas(modifier = Modifier.fillMaxSize()) {
                if (searchHighlightWords.isNotEmpty()) {
                    val searchHighlightColor = Color(0x66FFB300)
                    searchHighlightWords.forEach { word ->
                        drawRoundRect(
                            color = searchHighlightColor,
                            topLeft = Offset(word.relX * pageWPx, word.relY * pageHPx),
                            size = Size(
                                (word.relWidth * pageWPx).coerceAtLeast(6f),
                                (word.relHeight * pageHPx).coerceAtLeast(6f)
                            ),
                            cornerRadius = CornerRadius(2f, 2f)
                        )
                    }
                }
                if (sortedSelectedWords.isNotEmpty()) {
                    // Chrome Selection Colors
                    val chromeHighlightColor = Color(0x663388F6)
                    val chromeHandleColor = Color(0xFF4285F4)

                    // Draw highlight box for each individual selected word
                    sortedSelectedWords.forEach { word ->
                        val left = word.relX * pageWPx
                        val top = word.relY * pageHPx
                        val width = (word.relWidth * pageWPx).coerceAtLeast(6f)
                        val height = (word.relHeight * pageHPx).coerceAtLeast(6f)

                        // Translucent Chrome blue highlight
                        drawRoundRect(
                            color = chromeHighlightColor,
                            topLeft = Offset(left, top),
                            size = Size(width, height),
                            cornerRadius = CornerRadius(2f, 2f)
                        )
                    }

                    // Draw start pin line & circle
                    sortedSelectedWords.firstOrNull()?.let { firstWord ->
                        val pinX = firstWord.relX * pageWPx
                        val pinTopY = firstWord.relY * pageHPx
                        val pinBottomY = firstWord.relY * pageHPx + firstWord.relHeight * pageHPx
                        drawLine(
                            color = chromeHandleColor,
                            start = Offset(pinX, pinTopY),
                            end = Offset(pinX, pinBottomY),
                            strokeWidth = 3f
                        )
                        drawCircle(
                            color = chromeHandleColor,
                            radius = 11f,
                            center = Offset(pinX, pinTopY)
                        )
                    }

                    // Draw end pin line & circle
                    sortedSelectedWords.lastOrNull()?.let { lastWord ->
                        val pinX = lastWord.relX * pageWPx + lastWord.relWidth * pageWPx
                        val pinTopY = lastWord.relY * pageHPx
                        val pinBottomY = lastWord.relY * pageHPx + lastWord.relHeight * pageHPx
                        drawLine(
                            color = chromeHandleColor,
                            start = Offset(pinX, pinTopY),
                            end = Offset(pinX, pinBottomY),
                            strokeWidth = 3f
                        )
                        drawCircle(
                            color = chromeHandleColor,
                            radius = 11f,
                            center = Offset(pinX, pinBottomY)
                        )
                    }
                }
            }

            // 2b. Translation overlay: paint over each source block's rect
            // (padded so every source glyph is covered) and draw the
            // translation strictly inside that rect, keeping the original
            // layout (columns, figures, formulas) untouched. The drawing is
            // measure → shrink → clip (see TranslatedTextLayout), so text can
            // never overlap neighbouring blocks or spill over figures.
            if (translatedBlocks.isNotEmpty()) {
                val blockBg = if (isDarkMode) Color(0xFF222428) else Color.White
                val blockFg = if (isDarkMode) Color(0xFFE8EAED) else Color(0xFF141518)
                val renderBlocks = remember(translatedBlocks) {
                    com.example.pdf.PdfBlockExtractor.mergeOverlappingBlocks(translatedBlocks)
                }
                val overlayPaint = remember {
                    android.graphics.Paint().apply {
                        isAntiAlias = true
                        typeface = android.graphics.Typeface.DEFAULT
                    }
                }
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val measurer = PaintTextMeasurer(overlayPaint)
                    renderBlocks.forEach { tb ->
                        drawTranslatedBlock(tb, overlayPaint, measurer, blockBg, blockFg)
                    }
                }
            }

            if (showTranslateUnsupportedNotice && translatedBlocks.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0x992B2D30),
                    modifier = Modifier.align(Alignment.Center)
                ) {
                    Text(
                        text = "テキスト層がないため、このページは和訳未対応です",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            } else if (isTranslatingPage && translatedBlocks.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0x992B2D30),
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                ) {
                    Text(
                        text = "翻訳中…",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }

            // Helper function to find closest word index given page coordinates
            fun findClosestWordIndex(relX: Float, relY: Float): Int {
                if (words.isEmpty()) return 0
                val hit = words.indexOfFirst { w ->
                    relX >= (w.relX - 0.02f) && relX <= (w.relX + w.relWidth + 0.02f) &&
                    relY >= (w.relY - 0.015f) && relY <= (w.relY + w.relHeight + 0.015f)
                }
                return if (hit != -1) hit else {
                    words.indices.minByOrNull { i ->
                        val w = words[i]
                        val dy = (w.relY + w.relHeight / 2f) - relY
                        val dx = (w.relX + w.relWidth / 2f) - relX
                        (dy * 3f) * (dy * 3f) + dx * dx
                    } ?: 0
                }
            }

            // 3. Gesture Layer: Detects single tap, double-tap zoom, and long-press drag word selection
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(pageIndex, words) {
                        detectTapGestures(
                            onTap = {
                                onTap()
                            },
                            onDoubleTap = {
                                onDoubleTap()
                            }
                        )
                    }
                    .pointerInput(pageIndex, words) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { startOffset ->
                                if (words.isNotEmpty()) {
                                    val relX = startOffset.x / pageWPx
                                    val relY = startOffset.y / pageHPx
                                    val hitIndex = findClosestWordIndex(relX, relY)
                                    dragStartWordIndex = hitIndex
                                    val selectedIndices = setOf(hitIndex)
                                    val text = words[hitIndex].text
                                    onWordsSelected(selectedIndices, text)
                                }
                            },
                            onDrag = { change, _ ->
                                val startIdx = dragStartWordIndex
                                if (startIdx != null && words.isNotEmpty()) {
                                    val curRelX = change.position.x / pageWPx
                                    val curRelY = change.position.y / pageHPx
                                    val curIndex = findClosestWordIndex(curRelX, curRelY)

                                    val minIdx = min(startIdx, curIndex)
                                    val maxIdx = max(startIdx, curIndex)
                                    val indices = (minIdx..maxIdx).toSet()
                                    val combinedText = PdfTextExtractor.formatSelectedWords(words, indices)
                                    onWordsSelected(indices, combinedText)
                                }
                            },
                            onDragEnd = {
                                dragStartWordIndex = null
                            },
                            onDragCancel = {
                                dragStartWordIndex = null
                            }
                        )
                    }
            )

            // 4. Interactive Start and End Selection Handles (Allows adjusting selection boundaries anytime after release)
            if (sortedSelectedWords.isNotEmpty()) {
                val firstWord = sortedSelectedWords.first()
                val lastWord = sortedSelectedWords.last()

                val minSelectedIdx = selectedWordIndices.minOrNull() ?: 0
                val maxSelectedIdx = selectedWordIndices.maxOrNull() ?: 0

                val startPinXPx = firstWord.relX * pageWPx
                val startPinYPx = firstWord.relY * pageHPx
                val endPinXPx = (lastWord.relX + lastWord.relWidth) * pageWPx
                val endPinYPx = (lastWord.relY + lastWord.relHeight) * pageHPx

                val handleTouchSizeDp = 44.dp
                val handleTouchRadiusPx = with(density) { (handleTouchSizeDp / 2).toPx() }

                // Interactive Start Handle (Drag to adjust starting boundary)
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                x = (startPinXPx - handleTouchRadiusPx).roundToInt(),
                                y = (startPinYPx - handleTouchRadiusPx).roundToInt()
                            )
                        }
                        .size(handleTouchSizeDp)
                        .pointerInput(pageIndex, minSelectedIdx, maxSelectedIdx, words) {
                            var currentDragStartOffset = Offset.Zero
                            detectDragGestures(
                                onDragStart = { offset ->
                                    currentDragStartOffset = Offset(startPinXPx, startPinYPx) + offset - Offset(handleTouchRadiusPx, handleTouchRadiusPx)
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    currentDragStartOffset += dragAmount
                                    val curRelX = (currentDragStartOffset.x / pageWPx).coerceIn(0f, 1f)
                                    val curRelY = (currentDragStartOffset.y / pageHPx).coerceIn(0f, 1f)
                                    val newStartIdx = findClosestWordIndex(curRelX, curRelY)

                                    val newMin = min(newStartIdx, maxSelectedIdx)
                                    val newMax = max(newStartIdx, maxSelectedIdx)
                                    val newIndices = (newMin..newMax).toSet()
                                    val text = PdfTextExtractor.formatSelectedWords(words, newIndices)
                                    onWordsSelected(newIndices, text)
                                }
                            )
                        }
                        .testTag("selection_start_handle")
                )

                // Interactive End Handle (Drag to adjust ending boundary)
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                x = (endPinXPx - handleTouchRadiusPx).roundToInt(),
                                y = (endPinYPx - handleTouchRadiusPx).roundToInt()
                            )
                        }
                        .size(handleTouchSizeDp)
                        .pointerInput(pageIndex, minSelectedIdx, maxSelectedIdx, words) {
                            var currentDragEndOffset = Offset.Zero
                            detectDragGestures(
                                onDragStart = { offset ->
                                    currentDragEndOffset = Offset(endPinXPx, endPinYPx) + offset - Offset(handleTouchRadiusPx, handleTouchRadiusPx)
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    currentDragEndOffset += dragAmount
                                    val curRelX = (currentDragEndOffset.x / pageWPx).coerceIn(0f, 1f)
                                    val curRelY = (currentDragEndOffset.y / pageHPx).coerceIn(0f, 1f)
                                    val newEndIdx = findClosestWordIndex(curRelX, curRelY)

                                    val newMin = min(minSelectedIdx, newEndIdx)
                                    val newMax = max(minSelectedIdx, newEndIdx)
                                    val newIndices = (newMin..newMax).toSet()
                                    val text = PdfTextExtractor.formatSelectedWords(words, newIndices)
                                    onWordsSelected(newIndices, text)
                                }
                            )
                        }
                        .testTag("selection_end_handle")
                )
            }
        }
    }
}

/** Bridges [android.graphics.Paint] to the pure layout math in [com.example.pdf.TranslatedTextLayout]. */
private class PaintTextMeasurer(
    private val paint: android.graphics.Paint
) : com.example.pdf.TranslatedTextLayout.Measurer {
    override fun textWidth(text: String, fontSizePx: Float): Float {
        paint.textSize = fontSizePx
        return paint.measureText(text)
    }

    override fun fontHeight(fontSizePx: Float): Float {
        paint.textSize = fontSizePx
        val fm = paint.fontMetrics
        return fm.descent - fm.ascent
    }
}

/**
 * Paints one translated block: erases the source text under a padded rect,
 * then draws the translation inside that rect only. The font size comes
 * from the source line height and is shrunk by [com.example.pdf.TranslatedTextLayout]
 * until the wrapped lines fit; the native canvas is clipped to the rect as
 * a final guarantee, so glyphs can never reach neighbouring content.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTranslatedBlock(
    tb: com.example.pdf.TranslatedBlock,
    paint: android.graphics.Paint,
    measurer: com.example.pdf.TranslatedTextLayout.Measurer,
    bg: Color,
    fg: Color
) {
    val b = tb.block
    val pageW = size.width
    val pageH = size.height
    if (pageW <= 0f || pageH <= 0f) return
    val avgLineHPx = when {
        b.avgLineHeight > 0f -> b.avgLineHeight * pageH
        b.lineCount > 0 -> (b.relHeight * pageH) / b.lineCount
        else -> b.relHeight * pageH
    }
    if (avgLineHPx <= 0f) return

    // Erase rect: block rect plus padding (title-size glyphs included), so
    // no source character peeks out from under the translation. The
    // padding scales with the block's line height — i.e. with the source
    // font size — and the vertical share is generous because display type
    // has tall ascenders/descenders relative to its line box.
    val padX = max(2f, avgLineHPx * 0.35f)
    val padY = max(2f, avgLineHPx * 0.35f)
    val eraseLeft = (b.relX * pageW - padX).coerceAtLeast(0f)
    val eraseTop = (b.relY * pageH - padY).coerceAtLeast(0f)
    val eraseRight = ((b.relX + b.relWidth) * pageW + padX).coerceAtMost(pageW)
    val eraseBottom = ((b.relY + b.relHeight) * pageH + padY).coerceAtMost(pageH)
    if (eraseRight <= eraseLeft || eraseBottom <= eraseTop) return
    drawRect(
        color = bg,
        topLeft = Offset(eraseLeft, eraseTop),
        size = Size(eraseRight - eraseLeft, eraseBottom - eraseTop)
    )

    // Text area: inside the erased rect, slightly inset.
    val insetX = max(1f, padX * 0.4f)
    val insetY = max(1f, padY * 0.4f)
    val textLeft = eraseLeft + insetX
    val textTop = eraseTop + insetY
    val textWidth = eraseRight - eraseLeft - insetX * 2
    val textHeight = eraseBottom - eraseTop - insetY * 2
    if (textWidth <= 0f || textHeight <= 0f) return

    // Start from the source's own font size (line height / line-height
    // factor) so body text stays body-sized and titles stay large.
    val startFontPx = (avgLineHPx / com.example.pdf.TranslatedTextLayout.MIN_LINE_HEIGHT_FACTOR)
        .coerceIn(4f, pageH * 0.08f)
    val minFontPx = 5.dp.toPx()
    val result = com.example.pdf.TranslatedTextLayout.layout(
        text = tb.translatedText,
        maxWidthPx = textWidth,
        maxHeightPx = textHeight,
        startFontSizePx = startFontPx,
        minFontSizePx = minFontPx,
        measurer = measurer
    )
    if (result.lines.isEmpty()) return

    paint.textSize = result.fontSizePx
    paint.color = fg.toArgb()
    val fm = paint.fontMetrics
    val fontHeight = fm.descent - fm.ascent
    // Centre each line's font box inside its line-height slot.
    val baselineOffset = (result.lineHeightPx - fontHeight) / 2f - fm.ascent
    with(drawContext.canvas.nativeCanvas) {
        val saveCount = save()
        clipRect(textLeft, textTop, textLeft + textWidth, textTop + textHeight)
        result.lines.forEachIndexed { i, line ->
            drawText(line, textLeft, textTop + baselineOffset + i * result.lineHeightPx, paint)
        }
        restoreToCount(saveCount)
    }
}

private fun sharePdfFile(context: Context, item: PdfItem) {
    try {
        val file = item.filePath?.let { File(it) }
        val shareUri: Uri = if (file != null && file.exists()) {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        } else {
            Uri.parse(item.uriString)
        }

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, shareUri)
            putExtra(Intent.EXTRA_SUBJECT, item.fileName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "${item.fileName} を共有"))
    } catch (e: Exception) {
        Toast.makeText(context, "共有できませんでした: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}
