package com.hermes.client.data.network

import com.hermes.client.data.auth.GatewayConfig
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.junit4.MockWebServerRule
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class HermesRestApiTranscribeTest {
    @get:Rule val serverRule = MockWebServerRule()
    private val json = Json { ignoreUnknownKeys = true }

    private fun api(server: MockWebServer) = HermesRestApi(OkHttpClient(), json) {
        GatewayConfig(baseUrl = server.url("/").toString().trimEnd('/'), token = "secret")
    }

    @Test fun transcribe_returns_trimmed_transcript_and_posts_data_url() = runTest {
        serverRule.server.enqueue(MockResponse.Builder().code(200).body(
            """{"ok":true,"transcript":"  book the flight  ","provider":"local"}"""
        ).build())

        val text = api(serverRule.server).transcribe("data:audio/mp4;base64,AAA", "audio/mp4")
        assertEquals("book the flight", text)

        val recorded = serverRule.server.takeRequest()
        assertEquals("/api/audio/transcribe", recorded.target)
        assertEquals("secret", recorded.headers["X-Hermes-Session-Token"])
        val sent = recorded.body?.utf8().orEmpty()
        assertTrue(sent.contains("\"data_url\":\"data:audio/mp4;base64,AAA\""))
        assertTrue(sent.contains("\"mime_type\":\"audio/mp4\""))
    }

    @Test fun transcribe_blank_transcript_returns_empty() = runTest {
        serverRule.server.enqueue(MockResponse.Builder().code(200).body("""{"ok":true}""").build())
        assertEquals("", api(serverRule.server).transcribe("data:audio/mp4;base64,AAA", "audio/mp4"))
    }

    @Test fun transcribe_error_throws() = runTest {
        serverRule.server.enqueue(MockResponse.Builder().code(400).body("""{"detail":"no stt"}""").build())
        try {
            api(serverRule.server).transcribe("data:audio/mp4;base64,AAA", "audio/mp4")
            org.junit.Assert.fail("expected HermesApiException")
        } catch (e: HermesApiException) {
            assertEquals(400, e.code)
        }
    }
}
