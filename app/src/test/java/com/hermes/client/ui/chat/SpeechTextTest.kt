package com.hermes.client.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTextTest {
    @Test fun strips_emphasis_and_headings() {
        assertEquals("Hello world", speechText("**Hello** _world_"))
        assertEquals("Title", speechText("# Title"))
    }

    @Test fun link_becomes_its_text() {
        assertEquals("click here", speechText("[click here](https://example.com)"))
    }

    @Test fun inline_code_backticks_stripped() {
        assertEquals("run ls now", speechText("run `ls` now"))
    }

    @Test fun fenced_code_block_removed() {
        val out = speechText("before\n```kotlin\nval x = 1\n```\nafter")
        assertTrue(out.contains("before"))
        assertTrue(out.contains("after"))
        assertTrue(!out.contains("val x = 1"))
    }

    @Test fun plain_text_unchanged_and_empty_is_empty() {
        assertEquals("just words", speechText("just words"))
        assertEquals("", speechText(""))
    }
}
