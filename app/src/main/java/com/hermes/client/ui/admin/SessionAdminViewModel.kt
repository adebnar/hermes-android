package com.hermes.client.ui.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.network.SearchResultDto
import com.hermes.client.data.network.SessionStatsDto
import com.hermes.client.data.repository.ProfileManager
import com.hermes.client.data.repository.SessionRepository
import com.hermes.client.domain.Session
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SessionAdminUiState(
    val stats: SessionStatsDto? = null,
    val query: String = "",
    val results: List<SearchResultDto> = emptyList(),
    val archived: List<Session> = emptyList(),
    val searching: Boolean = false,
)

@HiltViewModel
class SessionAdminViewModel @Inject constructor(
    private val sessions: SessionRepository,
    private val profileManager: ProfileManager,
) : ViewModel() {
    private val _state = MutableStateFlow(SessionAdminUiState())
    val state: StateFlow<SessionAdminUiState> = _state.asStateFlow()

    private val profile: String? get() = profileManager.active.value

    init { load() }

    fun load() = viewModelScope.launch {
        runCatching { sessions.stats(profile) }.onSuccess { _state.value = _state.value.copy(stats = it) }
        runCatching { sessions.archived(profile) }.onSuccess { _state.value = _state.value.copy(archived = it) }
    }

    fun onQueryChange(q: String) { _state.value = _state.value.copy(query = q) }

    fun search() = viewModelScope.launch {
        val q = _state.value.query.trim()
        if (q.isBlank()) { _state.value = _state.value.copy(results = emptyList()); return@launch }
        _state.value = _state.value.copy(searching = true)
        runCatching { sessions.search(q, profile) }
            .onSuccess { _state.value = _state.value.copy(results = it, searching = false) }
            .onFailure { _state.value = _state.value.copy(results = emptyList(), searching = false) }
    }
}
