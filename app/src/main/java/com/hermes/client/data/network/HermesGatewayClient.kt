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
    private var attempt = 0

    fun connect() {
        manuallyClosed = false
        openSocket()
    }

    protected fun openSocket() {
        _state.value = ConnectionState.Connecting
        val request = Request.Builder().url(wsUrlProvider()).build()
        ws = okHttp.newWebSocket(request, listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            attempt = 0
            _state.value = ConnectionState.Connected
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            text.lineSequence().filter { it.isNotBlank() }.forEach { line ->
                when (val msg = parseInbound(json, line)) {
                    is RpcResult -> pending.remove(msg.id)?.complete(msg.result)
                    is RpcErrorReply -> pending.remove(msg.id)
                        ?.completeExceptionally(GatewayRpcException(msg.error.code, msg.error.message))
                    is RpcEvent -> _events.tryEmit(msg.event)
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
        failAllPending(reason)
        if (manuallyClosed) {
            _state.value = ConnectionState.Disconnected
            return
        }
        _state.value = ConnectionState.Reconnecting
        val delayMs = backoff.delayFor(attempt++)
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
        failAllPending("client closing")
        ws?.close(1000, "client closing")
        ws = null
        _state.value = ConnectionState.Disconnected
    }

    /** Immediately cancel the underlying socket (no graceful close handshake). */
    internal fun cancelNow() {
        manuallyClosed = true
        ws?.cancel()
        ws = null
        _state.value = ConnectionState.Disconnected
    }
}
