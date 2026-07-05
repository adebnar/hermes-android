package com.hermes.client.ui.usage
import androidx.compose.material.icons.automirrored.rounded.ArrowBack

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.network.ModelUsageDto
import com.hermes.client.data.repository.AnalyticsRepository
import com.hermes.client.data.repository.ProfileManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class UsageUiState(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val estimatedCost: Double = 0.0,
    val sessions: Int = 0,
    val apiCalls: Int = 0,
    val topModels: List<ModelUsageDto> = emptyList(),
    val daily: List<com.hermes.client.data.network.UsageDayDto> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

@HiltViewModel
class UsageViewModel @Inject constructor(
    private val analytics: AnalyticsRepository,
    private val profileManager: ProfileManager,
) : ViewModel() {
    private val _state = MutableStateFlow(UsageUiState())
    val state: StateFlow<UsageUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { profileManager.active.collect { load() } }
    }

    fun load() = viewModelScope.launch {
        val p = profileManager.active.value
        _state.value = _state.value.copy(loading = true, error = null)
        val usage = runCatching { analytics.usage(p) }.getOrNull()
        val models = runCatching { analytics.models(p) }.getOrNull() ?: emptyList()
        if (usage == null) {
            _state.value = UsageUiState(loading = false, error = "Failed to load usage")
            return@launch
        }
        _state.value = UsageUiState(
            inputTokens = usage.daily.sumOf { it.inputTokens },
            outputTokens = usage.daily.sumOf { it.outputTokens },
            estimatedCost = usage.daily.sumOf { it.estimatedCost },
            sessions = usage.daily.sumOf { it.sessions },
            apiCalls = usage.daily.sumOf { it.apiCalls },
            topModels = models.sortedByDescending { it.inputTokens + it.outputTokens }.take(8),
            daily = usage.daily.sortedBy { it.day },
            loading = false,
        )
    }
}

private fun Long.compact(): String = when {
    this >= 1_000_000 -> "%.1fM".format(this / 1_000_000.0)
    this >= 1_000 -> "%.1fK".format(this / 1_000.0)
    else -> toString()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageScreen(
    onMenu: () -> Unit,
    vm: UsageViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            com.hermes.client.ui.components.HermesTopBar(
                title = "Usage",
                navigationIcon = { IconButton(onClick = onMenu) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.loading -> com.hermes.client.ui.components.LoadingState()
                state.error != null -> com.hermes.client.ui.components.ErrorState(
                    message = state.error!!, onRetry = { vm.load() },
                )
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        Column(Modifier.padding(16.dp)) {
                            Row(Modifier.fillMaxWidth()) {
                                Stat("Sessions", state.sessions.toString(), Modifier.weight(1f))
                                Stat("API calls", state.apiCalls.toString(), Modifier.weight(1f))
                            }
                            Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                                Stat("Tokens in/out",
                                    "${state.inputTokens.compact()} / ${state.outputTokens.compact()}",
                                    Modifier.weight(1f))
                                Stat("Est. cost", "$" + "%.2f".format(state.estimatedCost), Modifier.weight(1f))
                            }
                            if (state.daily.isNotEmpty()) {
                                Text("DAILY TOKENS", style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
                                DailyTokensChart(state.daily)
                            }
                        }
                        Text("TOP MODELS", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp))
                    }
                    // No key: model names can repeat across providers (e.g. two "gemma"),
                    // and duplicate keys crash LazyColumn.
                    items(state.topModels) { m ->
                        ListItem(
                            headlineContent = { Text(m.model) },
                            supportingContent = { Text("${m.sessions} sessions · ${m.apiCalls} calls") },
                            trailingContent = {
                                Text((m.inputTokens + m.outputTokens).compact() + " tok")
                            },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun DailyTokensChart(daily: List<com.hermes.client.data.network.UsageDayDto>) {
    val bars = daily.takeLast(30)
    val maxTok = (bars.maxOfOrNull { it.inputTokens + it.outputTokens } ?: 1L).coerceAtLeast(1L)
    val inputColor = MaterialTheme.colorScheme.primary
    val outputColor = MaterialTheme.colorScheme.tertiary
    androidx.compose.foundation.Canvas(
        Modifier.fillMaxWidth().height(120.dp).padding(top = 4.dp),
    ) {
        if (bars.isEmpty()) return@Canvas
        val gap = 3.dp.toPx()
        val barW = ((size.width - gap * (bars.size - 1)) / bars.size).coerceAtLeast(1f)
        bars.forEachIndexed { i, d ->
            val x = i * (barW + gap)
            val inH = size.height * (d.inputTokens.toFloat() / maxTok)
            val outH = size.height * (d.outputTokens.toFloat() / maxTok)
            // output stacked on top of input
            drawRect(inputColor, topLeft = androidx.compose.ui.geometry.Offset(x, size.height - inH),
                size = androidx.compose.ui.geometry.Size(barW, inH))
            drawRect(outputColor, topLeft = androidx.compose.ui.geometry.Offset(x, size.height - inH - outH),
                size = androidx.compose.ui.geometry.Size(barW, outH))
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text("input", style = MaterialTheme.typography.labelSmall, color = inputColor)
        Spacer(Modifier.width(12.dp))
        Text("output", style = MaterialTheme.typography.labelSmall, color = outputColor)
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}
