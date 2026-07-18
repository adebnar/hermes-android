package com.hermes.client.data.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioDataUrlTest {
    @Test fun builds_base64_data_url_with_mime_prefix() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val url = audioDataUrl(bytes, "audio/mp4")
        assertTrue(url.startsWith("data:audio/mp4;base64,"))
        val b64 = url.removePrefix("data:audio/mp4;base64,")
        assertEquals(bytes.toList(), java.util.Base64.getDecoder().decode(b64).toList())
    }

    @Test fun empty_bytes_still_valid() {
        assertEquals("data:audio/mp4;base64,", audioDataUrl(ByteArray(0), "audio/mp4"))
    }
}
