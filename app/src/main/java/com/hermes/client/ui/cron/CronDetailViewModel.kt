package com.hermes.client.ui.cron

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.network.CronJobDto
import com.hermes.client.data.network.CronRunDto
import com.hermes.client.data.repository.ProfileManager
import com.hermes.client.data.repository.ToolsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CronDetailUiState(
    val job: CronJobDto? = null,
    val runs: List<CronRunDto> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val message: String? = null,
    val deleted: Boolean = false,
)

@HiltViewModel
class CronDetailViewModel @Inject constructor(
    private val tools: ToolsRepository,
    private val profileManager: ProfileManager,
) : ViewModel() {
    private val _state = MutableStateFlow(CronDetailUiState())
    val state: StateFlow<CronDetailUiState> = _state.asStateFlow()

    private var jobId: String = ""
    private val profile: String? get() = profileManager.active.value

    fun load(id: String) {
        jobId = id
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            val job = runCatching { tools.cronJob(id, profile) }.getOrNull()
            val runs = runCatching { tools.cronRuns(id, profile) }.getOrNull() ?: emptyList()
            _state.value = if (job == null) {
                _state.value.copy(loading = false, error = "Failed to load cron job")
            } else {
                _state.value.copy(job = job, runs = runs, loading = false)
            }
        }
    }

    private fun act(label: String, block: suspend () -> Unit) = viewModelScope.launch {
        runCatching { block() }
            .onSuccess { _state.value = _state.value.copy(message = label); load(jobId) }
            .onFailure { _state.value = _state.value.copy(message = "$label failed: ${it.message}") }
    }

    fun pause() = act("Paused") { tools.pauseCron(jobId, profile) }
    fun resume() = act("Resumed") { tools.resumeCron(jobId, profile) }
    fun trigger() = act("Triggered") { tools.triggerCron(jobId, profile) }

    fun delete() = viewModelScope.launch {
        runCatching { tools.deleteCron(jobId, profile) }
            .onSuccess { _state.value = _state.value.copy(deleted = true) }
            .onFailure { _state.value = _state.value.copy(message = "Delete failed: ${it.message}") }
    }

    fun clearMessage() { _state.value = _state.value.copy(message = null) }
}
