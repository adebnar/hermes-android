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

data class CronUiState(
    val jobs: List<CronJobDto> = emptyList(),
    val profile: String? = null,
    val loading: Boolean = true,
    val error: String? = null,
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
}
