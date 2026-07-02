package com.hermes.client.ui.cron
import androidx.compose.material.icons.automirrored.rounded.ArrowBack

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

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
                onClick = onNew, text = { Text("New") }, icon = {},
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.loading -> com.hermes.client.ui.components.LoadingState()
                state.error != null -> Text(state.error!!, Modifier.align(Alignment.Center))
                state.jobs.isEmpty() -> Text(
                    "No cron jobs for this profile.",
                    Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(state.jobs, key = { it.id }) { job ->
                        ListItem(
                            overlineContent = {
                                Text(
                                    job.scheduleText + when {
                                        job.isPaused -> "  · paused"
                                        !job.enabled -> "  · disabled"
                                        else -> ""
                                    },
                                    color = if (job.enabled && !job.isPaused) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.error,
                                )
                            },
                            headlineContent = { Text(job.name ?: job.id) },
                            supportingContent = {
                                val next = job.nextRunAt?.let { "Next: " + com.hermes.client.ui.util.formatIso(it) }
                                Text(next ?: job.prompt?.replace("\n", " ")?.trim()?.take(100).orEmpty())
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
