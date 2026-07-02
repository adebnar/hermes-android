package com.hermes.client.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.hermes.client.ui.theme.LocalProfileAccent

/**
 * App bar tinted by the active profile's accent (chrome-only per the design decision). The
 * soft `container` color fills the bar and the accent's on-color carries the title, so the
 * whole top of the screen reflects which tenant you're acting as — a glanceable isolation
 * signal the single-account apps can't offer. Falls back to the brand accent for no profile.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HermesTopBar(
    title: String,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    subtitle: String? = null,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
) {
    val accent = LocalProfileAccent.current
    // Soft tint: a pale accent-tinted bar with a contrast-checked dark/light on-color. Keeps
    // the tenant signal without a heavy fully-saturated header, and — critically — every bit
    // of chrome text/icon must use `onContainer` so nothing (e.g. action labels) goes illegible.
    val colors: TopAppBarColors = TopAppBarDefaults.topAppBarColors(
        containerColor = accent.container,
        titleContentColor = accent.onContainer,
        navigationIconContentColor = accent.onContainer,
        actionIconContentColor = accent.onContainer,
    )
    TopAppBar(
        modifier = modifier,
        colors = colors,
        title = {
            Column {
                Text(title, style = MaterialTheme.typography.titleLarge, color = accent.onContainer)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = accent.onContainer.copy(alpha = 0.75f),
                    )
                }
            }
        },
        navigationIcon = navigationIcon,
        actions = actions,
    )
}

/** Convenience accessors so screens can tint other chrome (FAB, action labels) to the accent. */
object AccentChrome {
    val fabContainer: Color @Composable get() = LocalProfileAccent.current.accent
    val onFab: Color @Composable get() = LocalProfileAccent.current.onAccent

    /** Legible content color for anything sitting on the soft-tinted top bar (e.g. action text). */
    val onBar: Color @Composable get() = LocalProfileAccent.current.onContainer
}
