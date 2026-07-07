package com.hermes.client.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class DictationTest {
    @Test fun blank_current_returns_spoken() {
        assertEquals("hello", appendDictation("", "hello"))
        assertEquals("hello", appendDictation("   ", "  hello  "))
    }
    @Test fun appends_with_single_space() {
        assertEquals("type and speak", appendDictation("type", "and speak"))
        assertEquals("type more", appendDictation("type ", "  more "))
    }
    @Test fun blank_spoken_leaves_current_unchanged() {
        assertEquals("type", appendDictation("type", ""))
        assertEquals("type", appendDictation("type", "   "))
    }
    @Test fun trims_spoken() {
        assertEquals("hi there", appendDictation("hi", "  there  "))
    }
}
