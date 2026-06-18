package com.hermes.client.data.network

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data object Connected : ConnectionState
    data object Reconnecting : ConnectionState
    data class Error(val reason: String) : ConnectionState
}
