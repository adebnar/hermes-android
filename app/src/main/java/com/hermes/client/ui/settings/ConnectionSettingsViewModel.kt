package com.hermes.client.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.auth.CredentialStore
import com.hermes.client.data.auth.GatewayConfig
import com.hermes.client.data.network.GatedAuth
import com.hermes.client.data.network.HermesRestApi
import com.hermes.client.data.repository.ChatRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ConnectionUiState(
    val url: String = "",
    val token: String = "",
    val username: String = "",
    val password: String = "",
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
    private val gatedAuth: GatedAuth,
) : ViewModel() {
    private val _state = MutableStateFlow(
        store.load()?.let {
            ConnectionUiState(url = it.baseUrl, token = it.token, username = it.username, password = it.password)
        } ?: ConnectionUiState(),
    )
    val state: StateFlow<ConnectionUiState> = _state.asStateFlow()

    fun onUrlChange(v: String) { _state.value = _state.value.copy(url = v.trim(), saved = false, testResult = null) }
    fun onTokenChange(v: String) { _state.value = _state.value.copy(token = v.trim(), saved = false, testResult = null) }
    fun onUsernameChange(v: String) { _state.value = _state.value.copy(username = v.trim(), saved = false, testResult = null) }
    fun onPasswordChange(v: String) { _state.value = _state.value.copy(password = v, saved = false, testResult = null) }

    /** Test with the entered values WITHOUT persisting: a login probe when a username is set,
     *  otherwise a plain status check. */
    fun test() = viewModelScope.launch {
        val s = _state.value
        val ok = if (s.username.isNotBlank()) {
            withContext(Dispatchers.IO) { gatedAuth.probeLogin(s.url, s.username, s.password) }
        } else {
            rest.statusFor(s.url, s.token)
        }
        _state.value = _state.value.copy(testResult = if (ok) "Connected ✓" else "Failed — check the details")
    }

    /** Persist the new server/credentials, drop any stale session, then reconnect. */
    fun save() {
        val s = _state.value
        store.save(GatewayConfig(s.url.trim(), s.token.trim(), s.username.trim(), s.password))
        gatedAuth.cookieJar.clear() // force a fresh login with the new credentials
        runCatching { chat.reconnect() }
        _state.value = _state.value.copy(saved = true, testResult = "Saved — reconnecting")
    }
}
