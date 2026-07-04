package com.hermes.client.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingShareStoreTest {
    @Test fun put_then_take_same_id_returns_share_once() {
        val store = PendingShareStore()
        store.put("s1", PendingShare(text = "hello"))
        assertEquals("hello", store.take("s1")?.text)
        assertNull(store.take("s1"))   // consumed
    }

    @Test fun take_other_id_returns_null_and_keeps_value() {
        val store = PendingShareStore()
        store.put("s1", PendingShare(text = "hello"))
        assertNull(store.take("s2"))
        assertEquals("hello", store.take("s1")?.text)
    }

    @Test fun stores_image_fields() {
        val store = PendingShareStore()
        store.put("s1", PendingShare(imageBase64 = "AAA", imageMime = "image/png"))
        val s = store.take("s1")!!
        assertEquals("AAA", s.imageBase64)
        assertEquals("image/png", s.imageMime)
        assertNull(s.text)
    }

    @Test fun take_when_empty_is_null() {
        assertNull(PendingShareStore().take("s1"))
    }
}
