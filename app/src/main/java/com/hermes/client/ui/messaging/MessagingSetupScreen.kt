package com.hermes.client.ui.messaging

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.network.MessagingPlatformDto
import com.hermes.client.data.repository.ToolsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MessagingSetupState(
    val platform: MessagingPlatformDto? = null,
    val loading: Boolean = true,
    val saving: Boolean = false,
    val message: String? = null,
    val done: Boolean = false,
)

@HiltViewModel
class MessagingSetupViewModel @Inject constructor(
    private val tools: ToolsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(MessagingSetupState())
    val state: StateFlow<MessagingSetupState> = _state.asStateFlow()
    private var id: String = ""

    fun load(platformId: String) {
        id = platformId
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            val p = runCatching { tools.messagingPlatforms() }.getOrNull()?.firstOrNull { it.id == platformId }
            _state.value = _state.value.copy(platform = p, loading = false)
        }
    }

    fun saveAndEnable(values: Map<String, String>) = viewModelScope.launch {
        _state.value = _state.value.copy(saving = true)
        val env = values.filterValues { it.isNotBlank() }
        runCatching { tools.configureMessaging(id, env, enabled = true) }
            .onSuccess { _state.value = _state.value.copy(saving = false, done = true) }
            .onFailure { _state.value = _state.value.copy(saving = false, message = "Save failed: ${it.message}") }
    }

    fun clearMessage() { _state.value = _state.value.copy(message = null) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagingSetupScreen(
    platformId: String,
    onDone: () -> Unit,
    vm: MessagingSetupViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val values = remember { mutableStateMapOf<String, String>() }

    LaunchedEffect(platformId) { vm.load(platformId) }
    LaunchedEffect(state.done) { if (state.done) onDone() }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); vm.clearMessage() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.platform?.name ?: "Set up") },
                navigationIcon = { IconButton(onClick = onDone) { Text("‹") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val p = state.platform
        when {
            state.loading -> CircularProgressIndicator(Modifier.padding(padding).padding(24.dp))
            p == null -> Text("Platform not found", Modifier.padding(padding).padding(24.dp))
            else -> Column(
                Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            ) {
                p.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                p.docsUrl?.let { url ->
                    Text(
                        "Setup guide ↗",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp).clickable {
                            runCatching {
                                context.startActivity(
                                    android.content.Intent(android.content.Intent.ACTION_VIEW, url.toUri()),
                                )
                            }
                        },
                    )
                }
                if (p.envVars.isEmpty()) {
                    Text(
                        "No configuration needed — just enable it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
                p.envVars.forEach { v ->
                    OutlinedTextField(
                        value = values[v.key] ?: "",
                        onValueChange = { values[v.key] = it },
                        label = {
                            Text((v.prompt ?: v.key) + if (v.required) " *" else "" + if (v.isSet) "  (set)" else "")
                        },
                        supportingText = { v.description?.let { Text(it, maxLines = 2) } },
                        singleLine = true,
                        visualTransformation = if (v.isPassword) PasswordVisualTransformation()
                        else androidx.compose.ui.text.input.VisualTransformation.None,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
                Button(
                    onClick = { vm.saveAndEnable(values.toMap()) },
                    enabled = !state.saving,
                    modifier = Modifier.padding(top = 16.dp),
                ) { Text(if (state.saving) "Saving…" else "Save & enable") }
            }
        }
    }
}
