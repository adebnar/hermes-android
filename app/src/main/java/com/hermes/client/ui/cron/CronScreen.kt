package com.hermes.client.ui.cron

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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CronScreen(
    onMenu: () -> Unit,
    vm: CronViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    androidx.compose.foundation.layout.Column {
                        Text("Cron jobs")
                        state.profile?.let {
                            Text("Profile: $it", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                },
                navigationIcon = { IconButton(onClick = onMenu) { Text("☰") } },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
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
                                    (job.schedule?.display ?: job.schedule?.expr ?: "—") +
                                        if (!job.enabled) "  · disabled" else "",
                                    color = if (job.enabled) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.error,
                                )
                            },
                            headlineContent = { Text(job.name ?: job.id) },
                            supportingContent = {
                                job.prompt?.replace("\n", " ")?.trim()?.take(120)?.let { Text(it) }
                            },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
