package com.hermes.client.ui.cron

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.repository.ProfileManager
import com.hermes.client.data.repository.ToolsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CronEditState(
    val name: String = "",
    val schedule: String = "",
    val prompt: String = "",
    val isNew: Boolean = true,
    val loading: Boolean = false,
    val saved: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class CronEditViewModel @Inject constructor(
    private val tools: ToolsRepository,
    private val profileManager: ProfileManager,
) : ViewModel() {
    private val _state = MutableStateFlow(CronEditState())
    val state: StateFlow<CronEditState> = _state.asStateFlow()
    private var jobId: String = "new"
    private val profile: String? get() = profileManager.active.value

    fun load(id: String) {
        jobId = id
        if (id == "new") { _state.value = CronEditState(isNew = true); return }
        _state.value = _state.value.copy(loading = true, isNew = false)
        viewModelScope.launch {
            runCatching { tools.cronJob(id, profile) }
                .onSuccess { job ->
                    _state.value = CronEditState(
                        name = job.name ?: "",
                        schedule = job.scheduleText,
                        prompt = job.prompt ?: "",
                        isNew = false,
                        loading = false,
                    )
                }
                .onFailure { _state.value = _state.value.copy(loading = false, message = "Load failed: ${it.message}") }
        }
    }

    fun setName(v: String) { _state.value = _state.value.copy(name = v) }
    fun setSchedule(v: String) { _state.value = _state.value.copy(schedule = v) }
    fun setPrompt(v: String) { _state.value = _state.value.copy(prompt = v) }

    fun save() = viewModelScope.launch {
        val s = _state.value
        if (s.prompt.isBlank() || s.schedule.isBlank()) {
            _state.value = s.copy(message = "Schedule and prompt are required"); return@launch
        }
        runCatching {
            if (s.isNew) tools.createCron(s.prompt, s.schedule, s.name, profile)
            else tools.updateCron(jobId, s.prompt, s.schedule, s.name, profile)
        }.onSuccess { _state.value = s.copy(saved = true) }
            .onFailure { _state.value = s.copy(message = "Save failed: ${it.message}") }
    }

    fun clearMessage() { _state.value = _state.value.copy(message = null) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CronEditScreen(
    jobId: String,
    onDone: () -> Unit,
    vm: CronEditViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(jobId) { vm.load(jobId) }
    LaunchedEffect(state.saved) { if (state.saved) onDone() }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); vm.clearMessage() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.isNew) "New cron job" else "Edit cron job") },
                navigationIcon = { IconButton(onClick = onDone) { Text("‹") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            OutlinedTextField(state.name, vm::setName, label = { Text("Name (optional)") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(state.schedule, vm::setSchedule, label = { Text("Schedule (cron, e.g. 0 9 * * *)") },
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            Text("min hour day month weekday", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            OutlinedTextField(state.prompt, vm::setPrompt, label = { Text("Prompt") },
                minLines = 5, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            Button(onClick = { vm.save() }, modifier = Modifier.padding(top = 16.dp)) {
                Text(if (state.isNew) "Create" else "Save")
            }
        }
    }
}
