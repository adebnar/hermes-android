package com.hermes.client.ui.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.network.HermesApiException
import com.hermes.client.data.repository.ChatRepository
import com.hermes.client.data.repository.ProfileManager
import com.hermes.client.data.repository.SessionRepository
import com.hermes.client.domain.Session
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

import kotlinx.coroutines.launch
import javax.inject.Inject

data class SessionsUiState(
    val sessions: List<Session> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    // I1: true when the server returned 401 — nav should route to Setup
    val unauthorized: Boolean = false,
)

@HiltViewModel
class SessionsViewModel @Inject constructor(
    private val sessions: SessionRepository,
    private val chat: ChatRepository,
    private val profileManager: ProfileManager,
) : ViewModel() {
    private val _state = MutableStateFlow(SessionsUiState())
    val state: StateFlow<SessionsUiState> = _state.asStateFlow()

    /** The active profile, shown as a subtitle so the tenant context is always visible. */
    val activeProfile: StateFlow<String?> = profileManager.active

    init {
        chat.connect()
        viewModelScope.launch { profileManager.refresh() }
        // Reload this profile's sessions whenever the selected profile changes (including the
        // first value once it's loaded). Sessions are scoped server-side via ?profile=, so the
        // list always reflects the profile picked in the drawer.
        viewModelScope.launch {
            profileManager.active.collect { refresh() }
        }
    }

    fun refresh() = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true, error = null, unauthorized = false)
        try {
            val list = sessions.list(profileManager.active.value)
            _state.value = SessionsUiState(sessions = list)
        } catch (e: HermesApiException) {
            if (e.code == 401) {
                _state.value = SessionsUiState(unauthorized = true)
            } else {
                _state.value = SessionsUiState(error = e.message ?: "Failed to load")
            }
        } catch (e: Exception) {
            _state.value = SessionsUiState(error = e.message ?: "Failed to load")
        }
    }

    /** Returns the new session id, or null if creation failed (so the UI doesn't crash). */
    suspend fun createSession(): String? = runCatching { chat.createSession() }.getOrNull()

    fun rename(sessionId: String, title: String) = viewModelScope.launch {
        runCatching { sessions.rename(sessionId, title) }.onSuccess { refresh() }
    }

    fun archive(sessionId: String) = viewModelScope.launch {
        // Archiving removes it from the default (archived=exclude) list.
        runCatching { sessions.archive(sessionId, archived = true) }.onSuccess { refresh() }
    }

    fun delete(sessionId: String) = viewModelScope.launch {
        runCatching { sessions.delete(sessionId) }.onSuccess { refresh() }
    }
}
