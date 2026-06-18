package com.hermes.client.data.auth

data class GatewayConfig(val baseUrl: String, val token: String) {
    /**
     * Derived WebSocket URL, e.g. http://host:9119 -> ws://host:9119/api/ws?token=...
     * The token query param is omitted when no token is configured (gateways that
     * don't enforce a session token, e.g. trusted tailnet setups).
     */
    val wsUrl: String
        get() {
            val ws = baseUrl.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
            val base = "${ws.trimEnd('/')}/api/ws"
            return if (token.isBlank()) base else "$base?token=$token"
        }
}

interface CredentialStore {
    fun load(): GatewayConfig?
    fun save(config: GatewayConfig)
    fun clear()
}
