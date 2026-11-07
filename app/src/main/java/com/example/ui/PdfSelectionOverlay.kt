package com.example.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.util.LastShareAppManager
import com.example.util.LastSharedApp

/**
 * Encapsulated direct text selection action overlay (Copy, Share, Web Search, and Quick-Share Apps Bar).
 * Positions directly at the bottom center with the quick share apps bar emerging directly over the app icon.
 */
@Composable
fun PdfSelectionActionOverlay(
    selectedText: String?,
    availableApps: List<LastSharedApp>,
    isShareAppsDrawerVisible: Boolean,
    clipboardManager: ClipboardManager,
    onDismissSelection: () -> Unit,
    onSetShareAppsDrawerVisible: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    AnimatedVisibility(
        visible = selectedText != null,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = modifier
            .navigationBarsPadding()
            .padding(bottom = 28.dp)
    ) {
        val selText = selectedText.orEmpty()
        val lastApp = availableApps.firstOrNull()

        FloatingDirectTextActionBar(
            selectedText = selText,
            lastSharedApp = lastApp,
            availableApps = availableApps,
            isShareAppsDrawerVisible = isShareAppsDrawerVisible,
            onCopy = {
                clipboardManager.setText(AnnotatedString(selText))
                Toast.makeText(context, "コピーしました", Toast.LENGTH_SHORT).show()
                onDismissSelection()
            },
            onShare = {
                try {
                    val chooser = LastShareAppManager.createShareChooserIntent(context, selText)
                    context.startActivity(chooser)
                } catch (e: Exception) {
                    Toast.makeText(context, "共有できませんでした", Toast.LENGTH_SHORT).show()
                }
                onDismissSelection()
            },
            onDirectShare = { targetApp ->
                val success = LastShareAppManager.directShare(context, selText, targetApp)
                if (!success) {
                    try {
                        val chooser = LastShareAppManager.createShareChooserIntent(context, selText)
                        context.startActivity(chooser)
                    } catch (e: Exception) {
                        Toast.makeText(context, "共有できませんでした", Toast.LENGTH_SHORT).show()
                    }
                }
                onDismissSelection()
            },
            onSwipeUpShareApps = {
                onSetShareAppsDrawerVisible(true)
            },
            onCloseShareApps = {
                onSetShareAppsDrawerVisible(false)
            },
            onWebSearch = {
                try {
                    val searchUri = Uri.parse("https://www.google.com/search?q=${Uri.encode(selText)}")
                    val intent = Intent(Intent.ACTION_VIEW, searchUri)
                    context.startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(context, "ブラウザを開けませんでした", Toast.LENGTH_SHORT).show()
                }
                onDismissSelection()
            }
        )
    }
}

@Composable
fun FloatingDirectTextActionBar(
    selectedText: String,
    lastSharedApp: LastSharedApp?,
    availableApps: List<LastSharedApp>,
    isShareAppsDrawerVisible: Boolean,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onDirectShare: (LastSharedApp) -> Unit,
    onSwipeUpShareApps: () -> Unit,
    onCloseShareApps: () -> Unit,
    onWebSearch: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.End,
        modifier = modifier.padding(horizontal = 8.dp)
    ) {
        // Vertical Shared Apps Bar (Positioned directly above the app icon)
        AnimatedVisibility(
            visible = isShareAppsDrawerVisible,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.padding(bottom = 6.dp, end = 4.dp)
        ) {
            PdfShareAppsVerticalBar(
                apps = availableApps,
                onAppClicked = onDirectShare,
                onClose = onCloseShareApps
            )
        }

        // Horizontal Action Bar Pill
        Surface(
            modifier = Modifier
                .shadow(12.dp, RoundedCornerShape(28.dp))
                .testTag("floating_direct_selection_bar"),
            shape = RoundedCornerShape(28.dp),
            color = Color(0xFF282A2D),
            border = BorderStroke(0.5.dp, Color(0xFF444746))
        ) {
            Row(
                modifier = Modifier
                    .height(48.dp)
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 1. Web Search with Google G icon
                TextButton(
                    onClick = onWebSearch,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = Color(0xFFE8EAED)
                    ),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .height(38.dp)
                        .testTag("direct_action_search")
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_google_g),
                        contentDescription = null,
                        tint = Color.Unspecified,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Web Search",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFFE8EAED),
                        fontSize = 13.sp
                    )
                }

                // 2. Copy
                TextButton(
                    onClick = onCopy,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = Color(0xFFE8EAED)
                    ),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .height(38.dp)
                        .testTag("direct_action_copy")
                ) {
                    Text(
                        text = "Copy",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFFE8EAED),
                        fontSize = 13.sp
                    )
                }

                // 3. Share
                TextButton(
                    onClick = onShare,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = Color(0xFFE8EAED)
                    ),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .height(38.dp)
                        .testTag("direct_action_share")
                ) {
                    Text(
                        text = "Share",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFFE8EAED),
                        fontSize = 13.sp
                    )
                }

                // 4. Share App Icon Button (Swipe up to pull out apps bar, or tap to share/expand)
                var dragOffsetY by remember { mutableFloatStateOf(0f) }

                Box(
                    modifier = Modifier
                        .padding(start = 2.dp, end = 4.dp)
                        .pointerInput(Unit) {
                            detectVerticalDragGestures(
                                onVerticalDrag = { change, dragAmount ->
                                    change.consume()
                                    dragOffsetY += dragAmount
                                    if (dragOffsetY < -14f) {
                                        onSwipeUpShareApps()
                                    }
                                },
                                onDragEnd = {
                                    if (dragOffsetY < -10f) {
                                        onSwipeUpShareApps()
                                    }
                                    dragOffsetY = 0f
                                },
                                onDragCancel = {
                                    dragOffsetY = 0f
                                }
                            )
                        }
                ) {
                    Surface(
                        onClick = {
                            if (isShareAppsDrawerVisible) {
                                onCloseShareApps()
                            } else if (lastSharedApp != null) {
                                onDirectShare(lastSharedApp)
                            } else {
                                onSwipeUpShareApps()
                            }
                        },
                        shape = CircleShape,
                        color = Color(0xFF383B40),
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("direct_action_share_last_app")
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            if (lastSharedApp?.iconBitmap != null) {
                                Image(
                                    bitmap = lastSharedApp.iconBitmap.asImageBitmap(),
                                    contentDescription = "${lastSharedApp.appName} に送信 (上にスワイプで全アプリ)",
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clip(CircleShape)
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = "共有アプリ一覧",
                                    tint = Color(0xFFE8EAED),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Vertical icon-only bar representing previously shared/available apps.
 * Positioned directly at the share app icon on top of the popup.
 */
@Composable
fun PdfShareAppsVerticalBar(
    apps: List<LastSharedApp>,
    onAppClicked: (LastSharedApp) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var dragDownOffsetY by remember { mutableFloatStateOf(0f) }

    Surface(
        modifier = modifier
            .width(52.dp)
            .heightIn(max = 280.dp)
            .shadow(16.dp, RoundedCornerShape(26.dp))
            .border(BorderStroke(0.5.dp, Color(0xFF444746)), RoundedCornerShape(26.dp))
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        dragDownOffsetY += dragAmount
                        if (dragDownOffsetY > 25f) {
                            onClose()
                        }
                    },
                    onDragEnd = {
                        if (dragDownOffsetY > 20f) {
                            onClose()
                        }
                        dragDownOffsetY = 0f
                    },
                    onDragCancel = {
                        dragDownOffsetY = 0f
                    }
                )
            }
            .testTag("pdf_share_apps_vertical_bar"),
        shape = RoundedCornerShape(26.dp),
        color = Color(0xFF282A2D)
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(vertical = 10.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            apps.forEach { app ->
                Surface(
                    onClick = { onAppClicked(app) },
                    shape = CircleShape,
                    color = Color.Transparent,
                    modifier = Modifier
                        .size(38.dp)
                        .testTag("share_drawer_app_${app.packageName}")
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        if (app.iconBitmap != null) {
                            Image(
                                bitmap = app.iconBitmap.asImageBitmap(),
                                contentDescription = "${app.appName} で共有",
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                            )
                        } else {
                            Surface(
                                shape = CircleShape,
                                color = Color(0xFF383B40),
                                modifier = Modifier.size(34.dp)
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Share,
                                        contentDescription = app.appName,
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
