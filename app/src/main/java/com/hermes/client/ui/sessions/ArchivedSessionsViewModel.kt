package com.hermes.client.ui.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.network.HermesApiException
import com.hermes.client.data.repository.ProfileManager
import com.hermes.client.data.repository.SessionRepository
import com.hermes.client.domain.Session
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ArchivedUiState(
    val sessions: List<Session> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val unauthorized: Boolean = false,
)

@HiltViewModel
class ArchivedSessionsViewModel @Inject constructor(
    private val sessions: SessionRepository,
    private val profileManager: ProfileManager,
) : ViewModel() {
    private val _state = MutableStateFlow(ArchivedUiState())
    val state: StateFlow<ArchivedUiState> = _state.asStateFlow()

    val activeProfile: StateFlow<String?> = profileManager.active

    init {
        // Scoped to the active profile, matching the main list — reload when the profile changes.
        viewModelScope.launch { profileManager.active.collect { refresh() } }
    }

    fun refresh() = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true, error = null, unauthorized = false)
        try {
            val active = profileManager.active.value
            val all = sessions.archivedAllProfiles()
            val list = if (active.isNullOrBlank()) all else all.filter { it.profile == active }
            _state.value = ArchivedUiState(sessions = list)
        } catch (e: HermesApiException) {
            if (e.code == 401) _state.value = ArchivedUiState(unauthorized = true)
            else _state.value = ArchivedUiState(error = e.message ?: "Failed to load")
        } catch (e: Exception) {
            _state.value = ArchivedUiState(error = e.message ?: "Failed to load")
        }
    }

    /** Restore an archived session to the active list, then refresh so it leaves this view. */
    fun unarchive(session: Session) = viewModelScope.launch {
        runCatching { sessions.archive(session.id, archived = false, session.profile) }.onSuccess { refresh() }
    }

    fun delete(session: Session) = viewModelScope.launch {
        runCatching { sessions.delete(session.id, session.profile) }.onSuccess { refresh() }
    }
}
