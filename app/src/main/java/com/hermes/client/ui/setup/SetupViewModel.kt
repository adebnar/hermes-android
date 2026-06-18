package com.hermes.client.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.auth.CredentialStore
import com.hermes.client.data.auth.GatewayConfig
import com.hermes.client.data.network.HermesRestApi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SetupUiState(
    val url: String = "",
    val token: String = "",
    val testResult: String? = null,
    val saved: Boolean = false,
)

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val store: CredentialStore,
    private val rest: HermesRestApi,
) : ViewModel() {
    private val _state = MutableStateFlow(
        store.load()?.let { SetupUiState(url = it.baseUrl, token = it.token) } ?: SetupUiState()
    )
    val state: StateFlow<SetupUiState> = _state.asStateFlow()

    fun onUrlChange(v: String) { _state.value = _state.value.copy(url = v.trim()) }
    fun onTokenChange(v: String) { _state.value = _state.value.copy(token = v.trim()) }

    // T10b: test() must NOT persist unverified credentials — use statusFor() with transient values
    fun test() = viewModelScope.launch {
        val ok = rest.statusFor(_state.value.url, _state.value.token)
        _state.value = _state.value.copy(testResult = if (ok) "Connected" else "Unreachable")
    }

    fun save() {
        store.save(GatewayConfig(_state.value.url, _state.value.token))
        _state.value = _state.value.copy(saved = true)
    }
}
