package com.hermes.client.ui.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingPayloadTest {
    @Test fun parses_gated_payload() {
        val p = parsePairingPayload("""{"v":1,"url":"https://h.ts.net","username":"a","password":"p"}""")!!
        assertEquals("https://h.ts.net", p.url)
        assertEquals("a", p.username)
        assertEquals("p", p.password)
        assertEquals("", p.token)
    }

    @Test fun parses_token_payload() {
        val p = parsePairingPayload("""{"v":1,"url":"http://127.0.0.1:9119","token":"tok"}""")!!
        assertEquals("tok", p.token)
        assertEquals("", p.username)
    }

    @Test fun ignores_unknown_keys() {
        val p = parsePairingPayload("""{"v":1,"url":"http://h","extra":"x"}""")!!
        assertEquals("http://h", p.url)
    }

    @Test fun rejects_malformed_json() {
        assertNull(parsePairingPayload("not json"))
        assertNull(parsePairingPayload("{bad"))
        assertNull(parsePairingPayload("\"https://h\"")) // a bare string, not an object
    }

    @Test fun rejects_wrong_or_missing_version() {
        assertNull(parsePairingPayload("""{"v":2,"url":"http://h"}"""))
        assertNull(parsePairingPayload("""{"url":"http://h"}""")) // v defaults to 0
    }

    @Test fun rejects_blank_url() {
        assertNull(parsePairingPayload("""{"v":1,"url":""}"""))
        assertNull(parsePairingPayload("""{"v":1}"""))
    }
}
