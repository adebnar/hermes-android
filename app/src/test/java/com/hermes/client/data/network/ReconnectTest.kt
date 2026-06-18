package com.hermes.client.data.network

import org.junit.Assert.assertEquals
import org.junit.Test

class BackoffPolicyTest {
    @Test fun grows_exponentially_and_caps() {
        val p = BackoffPolicy(baseMs = 500, factor = 2.0, maxMs = 8000)
        assertEquals(500L, p.delayFor(0))
        assertEquals(1000L, p.delayFor(1))
        assertEquals(2000L, p.delayFor(2))
        assertEquals(8000L, p.delayFor(10)) // capped
    }
}
