package com.hermes.client.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Per-profile accent color. Each profile (tenant) deterministically maps to a hue, so the
// chrome — top bar, bottom nav, group headers, FAB — visibly reflects *which* profile you're
// in. This is both an identity flourish the single-account consumer apps can't do and a
// tenant-isolation safety signal (you always see the client you're acting as).
//
// Scope is CHROME ONLY (resolved decision, 2026-07-02): the chat body stays neutral for
// legibility. The math below is pure (ARGB Int in/out) so it unit-tests without Compose or
// an Android runtime; the Compose-facing wrapper just boxes the ints into Color.

/** Fallback hue for the default/no profile — a stable brand indigo rather than a hashed one. */
internal const val DEFAULT_ACCENT_HUE = 245f

private const val WHITE_ARGB = 0xFFFFFFFF.toInt()
private const val BLACK_ARGB = 0xFF000000.toInt()

/**
 * Deterministic hue in [0,360) from a profile name: FNV-1a for the string, then a murmur3
 * fmix32 avalanche so that similar names (e.g. "personal" vs "odos") decorrelate into
 * visibly distinct hues rather than clustering. The final unsigned modulo keeps the spread
 * even (a signed modulo would re-cluster ~half the space). Distinctness is guaranteed-tested
 * for the known tenant set; for arbitrarily many profiles occasional near-collisions are
 * mathematically unavoidable (birthday problem) and acceptable — this is a hint, not an ID.
 */
internal fun profileHue(name: String): Float {
    var h = -0x7ee3623b // 2166136261 (FNV offset basis) as a signed Int
    for (c in name) {
        h = h xor c.code
        h *= 0x01000193 // FNV prime
    }
    h = h xor (h ushr 16)
    h *= 0x85ebca6b.toInt()
    h = h xor (h ushr 13)
    h *= 0xc2b2ae35.toInt()
    h = h xor (h ushr 16)
    val unsigned = h.toLong() and 0xFFFFFFFFL
    return (unsigned % 360L).toFloat()
}

/** Vivid accent (FAB, selected nav, indicators). Lighter on dark, deeper on light. */
internal fun accentArgb(profile: String?, dark: Boolean): Int {
    val hue = if (profile.isNullOrBlank()) DEFAULT_ACCENT_HUE else profileHue(profile)
    val s = if (dark) 0.52f else 0.62f
    val l = if (dark) 0.62f else 0.44f
    return hslToArgb(hue, s, l)
}

/** Soft tinted container (header chips, subtle backgrounds). */
internal fun containerArgb(profile: String?, dark: Boolean): Int {
    val hue = if (profile.isNullOrBlank()) DEFAULT_ACCENT_HUE else profileHue(profile)
    val s = if (dark) 0.38f else 0.42f
    val l = if (dark) 0.24f else 0.90f
    return hslToArgb(hue, s, l)
}

/** Black or white — whichever has higher contrast against [argb]. */
internal fun onColorFor(argb: Int): Int =
    if (contrastRatio(argb, WHITE_ARGB) >= contrastRatio(argb, BLACK_ARGB)) WHITE_ARGB else BLACK_ARGB

internal fun hslToArgb(hDeg: Float, s: Float, l: Float): Int {
    val h = ((hDeg % 360f) + 360f) % 360f / 360f
    val q = if (l < 0.5f) l * (1 + s) else l + s - l * s
    val p = 2 * l - q
    val r = hueToChannel(p, q, h + 1f / 3f)
    val g = hueToChannel(p, q, h)
    val b = hueToChannel(p, q, h - 1f / 3f)
    return (0xFF shl 24) or (to255(r) shl 16) or (to255(g) shl 8) or to255(b)
}

private fun hueToChannel(p: Float, q: Float, tIn: Float): Float {
    var t = tIn
    if (t < 0f) t += 1f
    if (t > 1f) t -= 1f
    return when {
        t < 1f / 6f -> p + (q - p) * 6f * t
        t < 1f / 2f -> q
        t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
        else -> p
    }
}

private fun to255(v: Float): Int = (v * 255f + 0.5f).toInt().coerceIn(0, 255)

/** WCAG relative luminance of an ARGB int (alpha ignored). */
internal fun relativeLuminance(argb: Int): Double {
    fun lin(channel: Int): Double {
        val c = channel / 255.0
        return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
    }
    val r = lin((argb shr 16) and 0xFF)
    val g = lin((argb shr 8) and 0xFF)
    val b = lin(argb and 0xFF)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

/** WCAG contrast ratio between two ARGB ints (>= 1.0). */
internal fun contrastRatio(a: Int, b: Int): Double {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    val hi = maxOf(la, lb)
    val lo = minOf(la, lb)
    return (hi + 0.05) / (lo + 0.05)
}

/** Compose-facing accent bundle for the active profile. */
data class ProfileAccentColors(
    val accent: Color,
    val onAccent: Color,
    val container: Color,
    val onContainer: Color,
)

fun profileAccentColors(profile: String?, dark: Boolean, overrideArgb: Int? = null): ProfileAccentColors {
    // A user-chosen colour (if any) is used verbatim as the accent; otherwise fall back to the
    // deterministic hashed hue. The soft container is derived from whichever colour we end up with.
    val a = overrideArgb ?: accentArgb(profile, dark)
    val c = if (overrideArgb != null) softContainerFrom(overrideArgb, dark) else containerArgb(profile, dark)
    return ProfileAccentColors(
        accent = Color(a),
        onAccent = Color(onColorFor(a)),
        container = Color(c),
        onContainer = Color(onColorFor(c)),
    )
}

/** Soft accent-tinted container derived from a chosen accent colour (for the top-bar tint). */
internal fun softContainerFrom(argb: Int, dark: Boolean): Int {
    val (h, s, _) = argbToHsl(argb)
    return hslToArgb(h, s * 0.55f, if (dark) 0.24f else 0.90f)
}

/** ARGB → HSL (hue in [0,360), saturation/lightness in [0,1]); alpha ignored. */
internal fun argbToHsl(argb: Int): Triple<Float, Float, Float> {
    val r = ((argb shr 16) and 0xFF) / 255f
    val g = ((argb shr 8) and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val l = (max + min) / 2f
    if (max == min) return Triple(0f, 0f, l)
    val d = max - min
    val s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
    val h = when (max) {
        r -> (g - b) / d + (if (g < b) 6f else 0f)
        g -> (b - r) / d + 2f
        else -> (r - g) / d + 4f
    } * 60f
    return Triple(h, s, l)
}

/**
 * Curated, contrast-safe accent swatches for the per-profile colour picker: evenly-spaced hues at
 * a saturation/lightness whose on-colour stays legible (verified in tests). Lets users set a
 * colour without a free wheel that could produce an illegible tint.
 */
// Contrast is guaranteed by construction: onColorFor() adaptively picks black or white, and the
// worst-case max(black,white) contrast for any opaque colour is ~4.58 — always above the AA-large
// 3.0 bar. So any chosen colour stays legible as long as chrome text uses the paired on-colour;
// no colour needs to be rejected. (This is why the picker can even be a free wheel later.)
val ACCENT_SWATCHES: List<Int> = (0 until 12).map { hslToArgb(it * 30f, 0.62f, 0.44f) }

/** Available anywhere in the tree; defaults to the brand (no-profile) accent in light mode. */
val LocalProfileAccent = staticCompositionLocalOf { profileAccentColors(null, dark = false) }

/** Per-profile colour overrides (profile name → ARGB), provided at the app root from persistence. */
val LocalProfileAccentOverrides = staticCompositionLocalOf<Map<String, Int>> { emptyMap() }

/** Accent for [profile], consulting the user's override map from [LocalProfileAccentOverrides]. */
@androidx.compose.runtime.Composable
fun rememberProfileAccent(profile: String?, dark: Boolean): ProfileAccentColors =
    profileAccentColors(profile, dark, LocalProfileAccentOverrides.current[profile])
