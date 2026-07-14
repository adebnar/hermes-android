package com.hermes.client.ui.settings
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
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.repository.ConfigRepository
import com.hermes.client.data.repository.ProfileManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

data class MemorySettingsState(
    val memoryEnabled: Boolean = true,
    val userProfileEnabled: Boolean = true,
    val writeApproval: Boolean = false,
    val memoryCharLimit: Int = 0,
    val userCharLimit: Int = 0,
    val defaultModel: String = "—",
    val loading: Boolean = true,
    val error: String? = null,
    val message: String? = null,
)

@HiltViewModel
class MemorySettingsViewModel @Inject constructor(
    private val config: ConfigRepository,
    private val profileManager: ProfileManager,
) : ViewModel() {
    private val _state = MutableStateFlow(MemorySettingsState())
    val state: StateFlow<MemorySettingsState> = _state.asStateFlow()
    private val profile: String? get() = profileManager.active.value

    init { load() }

    fun load() = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true, error = null)
        runCatching { config.get(profile) }
            .onSuccess { cfg ->
                val mem = (cfg["memory"] as? JsonObject)
                fun b(k: String, d: Boolean) = mem?.get(k)?.jsonPrimitive?.booleanOrNull ?: d
                fun i(k: String, d: Int) = mem?.get(k)?.jsonPrimitive?.intOrNull ?: d
                _state.value = MemorySettingsState(
                    memoryEnabled = b("memory_enabled", true),
                    userProfileEnabled = b("user_profile_enabled", true),
                    writeApproval = b("write_approval", false),
                    memoryCharLimit = i("memory_char_limit", 0),
                    userCharLimit = i("user_char_limit", 0),
                    defaultModel = cfg["model"]?.jsonPrimitive?.content ?: "—",
                    loading = false,
                )
            }
            .onFailure { _state.value = MemorySettingsState(loading = false, error = it.message ?: "Failed to load") }
    }

    /** Whole-config GET-modify-PUT: only the named memory field changes; everything else is preserved. */
    private fun updateMemory(vararg changes: Pair<String, JsonPrimitive>) = viewModelScope.launch {
        runCatching {
            config.update(profile) { cfg ->
                val mem = (cfg["memory"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
                changes.forEach { (k, v) -> mem[k] = v }
                cfg["memory"] = JsonObject(mem)
            }
        }.onSuccess { _state.value = _state.value.copy(message = "Saved"); load() }
            .onFailure { _state.value = _state.value.copy(message = "Save failed: ${it.message}") }
    }

    fun setMemoryEnabled(b: Boolean) { _state.value = _state.value.copy(memoryEnabled = b); updateMemory("memory_enabled" to JsonPrimitive(b)) }
    fun setUserProfileEnabled(b: Boolean) { _state.value = _state.value.copy(userProfileEnabled = b); updateMemory("user_profile_enabled" to JsonPrimitive(b)) }
    fun setWriteApproval(b: Boolean) { _state.value = _state.value.copy(writeApproval = b); updateMemory("write_approval" to JsonPrimitive(b)) }
    fun saveBudgets(memBudget: Int, userBudget: Int) =
        updateMemory("memory_char_limit" to JsonPrimitive(memBudget), "user_char_limit" to JsonPrimitive(userBudget))

    fun clearMessage() { _state.value = _state.value.copy(message = null) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemorySettingsScreen(
    onBack: () -> Unit,
    vm: MemorySettingsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); vm.clearMessage() } }

    Scaffold(
        topBar = {
            com.hermes.client.ui.components.HermesTopBar(
                title = "Models & memory",
                navigationIcon = { IconButton(onClick = onBack) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (state.loading) {
            com.hermes.client.ui.components.LoadingState()
            return@Scaffold
        }
        if (state.error != null) {
            Text(state.error!!, Modifier.padding(padding).padding(24.dp))
            return@Scaffold
        }
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            ListItem(
                headlineContent = { Text("Default model") },
                supportingContent = { Text(state.defaultModel) },
            )
            ToggleRow("Persistent memory", "Save durable memories across sessions",
                state.memoryEnabled) { vm.setMemoryEnabled(it) }
            ToggleRow("User profile", "Maintain a compact profile of preferences",
                state.userProfileEnabled) { vm.setUserProfileEnabled(it) }
            ToggleRow("Write approval", "Ask before saving a memory",
                state.writeApproval) { vm.setWriteApproval(it) }

            var mem by remember(state.memoryCharLimit) { mutableStateOf(state.memoryCharLimit.toString()) }
            var usr by remember(state.userCharLimit) { mutableStateOf(state.userCharLimit.toString()) }
            Text("BUDGETS (chars)", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
            OutlinedTextField(mem, { mem = it }, label = { Text("Memory budget") }, singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
            OutlinedTextField(usr, { usr = it }, label = { Text("Profile budget") }, singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
            Button(
                onClick = { vm.saveBudgets(mem.toIntOrNull() ?: state.memoryCharLimit, usr.toIntOrNull() ?: state.userCharLimit) },
                modifier = Modifier.padding(16.dp),
            ) { Text("Save budgets") }
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
    )
}
