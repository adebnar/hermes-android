package com.hermes.client.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout

/** Abstraction over Android connectivity so the monitor is unit-testable. */
interface ConnectivityChecker {
    /** True when the device has a validated, internet-capable network. */
    fun isOnline(): Boolean
}

class AndroidConnectivityChecker(private val context: Context) : ConnectivityChecker {
    override fun isOnline(): Boolean {
        // If we can't read connectivity, assume online rather than false-flag DeviceOffline —
        // the /api/status probe is then the source of truth.
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}

/**
 * Proactively tracks whether the self-hosted gateway is reachable, exposed as [health].
 * Probes device connectivity first (→ [GatewayHealth.DeviceOffline]) then the public
 * `/api/status` endpoint, with one immediate retry as debounce so a transient blip does not
 * flash the down-strip. Probing runs only while the app is foregrounded (see [startForeground]).
 */
class GatewayHealthMonitor(
    private val api: HermesRestApi,
    private val connectivity: ConnectivityChecker,
    private val connectionState: StateFlow<ConnectionState>,
    private val scope: CoroutineScope,
) {
    private val _health = MutableStateFlow<GatewayHealth>(GatewayHealth.Unknown)
    val health: StateFlow<GatewayHealth> = _health.asStateFlow()

    private val probeGuard = Mutex()
    private var periodicJob: Job? = null

    init {
        // A dropped/errored socket is an early hint the backend may be gone — re-probe promptly.
        // probe()'s tryLock coalesces this with any in-flight probe.
        scope.launch {
            connectionState.collect { st ->
                if (st is ConnectionState.Error || st is ConnectionState.Disconnected) probe()
            }
        }
    }

    /** Run one health probe, coalescing with any probe already in flight. */
    suspend fun probe() {
        if (!probeGuard.tryLock()) return
        try {
            _health.value = evaluate()
        } finally {
            probeGuard.unlock()
        }
    }

    private suspend fun evaluate(): GatewayHealth {
        if (!connectivity.isOnline()) return GatewayHealth.DeviceOffline
        // First attempt; on a retryable failure (null) try once more before declaring the gateway down.
        return attemptStatus() ?: attemptStatus() ?: GatewayHealth.GatewayUnreachable("unreachable")
    }

    /** Terminal state on a definitive answer (healthy / unauthorized), or null for a retryable failure. */
    private suspend fun attemptStatus(): GatewayHealth? {
        val start = System.nanoTime()
        return try {
            val dto = withTimeout(PROBE_TIMEOUT_MS) { api.gatewayStatus() }
            val latencyMs = (System.nanoTime() - start) / 1_000_000
            GatewayHealth.Healthy(version = dto.version, running = dto.gatewayRunning, latencyMs = latencyMs)
        } catch (e: HermesApiException) {
            // 401 is a definitive answer (reachable but unauthorized) — do not retry.
            if (e.code == 401) GatewayHealth.GatewayUnreachable("unauthorized") else null
        } catch (e: TimeoutCancellationException) {
            null // probe timed out — retryable
        } catch (e: CancellationException) {
            throw e // genuine cancellation (e.g. stopForeground) — never swallow
        } catch (e: Exception) {
            null // IO / other — retryable
        }
    }

    /** Fire an immediate probe (the sheet's Re-check button). */
    fun recheck() {
        scope.launch { probe() }
    }

    /** Begin foreground probing: probe now, then every [PROBE_INTERVAL_MS]. Idempotent. */
    fun startForeground() {
        if (periodicJob?.isActive == true) return
        periodicJob = scope.launch {
            while (true) {
                probe()
                delay(PROBE_INTERVAL_MS)
            }
        }
    }

    /** Stop foreground probing (app backgrounded). */
    fun stopForeground() {
        periodicJob?.cancel()
        periodicJob = null
    }

    companion object {
        const val PROBE_TIMEOUT_MS = 5_000L
        const val PROBE_INTERVAL_MS = 30_000L
    }
}
