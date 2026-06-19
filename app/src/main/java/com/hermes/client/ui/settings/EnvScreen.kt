package com.hermes.client.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.network.EnvVarDto
import com.hermes.client.data.repository.EnvRepository
import com.hermes.client.data.repository.ProfileManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class EnvUiState(
    val vars: List<Pair<String, EnvVarDto>> = emptyList(),
    val query: String = "",
    val loading: Boolean = true,
    val error: String? = null,
    val message: String? = null,
) {
    val filtered: List<Pair<String, EnvVarDto>>
        get() = if (query.isBlank()) vars
        else vars.filter { it.first.contains(query, true) || (it.second.description?.contains(query, true) == true) }
}

@HiltViewModel
class EnvViewModel @Inject constructor(
    private val env: EnvRepository,
    private val profileManager: ProfileManager,
) : ViewModel() {
    private val _state = MutableStateFlow(EnvUiState())
    val state: StateFlow<EnvUiState> = _state.asStateFlow()
    private val profile: String? get() = profileManager.active.value

    init { load() }

    fun load() = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true, error = null)
        runCatching { env.vars(profile) }
            .onSuccess { m ->
                // Set vars first, then alphabetical.
                val sorted = m.toList().sortedWith(compareByDescending<Pair<String, EnvVarDto>> { it.second.isSet }.thenBy { it.first })
                _state.value = _state.value.copy(vars = sorted, loading = false)
            }
            .onFailure { _state.value = _state.value.copy(loading = false, error = it.message ?: "Failed to load") }
    }

    fun onQuery(q: String) { _state.value = _state.value.copy(query = q) }

    fun set(key: String, value: String) = viewModelScope.launch {
        runCatching { env.set(key, value, profile) }
            .onSuccess { _state.value = _state.value.copy(message = "$key saved"); load() }
            .onFailure { _state.value = _state.value.copy(message = "Save failed: ${it.message}") }
    }

    fun clearMessage() { _state.value = _state.value.copy(message = null) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnvScreen(
    onBack: () -> Unit,
    vm: EnvViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); vm.clearMessage() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("API keys & env") },
                navigationIcon = { IconButton(onClick = onBack) { Text("‹") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.error != null -> Text(state.error!!, Modifier.align(Alignment.Center))
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        OutlinedTextField(
                            value = state.query,
                            onValueChange = vm::onQuery,
                            placeholder = { Text("Search keys…") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                        )
                    }
                    items(state.filtered, key = { it.first }) { (key, dto) ->
                        ListItem(
                            headlineContent = { Text(key) },
                            supportingContent = { Text(dto.description ?: dto.category ?: "") },
                            trailingContent = {
                                Text(
                                    if (dto.isSet) "set" else "—",
                                    color = if (dto.isSet) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            modifier = Modifier.clickable { editing = key },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    editing?.let { key ->
        var value by remember(key) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(key) },
            text = {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text("Value") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
            },
            confirmButton = {
                TextButton(onClick = { editing = null; if (value.isNotBlank()) vm.set(key, value) }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}
