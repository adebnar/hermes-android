package com.hermes.client.ui.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.network.HermesApiException
import com.hermes.client.data.network.SearchResultDto
import com.hermes.client.data.repository.ChatRepository
import com.hermes.client.data.repository.PinStore
import com.hermes.client.data.repository.ProfileManager
import com.hermes.client.data.repository.SessionRepository
import com.hermes.client.domain.Session
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
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
    private val pinStore: PinStore,
) : ViewModel() {
    private val _state = MutableStateFlow(SessionsUiState())
    val state: StateFlow<SessionsUiState> = _state.asStateFlow()

    /** The active profile, shown as a subtitle so the tenant context is always visible. */
    val activeProfile: StateFlow<String?> = profileManager.active

    /** Session ids pinned in the current profile (device-local). */
    val pinnedIds: StateFlow<Set<String>> =
        combine(pinStore.pinned, profileManager.active) { tokens, profile ->
            val prefix = "${profile ?: "default"}/"
            tokens.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }.toSet()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

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

    // ── Search ──────────────────────────────────────────────────────────────────────────
    // The title filter is applied to [state.sessions] in the UI as the query changes (instant,
    // offline). A message-content search hits the gateway only on the explicit Search action.
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _messageResults = MutableStateFlow<List<SearchResultDto>>(emptyList())
    val messageResults: StateFlow<List<SearchResultDto>> = _messageResults.asStateFlow()

    fun onQueryChange(q: String) {
        _query.value = q
        if (q.isBlank()) _messageResults.value = emptyList()
    }

    private var searchJob: kotlinx.coroutines.Job? = null

    /** Full-text search of message content across this profile's sessions. Cancels any in-flight
     *  search first so a slow older query can't overwrite newer results. */
    fun searchMessages() {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            val q = _query.value.trim()
            if (q.isBlank()) { _messageResults.value = emptyList(); return@launch }
            runCatching { sessions.search(q, profileManager.active.value) }
                .onSuccess { _messageResults.value = it }
                .onFailure { _messageResults.value = emptyList() }
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

    fun togglePin(sessionId: String) = viewModelScope.launch {
        pinStore.toggle(PinStore.token(profileManager.active.value, sessionId))
    }
}
