package com.hermes.client.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.auth.CredentialStore
import com.hermes.client.data.auth.GatewayConfig
import com.hermes.client.data.network.HermesRestApi
import com.hermes.client.data.repository.ChatRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ConnectionUiState(
    val url: String = "",
    val token: String = "",
    val testResult: String? = null,
    val saved: Boolean = false,
)

/**
 * View/update the gateway URL + token after first-run setup. Mirrors SetupViewModel but is
 * reachable from Settings and reconnects the live socket on save so a changed server/token
 * takes effect without restarting the app.
 */
@HiltViewModel
class ConnectionSettingsViewModel @Inject constructor(
    private val store: CredentialStore,
    private val rest: HermesRestApi,
    private val chat: ChatRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(
        store.load()?.let { ConnectionUiState(url = it.baseUrl, token = it.token) } ?: ConnectionUiState(),
    )
    val state: StateFlow<ConnectionUiState> = _state.asStateFlow()

    fun onUrlChange(v: String) {
        _state.value = _state.value.copy(url = v.trim(), saved = false, testResult = null)
    }

    fun onTokenChange(v: String) {
        _state.value = _state.value.copy(token = v.trim(), saved = false, testResult = null)
    }

    /** Test connectivity with the entered values WITHOUT persisting them. */
    fun test() = viewModelScope.launch {
        val ok = rest.statusFor(_state.value.url, _state.value.token)
        _state.value = _state.value.copy(testResult = if (ok) "Connected ✓" else "Unreachable")
    }

    /** Persist the new server/token, then reconnect — the socket URL is derived from config. */
    fun save() {
        store.save(GatewayConfig(_state.value.url.trim(), _state.value.token.trim()))
        runCatching { chat.reconnect() }
        _state.value = _state.value.copy(saved = true, testResult = "Saved — reconnecting")
    }
}
