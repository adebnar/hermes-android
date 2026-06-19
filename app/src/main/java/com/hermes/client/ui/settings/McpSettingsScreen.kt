package com.hermes.client.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.hermes.client.data.repository.ConfigRepository
import com.hermes.client.data.repository.ProfileManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject

data class McpServer(val name: String, val transport: String, val json: String)

data class McpUiState(
    val servers: List<McpServer> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val message: String? = null,
)

@HiltViewModel
class McpSettingsViewModel @Inject constructor(
    private val config: ConfigRepository,
    private val profileManager: ProfileManager,
) : ViewModel() {
    private val pretty = Json { prettyPrint = true }
    private val _state = MutableStateFlow(McpUiState())
    val state: StateFlow<McpUiState> = _state.asStateFlow()
    private val profile: String? get() = profileManager.active.value

    init { load() }

    fun load() = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true, error = null)
        runCatching { config.get(profile) }
            .onSuccess { cfg ->
                val servers = (cfg["mcp_servers"] as? JsonObject)?.entries?.map { (name, value) ->
                    val obj = value as? JsonObject
                    val transport = (obj?.get("transport") ?: obj?.get("type"))?.toString()?.trim('"')
                        ?: if (obj?.containsKey("url") == true) "http" else "stdio"
                    McpServer(name, transport, pretty.encodeToString(JsonObject.serializer(), obj ?: JsonObject(emptyMap())))
                } ?: emptyList()
                _state.value = McpUiState(servers = servers, loading = false)
            }
            .onFailure { _state.value = McpUiState(loading = false, error = it.message ?: "Failed to load") }
    }

    /** Parse the edited JSON and write mcp_servers[name] via a whole-config update. */
    fun save(name: String, jsonText: String) = viewModelScope.launch {
        val parsed = runCatching { Json.decodeFromString(JsonObject.serializer(), jsonText) }.getOrNull()
        if (parsed == null) { _state.value = _state.value.copy(message = "Invalid JSON"); return@launch }
        runCatching {
            config.update(profile) { cfg ->
                val servers = (cfg["mcp_servers"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
                servers[name] = parsed
                cfg["mcp_servers"] = JsonObject(servers)
            }
        }.onSuccess { _state.value = _state.value.copy(message = "Saved $name"); load() }
            .onFailure { _state.value = _state.value.copy(message = "Save failed: ${it.message}") }
    }

    fun remove(name: String) = viewModelScope.launch {
        runCatching {
            config.update(profile) { cfg ->
                val servers = (cfg["mcp_servers"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
                servers.remove(name)
                cfg["mcp_servers"] = JsonObject(servers)
            }
        }.onSuccess { _state.value = _state.value.copy(message = "Removed $name"); load() }
            .onFailure { _state.value = _state.value.copy(message = "Remove failed: ${it.message}") }
    }

    fun clearMessage() { _state.value = _state.value.copy(message = null) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpSettingsScreen(
    onBack: () -> Unit,
    vm: McpSettingsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var selected by remember { mutableStateOf<McpServer?>(null) }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); vm.clearMessage() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MCP servers") },
                navigationIcon = { IconButton(onClick = onBack) { Text("‹") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (state.loading) {
            CircularProgressIndicator(Modifier.padding(padding).padding(24.dp)); return@Scaffold
        }
        if (state.error != null) {
            Text(state.error!!, Modifier.padding(padding).padding(24.dp)); return@Scaffold
        }
        Column(Modifier.padding(padding).fillMaxSize()) {
            state.servers.forEach { server ->
                ListItem(
                    headlineContent = { Text(server.name) },
                    supportingContent = { Text(server.transport) },
                    modifier = Modifier.clickable { selected = server },
                )
                HorizontalDivider()
            }
        }
    }

    selected?.let { server ->
        var jsonText by remember(server.name) { mutableStateOf(server.json) }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(server.name) },
                    navigationIcon = { IconButton(onClick = { selected = null }) { Text("‹") } },
                )
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                Text("SERVER JSON", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
                OutlinedTextField(
                    value = jsonText,
                    onValueChange = { jsonText = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    minLines = 8,
                )
                Row(Modifier.padding(top = 12.dp)) {
                    Button(onClick = { vm.save(server.name, jsonText); selected = null }) { Text("Save") }
                    TextButton(onClick = { vm.remove(server.name); selected = null },
                        modifier = Modifier.padding(start = 8.dp)) { Text("Remove") }
                }
            }
        }
    }
}
