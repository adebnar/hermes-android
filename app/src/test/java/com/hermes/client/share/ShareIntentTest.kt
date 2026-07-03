package com.hermes.client.share

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShareIntentTest {
    private val send = Intent.ACTION_SEND

    @Test fun text_only_returns_trimmed_text() {
        assertEquals("https://x.com", sharedText(send, "text/plain", null, "  https://x.com  "))
    }
    @Test fun subject_and_text_combined_when_different() {
        assertEquals("My Page\nhttps://x.com", sharedText(send, "text/plain", "My Page", "https://x.com"))
    }
    @Test fun subject_equal_to_text_not_duplicated() {
        assertEquals("https://x.com", sharedText(send, "text/plain", "https://x.com", "https://x.com"))
    }
    @Test fun only_subject_returns_subject() {
        assertEquals("A note", sharedText(send, "text/plain", "A note", null))
    }
    @Test fun non_send_action_is_null() {
        assertNull(sharedText(Intent.ACTION_VIEW, "text/plain", null, "hi"))
    }
    @Test fun non_text_type_is_null() {
        assertNull(sharedText(send, "image/png", null, "hi"))
    }
    @Test fun blank_or_absent_returns_null() {
        assertNull(sharedText(send, "text/plain", "  ", ""))
        assertNull(sharedText(send, "text/plain", null, null))
        assertNull(sharedText(send, null, "s", "t"))
    }
}
