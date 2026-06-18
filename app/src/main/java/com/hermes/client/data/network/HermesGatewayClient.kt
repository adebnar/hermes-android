package com.hermes.client.data.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import kotlin.math.pow

class GatewayRpcException(val code: Int, message: String) : Exception(message)

data class BackoffPolicy(
    val baseMs: Long = 500,
    val factor: Double = 2.0,
    val maxMs: Long = 10_000,
) {
    fun delayFor(attempt: Int): Long =
        min(maxMs, (baseMs * factor.pow(attempt)).toLong())
}

open class HermesGatewayClient(
    private val okHttp: OkHttpClient,
    private val json: Json,
    private val scope: CoroutineScope,
    private val backoff: BackoffPolicy = BackoffPolicy(),
    private val wsUrlProvider: () -> String,
) {
    private val _events = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<ServerEvent> = _events.asSharedFlow()

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _state.asStateFlow()

    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonElement>>()

    @Volatile private var ws: WebSocket? = null
    @Volatile protected var manuallyClosed = false
    private val attempt = AtomicInteger(0)

    // Readiness gate: awaited by call() before sending RPCs.
    // Recreated (uncompleted) on each openSocket(); completed when gateway.ready arrives;
    // completed exceptionally when socket closes/fails or close() is called.
    @Volatile private var readyGate: CompletableDeferred<Unit> = CompletableDeferred()

    fun connect() {
        manuallyClosed = false
        openSocket()
    }

    protected fun openSocket() {
        // Install a fresh, uncompleted readiness gate for this new socket attempt.
        readyGate = CompletableDeferred()
        _state.value = ConnectionState.Connecting
        val request = Request.Builder().url(wsUrlProvider()).build()
        ws = okHttp.newWebSocket(request, listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            // Do NOT set state to Connected here. Wait for gateway.ready event.
            // Do NOT reset the attempt counter here either.
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            text.lineSequence().filter { it.isNotBlank() }.forEach { line ->
                when (val msg = parseInbound(json, line)) {
                    is RpcResult -> pending.remove(msg.id)?.complete(msg.result)
                    is RpcErrorReply -> pending.remove(msg.id)
                        ?.completeExceptionally(GatewayRpcException(msg.error.code, msg.error.message))
                    is RpcEvent -> {
                        // Handle gateway.ready: flip to Connected and open the readiness gate.
                        if (msg.event.type == "gateway.ready") {
                            attempt.set(0)
                            _state.value = ConnectionState.Connected
                            readyGate.complete(Unit)
                        }
                        _events.tryEmit(msg.event)
                    }
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            onSocketClosed(t.message ?: "connection failed")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            onSocketClosed(reason.ifBlank { "closed" })
        }
    }

    protected open fun onSocketClosed(reason: String) {
        // Fail any call() that is currently awaiting readiness so it throws immediately.
        readyGate.completeExceptionally(GatewayRpcException(0, reason))
        failAllPending(reason)
        if (manuallyClosed) {
            _state.value = ConnectionState.Disconnected
            return
        }
        _state.value = ConnectionState.Reconnecting
        val delayMs = backoff.delayFor(attempt.getAndIncrement())
        scope.launch {
            kotlinx.coroutines.delay(delayMs)
            if (!manuallyClosed) openSocket()
        }
    }

    private fun failAllPending(reason: String) {
        pending.keys.toList().forEach { id ->
            pending.remove(id)?.completeExceptionally(GatewayRpcException(0, reason))
        }
    }

    suspend fun call(method: String, params: JsonObject): JsonElement {
        // Wait until gateway.ready has been received before sending any RPC.
        readyGate.await()
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<JsonElement>()
        pending[id] = deferred
        val sent = ws?.send(RpcRequest(id, method, params).encode(json)) ?: false
        if (!sent) {
            pending.remove(id)
            throw GatewayRpcException(0, "not connected")
        }
        return deferred.await()
    }

    fun close() {
        manuallyClosed = true
        // Fail any call() awaiting readiness so it throws immediately rather than hanging.
        readyGate.completeExceptionally(GatewayRpcException(0, "client closing"))
        failAllPending("client closing")
        ws?.close(1000, "client closing")
        ws = null
        _state.value = ConnectionState.Disconnected
    }

    /** Immediately cancel the underlying socket (no graceful close handshake). */
    internal fun cancelNow() {
        manuallyClosed = true
        readyGate.completeExceptionally(GatewayRpcException(0, "client cancelled"))
        ws?.cancel()
        ws = null
        _state.value = ConnectionState.Disconnected
    }
}
