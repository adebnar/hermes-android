package com.hermes.client.ui.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.network.HermesApiException
import com.hermes.client.data.network.SearchResultDto
import com.hermes.client.data.repository.ChatRepository
import com.hermes.client.data.repository.GroupExpansionStore
import com.hermes.client.data.repository.PinStore
import com.hermes.client.data.repository.ProfileManager
import com.hermes.client.data.repository.SessionRepository
import com.hermes.client.domain.Session
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val groupExpansion: GroupExpansionStore,
) : ViewModel() {
    private val _state = MutableStateFlow(SessionsUiState())
    val state: StateFlow<SessionsUiState> = _state.asStateFlow()

    /** The active profile, shown as a subtitle so the tenant context is always visible. */
    val activeProfile: StateFlow<String?> = profileManager.active

    /**
     * Raw pinned tokens ("<profile>/<sessionId>", device-local). The list spans all profiles, so
     * the UI must test each session against its OWN profile token — not the active profile — or a
     * pin made in another profile would vanish. Pins do not sync to desktop (no gateway pin API).
     */
    val pinnedTokens: StateFlow<Set<String>> =
        pinStore.pinned.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** True if [session] is pinned, keyed by the session's own profile. */
    fun isPinned(session: Session, tokens: Set<String> = pinnedTokens.value): Boolean =
        PinStore.token(session.profile, session.id) in tokens

    /** Keys of currently-collapsed Profile/Workspace groups (device-local; default expanded). */
    val collapsedGroups: StateFlow<Set<String>> =
        groupExpansion.collapsed.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** Collapse or expand a group (profile or workspace) by its [GroupExpansionStore] key. */
    fun toggleGroup(groupKey: String) = viewModelScope.launch { groupExpansion.toggle(groupKey) }

    init {
        chat.connect()
        viewModelScope.launch { profileManager.refresh() }
        // The list now spans all profiles (desktop mirror), so it no longer reloads on a profile
        // switch — the contents are identical regardless of which profile is active. Load once;
        // the screen's ON_RESUME effect refreshes after creating/updating a session in a chat.
        refresh()
    }

    fun refresh() = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true, error = null, unauthorized = false)
        try {
            // Cross-profile: every session carries its true profile, so the app no longer guesses
            // the profile from the active selection (the root cause of "wrong profile shown").
            val list = sessions.listAllProfiles()
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

    /** Pin/unpin keyed by the session's OWN profile, so it works regardless of the active one. */
    fun togglePin(session: Session) = viewModelScope.launch {
        pinStore.toggle(PinStore.token(session.profile, session.id))
    }
}
