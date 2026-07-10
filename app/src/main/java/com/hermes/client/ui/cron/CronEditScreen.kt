package com.hermes.client.ui.cron
import androidx.compose.material.icons.automirrored.rounded.ArrowBack

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
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
        val template = cronTemplate(id)
        if (id == "new" || template != null) {
            _state.value = if (template != null) {
                CronEditState(schedule = template.schedule, prompt = template.prompt, isNew = true)
            } else {
                CronEditState(isNew = true)
            }
            return
        }
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
            Spacer(Modifier.height(8.dp))
            val nowMs = androidx.compose.runtime.remember(state.schedule) { System.currentTimeMillis() }
            ScheduleBuilder(schedule = state.schedule, onChange = vm::setSchedule, nowMs = nowMs)
            OutlinedTextField(state.prompt, vm::setPrompt, label = { Text("Prompt") },
                minLines = 5, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            Button(
                onClick = { vm.save() },
                enabled = state.prompt.isNotBlank() &&
                    (state.schedule !is Schedule.Advanced || isValidCron((state.schedule as Schedule.Advanced).expr)),
                modifier = Modifier.padding(top = 16.dp),
            ) {
                Text(if (state.isNew) "Create" else "Save")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleBuilder(schedule: Schedule, onChange: (Schedule) -> Unit, nowMs: Long) {
    val accent = com.hermes.client.ui.theme.LocalProfileAccent.current
    val kinds = listOf("Hourly", "Daily", "Weekly", "Monthly", "Advanced")
    val current = when (schedule) {
        is Schedule.Hourly -> 0; is Schedule.Daily -> 1; is Schedule.Weekly -> 2
        is Schedule.Monthly -> 3; is Schedule.Advanced -> 4
    }
    // time carried across kind switches
    val (h, m) = when (schedule) {
        is Schedule.Daily -> schedule.hour to schedule.minute
        is Schedule.Weekly -> schedule.hour to schedule.minute
        is Schedule.Monthly -> schedule.hour to schedule.minute
        is Schedule.Hourly -> 9 to schedule.minute
        is Schedule.Advanced -> 9 to 0
    }
    Column(Modifier.fillMaxWidth()) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            kinds.forEachIndexed { i, label ->
                SegmentedButton(
                    selected = current == i,
                    onClick = {
                        onChange(when (i) {
                            0 -> Schedule.Hourly(m.coerceIn(0, 59))
                            1 -> Schedule.Daily(h, m)
                            2 -> Schedule.Weekly(setOf(Weekday.MON), h, m)
                            3 -> Schedule.Monthly(1, h, m)
                            else -> Schedule.Advanced(schedule.toCron())
                        })
                    },
                    shape = SegmentedButtonDefaults.itemShape(i, kinds.size),
                    colors = SegmentedButtonDefaults.colors(activeContainerColor = accent.accent, activeContentColor = accent.onAccent),
                ) { Text(label, maxLines = 1) }
            }
        }
        Spacer(Modifier.height(12.dp))
        when (schedule) {
            is Schedule.Hourly -> MinutePicker(schedule.minute) { onChange(schedule.copy(minute = it)) }
            is Schedule.Daily -> TimeRow(schedule.hour, schedule.minute) { hh, mm -> onChange(schedule.copy(hour = hh, minute = mm)) }
            is Schedule.Weekly -> {
                WeekdayChips(schedule.days) { onChange(schedule.copy(days = it)) }
                Spacer(Modifier.height(8.dp))
                TimeRow(schedule.hour, schedule.minute) { hh, mm -> onChange(schedule.copy(hour = hh, minute = mm)) }
            }
            is Schedule.Monthly -> {
                DayOfMonthPicker(schedule.dayOfMonth) { onChange(schedule.copy(dayOfMonth = it)) }
                Spacer(Modifier.height(8.dp))
                TimeRow(schedule.hour, schedule.minute) { hh, mm -> onChange(schedule.copy(hour = hh, minute = mm)) }
            }
            is Schedule.Advanced -> {
                val valid = isValidCron(schedule.expr)
                OutlinedTextField(
                    schedule.expr, { onChange(Schedule.Advanced(it)) },
                    label = { Text("Schedule (cron, e.g. 0 9 * * *)") }, singleLine = true,
                    isError = schedule.expr.isNotBlank() && !valid, modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    if (schedule.expr.isNotBlank() && !valid) "Not a valid 5-field cron expression" else "min hour day month weekday",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (schedule.expr.isNotBlank() && !valid) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        val next = schedule.nextRun(nowMs)?.let { epochMs ->
            " · Next: " + com.hermes.client.ui.util.formatIso(
                java.time.Instant.ofEpochMilli(epochMs).atZone(java.time.ZoneId.systemDefault()).toOffsetDateTime().toString()
            )
        }.orEmpty()
        Text(schedule.describe() + next, style = MaterialTheme.typography.bodyMedium, color = accent.accent)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MinutePicker(minute: Int, onPick: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = ":%02d".format(minute),
            onValueChange = {},
            readOnly = true,
            label = { Text("Minute") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, true).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            (0..59).forEach { minuteOption ->
                DropdownMenuItem(
                    text = { Text(":%02d".format(minuteOption)) },
                    onClick = { onPick(minuteOption); expanded = false },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayOfMonthPicker(day: Int, onPick: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = day.toString(),
            onValueChange = {},
            readOnly = true,
            label = { Text("Day of month") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, true).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            (1..28).forEach { dayOption ->
                DropdownMenuItem(
                    text = { Text(dayOption.toString()) },
                    onClick = { onPick(dayOption); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun WeekdayChips(days: Set<Weekday>, onChange: (Set<Weekday>) -> Unit) {
    val accent = com.hermes.client.ui.theme.LocalProfileAccent.current
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Weekday.entries.forEach { day ->
            val selected = day in days
            FilterChip(
                selected = selected,
                onClick = {
                    val next = if (selected) days - day else days + day
                    if (next.isNotEmpty()) onChange(next)
                },
                label = { Text(day.short) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = accent.accent,
                    selectedLabelColor = accent.onAccent,
                ),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeRow(hour: Int, minute: Int, onPick: (Int, Int) -> Unit) {
    val accent = com.hermes.client.ui.theme.LocalProfileAccent.current
    var showDialog by remember { mutableStateOf(false) }
    OutlinedButton(
        onClick = { showDialog = true },
        colors = ButtonDefaults.outlinedButtonColors(contentColor = accent.accent),
    ) {
        Text("At %02d:%02d".format(hour, minute))
    }
    if (showDialog) {
        val state = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { showDialog = false },
            confirmButton = {
                TextButton(onClick = { onPick(state.hour, state.minute); showDialog = false }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) { Text("Cancel") }
            },
            text = { TimePicker(state = state) },
        )
    }
}
