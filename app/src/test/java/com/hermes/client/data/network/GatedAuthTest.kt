package com.hermes.client.data.network

import com.hermes.client.data.auth.GatewayConfig
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatedAuthTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun authFor(base: String) =
        GatedAuth(json) { GatewayConfig(baseUrl = base, token = "", username = "admin", password = "pw") }

    @Test fun login_posts_basic_credentials_and_succeeds() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse.Builder()
                    .body("{\"ok\":true}")
                    .addHeader("Set-Cookie", "hermes_session_at=abc; Path=/")
                    .build(),
            )
            server.start()
            val base = server.url("/").toString().trimEnd('/')

            assertTrue(authFor(base).login())

            val req = server.takeRequest()
            assertEquals("POST", req.method)
            assertEquals("/auth/password-login", req.target)
            val body = req.body?.utf8().orEmpty()
            assertTrue(body.contains("\"provider\":\"basic\""))
            assertTrue(body.contains("\"username\":\"admin\""))
            assertTrue(body.contains("\"password\":\"pw\""))
        }
    }

    @Test fun login_returns_false_on_401() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().code(401).body("{\"detail\":\"Invalid credentials\"}").build())
            server.start()
            assertFalse(authFor(server.url("/").toString().trimEnd('/')).login())
        }
    }

    @Test fun wsTicket_parses_ticket_field() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().body("{\"ticket\":\"TICK123\",\"ttl_seconds\":30}").build())
            server.start()
            val auth = authFor(server.url("/").toString().trimEnd('/'))
            val client = OkHttpClient.Builder().cookieJar(auth.cookieJar).build()

            assertEquals("TICK123", auth.wsTicket(client))
            assertEquals("/api/auth/ws-ticket", server.takeRequest().target)
        }
    }
}
