package com.hermes.client.ui.cron
import androidx.compose.material.icons.automirrored.rounded.ArrowBack

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.PauseCircleOutline
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import com.hermes.client.ui.theme.LocalProfileAccent
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.widget.Toast

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CronScreen(
    onMenu: () -> Unit,
    onOpen: (String) -> Unit = {},
    onNew: (String) -> Unit = {},
    vm: CronViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    // Reload when returning to this screen (e.g. after create/edit/delete) so the list is fresh.
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { vm.load() }

    Scaffold(
        topBar = {
            com.hermes.client.ui.components.HermesTopBar(
                title = "Cron jobs",
                subtitle = state.profile?.let { "Profile: $it" },
                navigationIcon = { IconButton(onClick = onMenu) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
            )
        },
        floatingActionButton = {
            androidx.compose.material3.ExtendedFloatingActionButton(
                onClick = { onNew("new") },
                text = { Text("New") },
                icon = { Icon(androidx.compose.material.icons.Icons.Rounded.Add, contentDescription = null) },
                containerColor = com.hermes.client.ui.components.AccentChrome.fabContainer,
                contentColor = com.hermes.client.ui.components.AccentChrome.onFab,
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.loading -> com.hermes.client.ui.components.LoadingState()
                state.error != null -> com.hermes.client.ui.components.ErrorState(
                    message = state.error!!, onRetry = { vm.load() },
                )
                state.jobs.isEmpty() -> CronEmpty(onNew = onNew)
                else -> {
                    val nowMs = remember(state.jobs) { System.currentTimeMillis() }
                    val menuFor = remember { mutableStateOf<String?>(null) }
                    val context = LocalContext.current
                    LaunchedEffect(state.message) {
                        state.message?.let {
                            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
                            vm.clearMessage()
                        }
                    }
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(state.jobs, key = { it.id }) { job ->
                            ListItem(
                                leadingContent = {
                                    val (icon, tint) = when (cronRowStatus(job, nowMs)) {
                                        CronRowStatus.FAILED, CronRowStatus.OVERDUE ->
                                            Icons.Rounded.ErrorOutline to MaterialTheme.colorScheme.error
                                        CronRowStatus.PAUSED ->
                                            Icons.Rounded.PauseCircleOutline to MaterialTheme.colorScheme.onSurfaceVariant
                                        CronRowStatus.OK ->
                                            Icons.Rounded.CheckCircle to com.hermes.client.ui.theme.LocalProfileAccent.current.accent
                                    }
                                    Icon(icon, contentDescription = null, tint = tint)
                                },
                                overlineContent = {
                                    Text(
                                        job.scheduleText + when {
                                            job.isPaused -> "  · paused"
                                            !job.enabled -> "  · disabled"
                                            else -> ""
                                        },
                                        color = if (job.enabled && !job.isPaused) LocalProfileAccent.current.accent
                                        else MaterialTheme.colorScheme.error,
                                    )
                                },
                                headlineContent = { Text(cronDisplayName(job.name, job.prompt, job.id)) },
                                supportingContent = {
                                    val next = job.nextRunAt?.let { "Next: " + com.hermes.client.ui.util.formatIso(it) }
                                    // Prompt snippet is only useful here when the headline is the name; when the job is
                                    // unnamed the headline already shows the prompt (via cronDisplayName), so don't repeat it.
                                    val fallback = job.name?.takeIf { it.isNotBlank() }?.let {
                                        job.prompt?.replace("\n", " ")?.trim()?.take(100)
                                    }
                                    Text(next ?: fallback.orEmpty())
                                },
                                trailingContent = {
                                    Box {
                                        IconButton(onClick = { menuFor.value = job.id }) {
                                            Icon(Icons.Rounded.MoreVert, contentDescription = "Actions")
                                        }
                                        DropdownMenu(expanded = menuFor.value == job.id, onDismissRequest = { menuFor.value = null }) {
                                            DropdownMenuItem(
                                                text = { Text(if (job.isPaused) "Resume" else "Pause") },
                                                onClick = {
                                                    vm.runAction(job.id, job.name ?: job.id, if (job.isPaused) CronAction.RESUME else CronAction.PAUSE)
                                                    menuFor.value = null
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Run now") },
                                                onClick = {
                                                    vm.runAction(job.id, job.name ?: job.id, CronAction.RUN)
                                                    menuFor.value = null
                                                }
                                            )
                                        }
                                    }
                                },
                                modifier = Modifier.clickable { onOpen(job.id) },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CronEmpty(onNew: (String) -> Unit) {
    val accent = com.hermes.client.ui.theme.LocalProfileAccent.current
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Rounded.Schedule, contentDescription = null, tint = accent.accent, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(12.dp))
        Text("No cron jobs", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text("Start from a template:", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        CRON_TEMPLATES.forEach { t ->
            OutlinedButton(onClick = { onNew(t.id) }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(t.label)
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = { onNew("new") }) { Text("New cron job") }
    }
}
