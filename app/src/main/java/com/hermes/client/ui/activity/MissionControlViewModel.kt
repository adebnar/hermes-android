package com.hermes.client.ui.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.network.HermesApiException
import com.hermes.client.data.repository.ProfileManager
import com.hermes.client.data.repository.SessionRepository
import com.hermes.client.data.repository.ToolsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MissionControlState(
    val sections: List<ActivitySection> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val unauthorized: Boolean = false,
)

/**
 * Mission Control feed for the active profile: merges recent/live conversations with cron
 * (upcoming next-runs + recent runs) into time-grouped sections. Cron is fetched defensively so a
 * cron-side failure still leaves the conversation feed intact.
 */
@HiltViewModel
class MissionControlViewModel @Inject constructor(
    private val sessions: SessionRepository,
    private val tools: ToolsRepository,
    private val profileManager: ProfileManager,
) : ViewModel() {
    private val _state = MutableStateFlow(MissionControlState())
    val state: StateFlow<MissionControlState> = _state.asStateFlow()

    val activeProfile: StateFlow<String?> = profileManager.active

    init {
        viewModelScope.launch { profileManager.active.collect { refresh() } }
    }

    fun refresh() = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true, error = null, unauthorized = false)
        try {
            val active = profileManager.active.value
            val all = sessions.listAllProfiles()
            val scoped = if (active.isNullOrBlank()) all else all.filter { it.profile == active }
            // A cron failure (e.g. profile without cron) must not blank the whole feed.
            val crons = runCatching { tools.cronJobs(active) }.getOrDefault(emptyList())
            val items = sessionsToActivity(scoped) + cronsToActivity(crons)
            _state.value = MissionControlState(sections = groupActivity(items, System.currentTimeMillis()))
        } catch (e: HermesApiException) {
            if (e.code == 401) _state.value = MissionControlState(unauthorized = true)
            else _state.value = MissionControlState(error = e.message ?: "Failed to load")
        } catch (e: Exception) {
            _state.value = MissionControlState(error = e.message ?: "Failed to load")
        }
    }
}
