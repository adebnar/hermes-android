package com.hermes.client.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileAccentTest {

    private val profiles = listOf("default", "personal", "odos", "semiotic", "dito")

    @Test fun hue_is_deterministic() {
        assertEquals(profileHue("personal"), profileHue("personal"), 0f)
        assertEquals(profileHue("odos"), profileHue("odos"), 0f)
    }

    @Test fun hue_is_in_range() {
        for (p in profiles) {
            val h = profileHue(p)
            assertTrue("$p hue in [0,360): $h", h >= 0f && h < 360f)
        }
    }

    // The whole point of profile-color is that tenants look different. Require the real
    // profiles to land on visibly distinct hues (>= 20° apart pairwise).
    @Test fun real_profiles_get_distinct_hues() {
        val hues = profiles.map { it to profileHue(it) }
        for (i in hues.indices) for (j in i + 1 until hues.size) {
            val (na, a) = hues[i]
            val (nb, b) = hues[j]
            val delta = minOf(Math.abs(a - b), 360f - Math.abs(a - b))
            assertTrue("$na($a) vs $nb($b) too close: ${delta}°", delta >= 20f)
        }
    }

    @Test fun accent_is_deterministic_per_mode() {
        assertEquals(accentArgb("odos", dark = false), accentArgb("odos", dark = false))
        assertNotEquals(accentArgb("odos", dark = false), accentArgb("odos", dark = true))
    }

    // Chrome tinting must stay legible: the chosen on-color needs at least large-text
    // contrast (WCAG AA large = 3.0) against both the accent and the soft container, in
    // light and dark, for every profile — otherwise the tint fails a11y on some tenant.
    @Test fun on_colors_meet_large_text_contrast() {
        for (p in profiles) for (dark in listOf(false, true)) {
            val accent = accentArgb(p, dark)
            val container = containerArgb(p, dark)
            assertTrue(
                "accent contrast for $p dark=$dark",
                contrastRatio(accent, onColorFor(accent)) >= 3.0,
            )
            assertTrue(
                "container contrast for $p dark=$dark",
                contrastRatio(container, onColorFor(container)) >= 3.0,
            )
        }
    }

    @Test fun null_profile_falls_back_to_brand_hue() {
        assertEquals(accentArgb(null, dark = false), accentArgb("", dark = false))
    }

    @Test fun hsl_primaries_are_correct() {
        assertEquals(0xFFFF0000.toInt(), hslToArgb(0f, 1f, 0.5f))   // red
        assertEquals(0xFF00FF00.toInt(), hslToArgb(120f, 1f, 0.5f)) // green
        assertEquals(0xFF0000FF.toInt(), hslToArgb(240f, 1f, 0.5f)) // blue
        assertEquals(0xFFFFFFFF.toInt(), hslToArgb(0f, 0f, 1f))     // white
        assertEquals(0xFF000000.toInt(), hslToArgb(0f, 0f, 0f))     // black
    }
}
