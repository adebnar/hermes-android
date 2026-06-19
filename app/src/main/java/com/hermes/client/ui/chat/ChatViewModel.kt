package com.hermes.client.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.network.ConnectionState
import com.hermes.client.data.network.HermesApiException
import com.hermes.client.data.network.ModelOptionDto
import com.hermes.client.data.network.ProfileDto
import com.hermes.client.data.repository.ChatRepository
import com.hermes.client.data.repository.ModelRepository
import com.hermes.client.data.repository.ProfileRepository
import com.hermes.client.data.repository.SessionRepository
import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Role
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chat: ChatRepository,
    private val sessions: SessionRepository,
    private val modelRepo: ModelRepository,
    private val profileRepo: ProfileRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState.empty())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    val connectionState: StateFlow<ConnectionState> = chat.connectionState

    // I1: expose 401 unauthorized so the nav layer can route back to Setup
    private val _unauthorized = MutableStateFlow(false)
    val unauthorized: StateFlow<Boolean> = _unauthorized.asStateFlow()

    private val _models = MutableStateFlow<List<ModelOptionDto>>(emptyList())
    val models: StateFlow<List<ModelOptionDto>> = _models.asStateFlow()

    private val _profiles = MutableStateFlow<List<ProfileDto>>(emptyList())
    val profiles: StateFlow<List<ProfileDto>> = _profiles.asStateFlow()

    private var sessionId: String = ""
    private var collectJob: Job? = null
    private var connJob: Job? = null

    fun open(id: String) {
        sessionId = id
        connJob?.cancel()
        viewModelScope.launch {
            try {
                val history = sessions.history(id)
                _state.value = ChatUiState(messages = history)
            } catch (e: HermesApiException) {
                if (e.code == 401) { _unauthorized.value = true; return@launch }
                _state.value = ChatUiState(messages = emptyList())
            } catch (e: Exception) {
                // History load failed (network/parse) — start with an empty thread rather than crash.
                _state.value = ChatUiState(messages = emptyList())
            }
            // resume() returns the live socket handle for this session; switch to it so
            // submit/interrupt and event filtering use the id the gateway actually knows.
            runCatching { chat.resume(id) }.getOrNull()?.let { sessionId = it }
            // Load model options and profiles; failures are non-fatal
            launch { runCatching { _models.value = modelRepo.options() } }
            launch { runCatching { _profiles.value = profileRepo.list() } }
        }
        collectJob?.cancel()
        collectJob = viewModelScope.launch {
            chat.events.filter { it.sessionId == null || it.sessionId == sessionId }
                .onEach { event -> _state.value = reduce(_state.value, event) }
                .collect {}
        }
        // C2 + I3: watch connection transitions
        connJob = viewModelScope.launch {
            var prev: ConnectionState? = null
            chat.connectionState.collect { cur ->
                // I3: entering Reconnecting or Error while generating → mark interrupted
                if ((cur is ConnectionState.Reconnecting || cur is ConnectionState.Error)
                    && _state.value.isGenerating
                ) {
                    _state.value = _state.value.markInterrupted()
                }
                // C2: reconnect cycle completed (Reconnecting → Connected) → re-attach agent stream
                // Guard: prev must be Reconnecting (not null) to skip the very first Connected transition
                if (cur is ConnectionState.Connected && prev is ConnectionState.Reconnecting) {
                    launch { runCatching { chat.resume(sessionId) }.getOrNull()?.let { sessionId = it } }
                }
                prev = cur
            }
        }
    }

    fun send(text: String) {
        if (text.isBlank()) return
        _state.value = _state.value.withUserMessage(text)
        viewModelScope.launch {
            try {
                chat.submit(sessionId, text)
            } catch (e: Exception) {
                // A gateway error (e.g. "session not found") must surface, not crash the app.
                appendError(e.message ?: "Failed to send message")
            }
        }
    }

    fun stop() { viewModelScope.launch { runCatching { chat.interrupt(sessionId) } } }

    /** User tapped "Retry" on the offline banner — force an immediate reconnect. */
    fun reconnect() { runCatching { chat.reconnect() } }

    fun approve(approve: Boolean) {
        _state.value = _state.value.copy(pendingApproval = null)
        viewModelScope.launch { runCatching { chat.respondApproval(sessionId, approve) } }
    }

    fun clarify(answer: String) {
        _state.value = _state.value.copy(pendingClarify = null)
        viewModelScope.launch { runCatching { chat.respondClarify(sessionId, answer) } }
    }

    /** Appends a non-fatal error as a system message and stops the generating spinner. */
    private fun appendError(text: String) {
        _state.value = _state.value.copy(
            messages = _state.value.messages + ChatMessage(
                id = "e-${_state.value.messages.size}",
                role = Role.SYSTEM,
                text = text,
                isError = true,
            ),
            isGenerating = false,
        )
    }

    fun selectModel(provider: String, model: String) {
        viewModelScope.launch { runCatching { modelRepo.set(provider, model) } }
    }

    fun selectProfile(name: String) {
        viewModelScope.launch { runCatching { profileRepo.setActive(name) } }
    }
}
