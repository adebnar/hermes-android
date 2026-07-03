package com.hermes.client.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingShareStoreTest {
    @Test fun put_then_take_same_id_returns_text_once() {
        val store = PendingShareStore()
        store.put("s1", "hello")
        assertEquals("hello", store.take("s1"))
        assertNull(store.take("s1"))   // consumed — one-shot
    }
    @Test fun take_other_id_returns_null_and_keeps_value() {
        val store = PendingShareStore()
        store.put("s1", "hello")
        assertNull(store.take("s2"))            // not the target session
        assertEquals("hello", store.take("s1")) // still available for the right session
    }
    @Test fun take_when_empty_is_null() {
        assertNull(PendingShareStore().take("s1"))
    }
}
