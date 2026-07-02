package com.hermes.client.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Curated brand palette. Per-profile accent (see ProfileAccent.kt) tints the chrome on top
// of these neutral base schemes; the base stays calm so chat content reads cleanly and so
// Material You (opt-in) can slot in as an alternative neutral base without a redesign.

// Brand — a calm indigo/violet, AI-adjacent without leaning on any one competitor.
private val Indigo10 = Color(0xFF14103A)
private val Indigo20 = Color(0xFF241C63)
private val Indigo30 = Color(0xFF362C86)
private val Indigo40 = Color(0xFF4B3FA8)
private val Indigo80 = Color(0xFFC5C0FF)
private val Indigo90 = Color(0xFFE3DFFF)

private val Violet40 = Color(0xFF7A4E8C)
private val Violet80 = Color(0xFFEBB4FF)
private val Violet90 = Color(0xFFF9D8FF)

// Neutrals tuned for comfortable long-form reading.
private val LightBackground = Color(0xFFFDFBFF)
private val LightSurface = Color(0xFFFDFBFF)
private val LightSurfaceVariant = Color(0xFFE4E1EC)
private val LightOutline = Color(0xFF767680)

private val DarkBackground = Color(0xFF121218)
private val DarkSurface = Color(0xFF121218)
private val DarkSurfaceVariant = Color(0xFF47464F)
private val DarkOutline = Color(0xFF918F9A)

val HermesLightColors = lightColorScheme(
    primary = Indigo40,
    onPrimary = Color.White,
    primaryContainer = Indigo90,
    onPrimaryContainer = Indigo10,
    secondary = Color(0xFF5D5C72),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE2DFF9),
    onSecondaryContainer = Color(0xFF1A1A2C),
    tertiary = Violet40,
    onTertiary = Color.White,
    tertiaryContainer = Violet90,
    onTertiaryContainer = Color(0xFF2E0A3D),
    background = LightBackground,
    onBackground = Color(0xFF1B1B1F),
    surface = LightSurface,
    onSurface = Color(0xFF1B1B1F),
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = Color(0xFF46464F),
    outline = LightOutline,
    outlineVariant = Color(0xFFC7C5D0),
)

val HermesDarkColors = darkColorScheme(
    primary = Indigo80,
    onPrimary = Indigo20,
    primaryContainer = Indigo30,
    onPrimaryContainer = Indigo90,
    secondary = Color(0xFFC6C3DC),
    onSecondary = Color(0xFF2F2E41),
    secondaryContainer = Color(0xFF454559),
    onSecondaryContainer = Color(0xFFE2DFF9),
    tertiary = Violet80,
    onTertiary = Color(0xFF46204F),
    tertiaryContainer = Color(0xFF603867),
    onTertiaryContainer = Violet90,
    background = DarkBackground,
    onBackground = Color(0xFFE5E1E9),
    surface = DarkSurface,
    onSurface = Color(0xFFE5E1E9),
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = Color(0xFFC9C5D0),
    outline = DarkOutline,
    outlineVariant = Color(0xFF47464F),
)
