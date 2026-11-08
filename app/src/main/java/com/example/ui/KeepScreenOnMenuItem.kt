package com.example.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.StayCurrentPortrait
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight

/** How long the screen stays on without any input while the setting is on. */
const val KEEP_SCREEN_ON_IDLE_MINUTES = 30

/**
 * Settings-menu toggle for "画面をオフにしない". The setting is applied by
 * MainActivity (only while the app is in the foreground).
 */
@Composable
fun KeepScreenOnMenuItem(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onDismissMenu: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = MaterialTheme.colorScheme.primary
    DropdownMenuItem(
        text = {
            Column {
                Text(
                    text = "画面をオフにしない",
                    fontWeight = if (enabled) FontWeight.Bold else FontWeight.Medium,
                    color = if (enabled) accent else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "表示中は操作がなくても${KEEP_SCREEN_ON_IDLE_MINUTES}分間消灯しない",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.StayCurrentPortrait,
                contentDescription = null,
                tint = if (enabled) accent else MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingIcon = if (enabled) {
            {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "オン",
                    tint = accent
                )
            }
        } else null,
        onClick = {
            onDismissMenu()
            onEnabledChange(!enabled)
        },
        modifier = modifier
    )
}
