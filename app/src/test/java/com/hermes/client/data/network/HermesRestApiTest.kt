package com.hermes.client.data.network

import com.hermes.client.data.auth.GatewayConfig
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.junit4.MockWebServerRule
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class HermesRestApiTest {
    @get:Rule val serverRule = MockWebServerRule()
    private val json = Json { ignoreUnknownKeys = true }

    private fun api(server: MockWebServer) = HermesRestApi(OkHttpClient(), json) {
        GatewayConfig(baseUrl = server.url("/").toString().trimEnd('/'), token = "secret")
    }

    @Test fun sessions_parses_list_and_sends_token() = runTest {
        serverRule.server.enqueue(MockResponse.Builder().code(200).body(
            """{"sessions":[{"id":"s1","title":"First","model":"opus","provider":"anthropic","message_count":3}]}"""
        ).build())

        val list = api(serverRule.server).sessions(limit = 20, offset = 0)
        assertEquals(1, list.size)
        assertEquals("First", list[0].title)

        val recorded = serverRule.server.takeRequest()
        assertTrue(recorded.target.startsWith("/api/sessions"))
        assertEquals("secret", recorded.headers["X-Hermes-Session-Token"])
    }

    // T1: the cross-profile list tags each session with its true profile and carries
    // profile_totals for group headers. A default-profile session reports is_default_profile.
    @Test fun profileSessions_parses_per_session_profile_and_totals() = runTest {
        serverRule.server.enqueue(MockResponse.Builder().code(200).body(
            """{"sessions":[
                {"id":"s1","title":"Mine","profile":"personal","cwd":"/home/me/app","archived":false},
                {"id":"s2","title":"Default","is_default_profile":true,"archived":false}
            ],"total":2,"profile_totals":{"personal":1,"default":1},"errors":[]}"""
        ).build())

        val res = api(serverRule.server).profileSessions()
        assertEquals(2, res.sessions.size)
        assertEquals("personal", res.sessions[0].profile)
        // is_default_profile with no explicit profile name still surfaces (normalized downstream).
        assertTrue(res.sessions[1].isDefaultProfile)
        assertEquals(1, res.profileTotals["personal"])

        val recorded = serverRule.server.takeRequest()
        assertTrue(recorded.target.startsWith("/api/profiles/sessions"))
    }

    // T4: the archived cross-profile view requests archived=only on the same endpoint.
    @Test fun profileSessions_archivedOnly_appends_param() = runTest {
        serverRule.server.enqueue(MockResponse.Builder().code(200).body(
            """{"sessions":[],"total":0,"profile_totals":{},"errors":[]}"""
        ).build())

        api(serverRule.server).profileSessions(archivedOnly = true)

        val recorded = serverRule.server.takeRequest()
        assertTrue(recorded.target.startsWith("/api/profiles/sessions"))
        assertTrue("must request only archived sessions", recorded.target.contains("archived=only"))
    }

    @Test fun status_returns_true_on_200() = runTest {
        serverRule.server.enqueue(MockResponse.Builder().code(200).body("""{"ok":true}""").build())
        assertTrue(api(serverRule.server).status())
    }

    // T10b: statusFor() uses supplied baseUrl+token directly, never touches configProvider
    @Test fun statusFor_uses_explicit_credentials_not_stored_config() = runTest {
        val server = serverRule.server
        server.enqueue(MockResponse.Builder().code(200).body("""{"ok":true}""").build())

        // api() is wired with token="secret", but we call statusFor with a different token
        val result = api(server).statusFor(
            baseUrl = server.url("/").toString().trimEnd('/'),
            token = "explicit-token",
        )
        assertTrue(result)
        val recorded = server.takeRequest()
        assertEquals("explicit-token", recorded.headers["X-Hermes-Session-Token"])
    }

    @Test fun statusFor_returns_false_on_non_2xx() = runTest {
        serverRule.server.enqueue(MockResponse.Builder().code(401).body("Unauthorized").build())
        val result = api(serverRule.server).statusFor(
            baseUrl = serverRule.server.url("/").toString().trimEnd('/'),
            token = "bad-token",
        )
        assertFalse(result)
    }

    @Test fun setActiveProfile_posts_to_correct_endpoint_with_token_and_name() = runTest {
        serverRule.server.enqueue(MockResponse.Builder().code(200).body("{}").build())

        api(serverRule.server).setActiveProfile("personal")

        val recorded = serverRule.server.takeRequest()
        assertEquals("POST", recorded.method)
        assertTrue(recorded.target.startsWith("/api/profiles/active"))
        assertEquals("secret", recorded.headers["X-Hermes-Session-Token"])
        val body = recorded.body?.utf8().orEmpty()
        assertTrue("body should contain profile name", body.contains("\"personal\""))
    }
}
