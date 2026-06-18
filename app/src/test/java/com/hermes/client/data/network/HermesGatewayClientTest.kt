package com.hermes.client.data.network

import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.junit4.MockWebServerRule
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit

class HermesGatewayClientTest {
    @get:Rule val serverRule = MockWebServerRule()
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Creates an OkHttpClient and HermesGatewayClient for one test.
     * Call [drainOkHttp] on the returned OkHttpClient before the test ends
     * so MockWebServer's taskRunner queue is empty when the rule's @after fires.
     */
    private fun makeClientAndHttp(server: MockWebServer): Pair<HermesGatewayClient, OkHttpClient> {
        val base = server.url("/api/ws").toString().replace("http", "ws")
        val okHttp = OkHttpClient.Builder()
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        return HermesGatewayClient(okHttp, json) { "$base?token=t" } to okHttp
    }

    /**
     * Forcibly terminate the OkHttp client so MockWebServer's internal task queues drain.
     * We use cancel() on the WebSocket (immediate TCP close) rather than close() (graceful
     * close handshake) because the MockWebServer rule's after() fires before we can wait for
     * the handshake to complete.
     */
    private fun tearDownClient(client: HermesGatewayClient, okHttp: OkHttpClient) {
        // Cancel (force-close) rather than graceful close so TCP sockets shut immediately.
        client.cancelNow()
        okHttp.dispatcher.executorService.shutdown()
        okHttp.dispatcher.executorService.awaitTermination(5, TimeUnit.SECONDS)
        okHttp.connectionPool.evictAll()
    }

    @Test fun call_resolves_with_matching_reply() = runTest {
        // Server echoes a result for whatever id the client sent.
        serverRule.server.enqueue(
            MockResponse.Builder().webSocketUpgrade(
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val id = json.parseToJsonElement(text).jsonObject["id"]!!.jsonPrimitive.content
                        webSocket.send("""{"jsonrpc":"2.0","id":$id,"result":{"pong":true}}""")
                    }
                }
            ).build()
        )

        val (client, okHttp) = makeClientAndHttp(serverRule.server)
        try {
            client.connect()
            // OkHttp WebSocket runs on real threads — use real time via Dispatchers.Default
            val result = withContext(Dispatchers.Default) {
                withTimeout(5_000) { client.call("ping", buildJsonObject {}) }
            }
            assertEquals("true", result.jsonObject["pong"]!!.jsonPrimitive.content)
        } finally {
            tearDownClient(client, okHttp)
        }
    }

    @Test fun emits_gateway_ready_and_flips_connected() = runTest {
        serverRule.server.enqueue(
            MockResponse.Builder().webSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{}}}""")
                    }
                }
            ).build()
        )

        val (client, okHttp) = makeClientAndHttp(serverRule.server)
        try {
            withContext(Dispatchers.Default) {
                client.events.test {
                    client.connect()
                    val event = withTimeout(5_000) { awaitItem() }
                    assertEquals("gateway.ready", event.type)
                    assertEquals(ConnectionState.Connected, client.connectionState.value)
                    cancelAndIgnoreRemainingEvents()
                }
            }
        } finally {
            tearDownClient(client, okHttp)
        }
    }
}
