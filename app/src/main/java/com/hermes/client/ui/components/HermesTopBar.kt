package com.hermes.client.ui.components

import androidx.compose.foundation.isSystemInDarkTheme
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
    val dark = isSystemInDarkTheme()
    val barBg = if (dark) accent.container else accent.accent
    val barOn = if (dark) accent.onContainer else accent.onAccent
    // The app bar carries the active tenant's colour: a bold saturated bar (accent + on-accent)
    // in light mode, and a soft tinted bar (container + on-container) in dark mode. barBg/barOn
    // pick the right pair so all chrome text/icons stay legible in both themes.
    val colors: TopAppBarColors = TopAppBarDefaults.topAppBarColors(
        containerColor = barBg,
        titleContentColor = barOn,
        navigationIconContentColor = barOn,
        actionIconContentColor = barOn,
    )
    TopAppBar(
        modifier = modifier,
        colors = colors,
        title = {
            Column {
                Text(title, style = MaterialTheme.typography.titleLarge, color = barOn)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = barOn.copy(alpha = 0.75f),
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
    val onBar: Color @Composable get() =
        if (isSystemInDarkTheme()) LocalProfileAccent.current.onContainer
        else LocalProfileAccent.current.onAccent
}
