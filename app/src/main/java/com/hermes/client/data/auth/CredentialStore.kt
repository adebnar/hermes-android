package com.hermes.client.data.auth

data class GatewayConfig(val baseUrl: String, val token: String) {
    /** Derived WebSocket URL, e.g. http://host:9119 -> ws://host:9119/api/ws?token=... */
    val wsUrl: String
        get() {
            val ws = baseUrl.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
            return "${ws.trimEnd('/')}/api/ws?token=$token"
        }
}

interface CredentialStore {
    fun load(): GatewayConfig?
    fun save(config: GatewayConfig)
    fun clear()
}
