package com.hermes.client.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class PromptStoreTest {
    private val a = SavedPrompt("1", "Greet", "Say hello")
    private val b = SavedPrompt("2", "Bye", "Say bye")

    @Test fun decode_bad_or_empty_is_empty_list() {
        assertEquals(emptyList<SavedPrompt>(), decodePrompts(null))
        assertEquals(emptyList<SavedPrompt>(), decodePrompts(""))
        assertEquals(emptyList<SavedPrompt>(), decodePrompts("not json"))
        assertEquals(emptyList<SavedPrompt>(), decodePrompts("{}"))
    }

    @Test fun encode_decode_round_trip() {
        assertEquals(listOf(a, b), decodePrompts(encodePrompts(listOf(a, b))))
    }

    @Test fun upsert_appends_new_and_replaces_existing() {
        assertEquals(listOf(a, b), upsertPrompt(listOf(a), b))
        val edited = a.copy(title = "Hi")
        assertEquals(listOf(edited, b), upsertPrompt(listOf(a, b), edited)) // replaced in place, order kept
    }

    @Test fun delete_removes_by_id_and_noops_when_absent() {
        assertEquals(listOf(b), deletePrompt(listOf(a, b), "1"))
        assertEquals(listOf(a, b), deletePrompt(listOf(a, b), "nope"))
    }
}
