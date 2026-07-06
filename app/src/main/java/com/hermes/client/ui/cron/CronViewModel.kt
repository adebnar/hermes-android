package com.hermes.client.ui.cron

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.network.CronJobDto
import com.hermes.client.data.repository.ProfileManager
import com.hermes.client.data.repository.ToolsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class CronAction { PAUSE, RESUME, RUN }

data class CronUiState(
    val jobs: List<CronJobDto> = emptyList(),
    val profile: String? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val message: String? = null,
)

@HiltViewModel
class CronViewModel @Inject constructor(
    private val tools: ToolsRepository,
    private val profileManager: ProfileManager,
) : ViewModel() {
    private val _state = MutableStateFlow(CronUiState())
    val state: StateFlow<CronUiState> = _state.asStateFlow()

    init {
        // Reload cron jobs whenever the selected profile changes — cron jobs are per-profile.
        viewModelScope.launch { profileManager.active.collect { load() } }
    }

    fun load() = viewModelScope.launch {
        val p = profileManager.active.value
        _state.value = _state.value.copy(loading = true, error = null, profile = p)
        runCatching { tools.cronJobs(p) }
            .onSuccess { _state.value = _state.value.copy(jobs = it, loading = false) }
            .onFailure {
                _state.value = _state.value.copy(loading = false, error = it.message ?: "Failed to load cron jobs")
            }
    }

    fun runAction(jobId: String, name: String, action: CronAction) = viewModelScope.launch {
        val p = _state.value.profile
        val ok = runCatching {
            when (action) {
                CronAction.PAUSE -> tools.pauseCron(jobId, p)
                CronAction.RESUME -> tools.resumeCron(jobId, p)
                CronAction.RUN -> tools.triggerCron(jobId, p)
            }
        }.isSuccess
        val verb = when (action) { CronAction.PAUSE -> "Paused"; CronAction.RESUME -> "Resumed"; CronAction.RUN -> "Triggered" }
        _state.value = _state.value.copy(message = if (ok) "$verb $name" else "Couldn't ${verb.lowercase()} $name")
        if (ok) runCatching { tools.cronJobs(p) }.onSuccess { jobs -> _state.value = _state.value.copy(jobs = jobs) }
    }

    fun clearMessage() { _state.value = _state.value.copy(message = null) }
}
