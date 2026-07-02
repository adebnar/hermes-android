package com.hermes.client.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalContext

/** App-wide flag for tool-call verbosity (true = Technical/show payloads, false = Product/hide). */
val LocalToolCallTechnical = compositionLocalOf { true }

@Composable
fun HermesTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Material You is opt-in and acts only as a neutral base — the per-profile accent still
    // wins for chrome. Off by default so the curated palette is the stable, screenshot-ready look.
    dynamicColor: Boolean = false,
    // Active profile name → chrome accent. Null keeps the brand (no-profile) accent.
    profile: String? = null,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> HermesDarkColors
        else -> HermesLightColors
    }
    // Honour a user-chosen colour for the active profile (falls back to the hashed hue).
    val accent = profileAccentColors(profile, darkTheme, LocalProfileAccentOverrides.current[profile])

    CompositionLocalProvider(LocalProfileAccent provides accent) {
        MaterialTheme(
            colorScheme = colors,
            typography = HermesTypography,
            shapes = HermesShapes,
            content = content,
        )
    }
}
