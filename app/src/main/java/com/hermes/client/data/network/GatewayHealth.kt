package com.hermes.client.data.network

/**
 * Backend health, distinct from the WebSocket [ConnectionState]. Sourced from the device's
 * connectivity plus the gateway's public `/api/status`.
 */
sealed interface GatewayHealth {
    /** Before the first probe completes — renders nothing. */
    data object Unknown : GatewayHealth

    /** `/api/status` returned 2xx. [running] mirrors `gateway_running`. */
    data class Healthy(val version: String?, val running: Boolean, val latencyMs: Long?) : GatewayHealth

    /** The device has no network — the phone is offline, not the gateway. */
    data object DeviceOffline : GatewayHealth

    /** Network is up but `/api/status` failed (timeout, refused, non-2xx). */
    data class GatewayUnreachable(val detail: String?) : GatewayHealth
}

/** True when the down-strip and the You-tab badge should show. */
fun GatewayHealth.isUnhealthy(): Boolean =
    this is GatewayHealth.DeviceOffline || this is GatewayHealth.GatewayUnreachable
