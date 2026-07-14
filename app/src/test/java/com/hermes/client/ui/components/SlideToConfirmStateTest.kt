package com.hermes.client.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlideToConfirmStateTest {
    @Test fun progress_is_clamped_0_to_1() {
        assertEquals(0f, slideProgress(dragPx = -50f, trackPx = 100f), 0.001f)
        assertEquals(0.5f, slideProgress(dragPx = 50f, trackPx = 100f), 0.001f)
        assertEquals(1f, slideProgress(dragPx = 150f, trackPx = 100f), 0.001f)
    }

    @Test fun confirmed_only_past_threshold() {
        assertFalse(isConfirmed(0.89f))
        assertTrue(isConfirmed(0.90f))
        assertTrue(isConfirmed(1f))
    }

    @Test fun zero_track_is_safe() {
        assertEquals(0f, slideProgress(dragPx = 10f, trackPx = 0f), 0.001f)
    }
}
