package com.hermes.client.ui.cron
import androidx.compose.material.icons.automirrored.rounded.ArrowBack

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
    val schedule: Schedule = Schedule.Daily(9, 0),
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
                        schedule = parseCron(job.schedule?.expr ?: job.scheduleText),
                        prompt = job.prompt ?: "",
                        isNew = false,
                        loading = false,
                    )
                }
                .onFailure { _state.value = _state.value.copy(loading = false, message = "Load failed: ${it.message}") }
        }
    }

    fun setName(v: String) { _state.value = _state.value.copy(name = v) }
    fun setSchedule(v: Schedule) { _state.value = _state.value.copy(schedule = v) }
    fun setPrompt(v: String) { _state.value = _state.value.copy(prompt = v) }

    fun save() = viewModelScope.launch {
        val s = _state.value
        val advancedInvalid = s.schedule is Schedule.Advanced && !isValidCron((s.schedule as Schedule.Advanced).expr)
        if (s.prompt.isBlank() || advancedInvalid) {
            _state.value = s.copy(message = if (s.prompt.isBlank()) "Prompt is required" else "Schedule is not a valid cron expression")
            return@launch
        }
        val cron = s.schedule.toCron()
        runCatching {
            if (s.isNew) tools.createCron(s.prompt, cron, s.name, profile)
            else tools.updateCron(jobId, s.prompt, cron, s.name, profile)
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
            com.hermes.client.ui.components.HermesTopBar(
                title = if (state.isNew) "New cron job" else "Edit cron job",
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        androidx.compose.material3.Icon(
                            androidx.compose.material.icons.Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            OutlinedTextField(state.name, vm::setName, label = { Text("Name (optional)") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(state.schedule.describe(), {}, label = { Text("Schedule (cron, e.g. 0 9 * * *)") },
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), enabled = false)
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
