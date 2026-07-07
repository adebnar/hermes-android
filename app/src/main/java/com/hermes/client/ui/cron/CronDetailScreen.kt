package com.hermes.client.ui.cron
import androidx.compose.material.icons.automirrored.rounded.ArrowBack

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.hermes.client.ui.theme.LocalProfileAccent
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.client.ui.util.formatEpoch
import com.hermes.client.ui.util.formatIso

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CronDetailScreen(
    jobId: String,
    onBack: () -> Unit,
    onEdit: () -> Unit = {},
    vm: CronDetailViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirmingDelete by remember { mutableStateOf(false) }

    LaunchedEffect(jobId) { vm.load(jobId) }
    LaunchedEffect(state.deleted) { if (state.deleted) onBack() }
    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); vm.clearMessage() }
    }

    Scaffold(
        topBar = {
            com.hermes.client.ui.components.HermesTopBar(
                title = state.job?.name ?: "Cron job",
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
        when {
            state.loading -> com.hermes.client.ui.components.LoadingState()
            state.job == null -> com.hermes.client.ui.components.ErrorState(
                message = state.error ?: "Couldn't load this cron job",
                modifier = Modifier.padding(padding).fillMaxSize(),
                onRetry = { vm.load(jobId) },
            )
            else -> {
                val job = state.job!!
                LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                    item {
                        Column(Modifier.padding(16.dp)) {
                            Field("Schedule", job.scheduleText)
                            Spacer(Modifier.height(8.dp))
                            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
                                Column(Modifier.padding(12.dp)) {
                                    Field("Status", if (job.isPaused) "Paused" else if (job.enabled) "Enabled" else "Disabled")
                                    Field("Next run", formatIso(job.nextRunAt))
                                    Field("Last run", formatIso(job.lastRunAt) + (job.lastStatus?.let { " · $it" } ?: ""))
                                    job.lastError?.takeIf { it.isNotBlank() }?.let { err ->
                                        var errorExpanded by rememberSaveable { mutableStateOf(false) }
                                        Spacer(Modifier.height(6.dp))
                                        Text(
                                            err,
                                            color = MaterialTheme.colorScheme.error,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = if (errorExpanded) Int.MAX_VALUE else 3,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.clickable { errorExpanded = !errorExpanded },
                                        )
                                        Text(
                                            if (errorExpanded) "Show less" else "Show more",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = LocalProfileAccent.current.accent,
                                            modifier = Modifier.clickable { errorExpanded = !errorExpanded }.padding(top = 2.dp),
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.padding(top = 12.dp))
                            Row {
                                if (job.isPaused) {
                                    Button(onClick = { vm.resume() }) { Text("Resume") }
                                } else {
                                    OutlinedButton(onClick = { vm.pause() }) { Text("Pause") }
                                }
                                Spacer(Modifier.width(8.dp))
                                Button(onClick = { vm.trigger() }) { Text("Run now") }
                                Spacer(Modifier.width(8.dp))
                                OutlinedButton(onClick = onEdit) { Text("Edit") }
                                Spacer(Modifier.width(8.dp))
                                TextButton(onClick = { confirmingDelete = true }) { Text("Delete") }
                            }
                            job.prompt?.takeIf { it.isNotBlank() }?.let {
                                Spacer(Modifier.padding(top = 12.dp))
                                Text("PROMPT", style = MaterialTheme.typography.labelSmall,
                                    color = LocalProfileAccent.current.accent)
                                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 8,
                                    overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Text(
                            "RUN HISTORY (${state.runs.size})",
                            style = MaterialTheme.typography.labelMedium,
                            color = LocalProfileAccent.current.accent,
                            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
                        )
                    }
                    items(state.runs, key = { it.id }) { run ->
                        ListItem(
                            headlineContent = { Text(formatEpoch(run.startedAt)) },
                            supportingContent = { Text(run.endReason ?: "—") },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Delete cron job?") },
            text = { Text("This permanently deletes the scheduled job.") },
            confirmButton = {
                TextButton(onClick = { confirmingDelete = false; vm.delete() }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Field(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, Modifier.width(96.dp), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
