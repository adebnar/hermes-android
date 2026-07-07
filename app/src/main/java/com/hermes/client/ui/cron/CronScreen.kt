package com.hermes.client.ui.cron
import androidx.compose.material.icons.automirrored.rounded.ArrowBack

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.PauseCircleOutline
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import com.hermes.client.ui.theme.LocalProfileAccent
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.widget.Toast

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CronScreen(
    onMenu: () -> Unit,
    onOpen: (String) -> Unit = {},
    onNew: () -> Unit = {},
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
                onClick = onNew,
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
                state.jobs.isEmpty() -> com.hermes.client.ui.components.EmptyState(
                    title = "No cron jobs",
                    subtitle = "Scheduled jobs for this profile will show up here.",
                )
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
                                headlineContent = { Text(job.name ?: job.id) },
                                supportingContent = {
                                    val next = job.nextRunAt?.let { "Next: " + com.hermes.client.ui.util.formatIso(it) }
                                    Text(next ?: job.prompt?.replace("\n", " ")?.trim()?.take(100).orEmpty())
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
