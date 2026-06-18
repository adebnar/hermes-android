package com.hermes.client.data.network

import app.cash.turbine.test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    // A long-lived scope shared by all tests in this class; real Dispatchers.IO threads
    // are fine here because OkHttp itself runs on real threads.
    private val testScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
        return HermesGatewayClient(okHttp, json, testScope) { "$base?token=t" } to okHttp
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

    private companion object {
        const val GATEWAY_READY_FRAME =
            """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{}}}"""
    }

    @Test fun call_resolves_with_matching_reply() = runTest {
        // Server sends gateway.ready on open, then echoes a result for whatever id the client sent.
        serverRule.server.enqueue(
            MockResponse.Builder().webSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        // Send gateway.ready so the client's readyGate is lifted before call().
                        webSocket.send(GATEWAY_READY_FRAME)
                    }

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
                        webSocket.send(GATEWAY_READY_FRAME)
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

    /**
     * Asserts that connectionState is Connecting after the socket opens but BEFORE
     * gateway.ready is delivered, and Connected only after gateway.ready arrives.
     */
    @Test fun state_is_connecting_until_gateway_ready_received() = runTest {
        // Gate that the test controls: server holds gateway.ready until we release it.
        val allowReady = CompletableDeferred<Unit>()
        // Gate that signals the test once the socket has opened server-side.
        val socketOpened = CompletableDeferred<WebSocket>()

        serverRule.server.enqueue(
            MockResponse.Builder().webSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        socketOpened.complete(webSocket)
                        // Do NOT send gateway.ready yet — wait for the test to release.
                    }
                }
            ).build()
        )

        val (client, okHttp) = makeClientAndHttp(serverRule.server)
        try {
            withContext(Dispatchers.Default) {
                client.connect()

                // Wait until the server-side socket is open.
                val serverWs = withTimeout(5_000) { socketOpened.await() }

                // State must be Connecting (not Connected) before gateway.ready.
                assertEquals(ConnectionState.Connecting, client.connectionState.value)

                // Now deliver gateway.ready from the server.
                serverWs.send(GATEWAY_READY_FRAME)

                // Poll until Connected (the message is dispatched on OkHttp's IO thread).
                withTimeout(5_000) {
                    while (client.connectionState.value != ConnectionState.Connected) {
                        kotlinx.coroutines.delay(10)
                    }
                }
                assertEquals(ConnectionState.Connected, client.connectionState.value)
            }
        } finally {
            tearDownClient(client, okHttp)
        }
    }
}
