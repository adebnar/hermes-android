package com.hermes.client.data.network

import com.hermes.client.data.diagnostics.DebugLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
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
    // suspend so gated mode can fetch a fresh single-use WS ticket (an HTTP round trip) before
    // each connect; loopback mode returns immediately.
    private val wsUrlProvider: suspend () -> String,
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
    // Monotonic socket generation. Each openSocket() bumps it; a socket's callbacks are
    // ignored once a newer socket has been opened, so an in-flight backoff reopen can never
    // race a manual reconnectNow() into two live sockets.
    private val generation = AtomicInteger(0)

    // Readiness gate: awaited by call() before sending RPCs.
    // Recreated (uncompleted) on each openSocket(); completed when gateway.ready arrives;
    // completed exceptionally when socket closes/fails or close() is called.
    @Volatile private var readyGate: CompletableDeferred<Unit> = CompletableDeferred()

    private companion object {
        const val READY_TIMEOUT_MS = 15_000L
    }

    fun connect() {
        manuallyClosed = false
        openSocket()
    }

    protected fun openSocket() {
        val gen = generation.incrementAndGet()
        DebugLog.log("ws", "opening socket (gen=$gen)")
        // Install a fresh, uncompleted readiness gate for this new socket attempt.
        readyGate = CompletableDeferred()
        _state.value = ConnectionState.Connecting
        // Resolve the URL off the calling thread: gated mode mints a WS ticket (HTTP) here. A
        // failure (e.g. login/ticket error) routes through onSocketClosed so backoff retries.
        scope.launch {
            val url = try {
                wsUrlProvider()
            } catch (e: Exception) {
                DebugLog.log("ws", "ws url/ticket failed (gen=$gen): ${e.message}")
                onSocketClosed(gen, e.message ?: "ws url failed")
                return@launch
            }
            // A newer socket may have superseded this one — or the client was closed — while we
            // fetched the ticket. Either way, don't open a now-orphaned socket.
            if (gen != generation.get() || manuallyClosed) return@launch
            val request = Request.Builder().url(url).build()
            ws = okHttp.newWebSocket(request, makeListener(gen))
        }
    }

    /**
     * Force an immediate reconnect, bypassing any pending backoff wait. The previous socket is
     * cancelled; because openSocket() bumps the generation first, the old socket's close callback
     * is ignored and cannot schedule a competing reopen.
     */
    fun reconnectNow() {
        manuallyClosed = false
        attempt.set(0)
        val old = ws
        openSocket()
        old?.cancel()
    }

    private fun makeListener(gen: Int) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            // Do NOT set state to Connected here. Wait for gateway.ready event.
            // Do NOT reset the attempt counter here either.
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (gen != generation.get()) return // superseded socket — drop late frames
            text.lineSequence().filter { it.isNotBlank() }.forEach { line ->
                when (val msg = parseInbound(json, line)) {
                    is RpcResult -> pending.remove(msg.id)?.complete(msg.result)
                    is RpcErrorReply -> {
                        DebugLog.log("ws", "rpc#${msg.id} ← error ${msg.error.code}: ${msg.error.message}")
                        pending.remove(msg.id)
                            ?.completeExceptionally(GatewayRpcException(msg.error.code, msg.error.message))
                    }
                    is RpcEvent -> {
                        // Handle gateway.ready: flip to Connected and open the readiness gate.
                        if (msg.event.type == "gateway.ready") {
                            attempt.set(0)
                            _state.value = ConnectionState.Connected
                            readyGate.complete(Unit)
                        }
                        // Log every event except the high-frequency streaming deltas, so the
                        // diagnostic trail stays readable while still capturing errors,
                        // tool calls, and lifecycle around a failure like "message not found".
                        if (msg.event.type != "message.delta" && msg.event.type != "reasoning.delta") {
                            DebugLog.log("ws", "event ${msg.event.type} session=${msg.event.sessionId ?: "-"}")
                        }
                        _events.tryEmit(msg.event)
                    }
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            onSocketClosed(gen, t.message ?: "connection failed")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            onSocketClosed(gen, reason.ifBlank { "closed" })
        }
    }

    protected open fun onSocketClosed(gen: Int, reason: String) {
        // A newer socket has superseded this one (e.g. reconnectNow()) — ignore its death.
        if (gen != generation.get()) return
        DebugLog.log("ws", "socket closed (gen=$gen): $reason")
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
            if (!manuallyClosed && gen == generation.get()) openSocket()
        }
    }

    private fun failAllPending(reason: String) {
        pending.keys.toList().forEach { id ->
            pending.remove(id)?.completeExceptionally(GatewayRpcException(0, reason))
        }
    }

    suspend fun call(method: String, params: JsonObject): JsonElement {
        // Wait until gateway.ready has been received before sending any RPC.
        // Bounded wait: if the server never sends gateway.ready, throw after READY_TIMEOUT_MS.
        // The await() happens BEFORE registering in `pending`, so a timeout here never leaks
        // a pending entry.
        try {
            withTimeout(READY_TIMEOUT_MS) { readyGate.await() }
        } catch (e: TimeoutCancellationException) {
            throw GatewayRpcException(0, "gateway readiness timeout")
        }
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<JsonElement>()
        pending[id] = deferred
        DebugLog.log("ws", "rpc#$id → $method")
        val sent = ws?.send(RpcRequest(id, method, params).encode(json)) ?: false
        if (!sent) {
            pending.remove(id)
            DebugLog.log("ws", "rpc#$id $method failed: not connected")
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
