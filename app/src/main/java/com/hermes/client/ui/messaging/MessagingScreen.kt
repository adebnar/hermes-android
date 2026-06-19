package com.hermes.client.ui.messaging

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

data class MessagingUiState(
    val platforms: List<MessagingPlatformDto> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

@HiltViewModel
class MessagingViewModel @Inject constructor(
    private val tools: ToolsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(MessagingUiState())
    val state: StateFlow<MessagingUiState> = _state.asStateFlow()

    init { load() }

    fun load() = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true, error = null)
        runCatching { tools.messagingPlatforms() }
            .onSuccess { _state.value = MessagingUiState(platforms = it, loading = false) }
            .onFailure {
                _state.value = MessagingUiState(loading = false, error = it.message ?: "Failed to load")
            }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagingScreen(
    onMenu: () -> Unit,
    vm: MessagingViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Messaging") },
                navigationIcon = { IconButton(onClick = onMenu) { Text("☰") } },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.error != null -> Text(state.error!!, Modifier.align(Alignment.Center))
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(state.platforms, key = { it.id }) { p ->
                        val status = when {
                            p.enabled && p.gatewayRunning -> "Connected"
                            p.configured -> "Configured (not running)"
                            else -> "Not configured"
                        }
                        ListItem(
                            headlineContent = { Text(p.name ?: p.id) },
                            supportingContent = { Text(p.description ?: "") },
                            trailingContent = {
                                Text(
                                    status,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (p.enabled && p.gatewayRunning)
                                        MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
