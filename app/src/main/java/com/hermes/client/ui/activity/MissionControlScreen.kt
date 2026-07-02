package com.hermes.client.ui.activity

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.client.ui.components.EmptyState
import com.hermes.client.ui.components.ErrorState
import com.hermes.client.ui.components.HermesTopBar
import com.hermes.client.ui.components.LoadingState
import com.hermes.client.ui.util.relativeTime

private data class QuickLink(val label: String, val icon: ImageVector, val route: String)

private val QUICK_LINKS = listOf(
    QuickLink("Cron", Icons.Rounded.Schedule, "cron"),
    QuickLink("Messaging", Icons.Rounded.Forum, "messaging"),
    QuickLink("Usage", Icons.Rounded.BarChart, "usage"),
    QuickLink("Agents", Icons.Rounded.Extension, "agents_tools"),
)

/**
 * Mission Control — the "Agent Activity" tab. A live, time-grouped feed (Live now · Upcoming ·
 * Recent) merging the active profile's conversations and cron, with quick links to the manage
 * screens. [onNavigate] opens both feed items and the quick links.
 */
@Composable
fun MissionControlScreen(
    onNavigate: (String) -> Unit,
    vm: MissionControlViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val activeProfile by vm.activeProfile.collectAsStateWithLifecycle()
    // Recompute on each (re)entry so relative times stay roughly fresh.
    val now = remember(state.sections) { System.currentTimeMillis() }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }

    Scaffold(
        topBar = {
            HermesTopBar(title = "Agent Activity", subtitle = activeProfile?.let { "Profile: $it" })
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item(key = "quicklinks") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    QUICK_LINKS.forEach { link ->
                        AssistChip(
                            onClick = { onNavigate(link.route) },
                            label = { Text(link.label) },
                            leadingIcon = {
                                Icon(link.icon, contentDescription = null, Modifier.width(18.dp))
                            },
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                }
            }

            when {
                state.loading && state.sections.isEmpty() -> item { LoadingState() }
                state.error != null -> item {
                    ErrorState(message = state.error!!, onRetry = { vm.refresh() })
                }
                state.sections.isEmpty() -> item {
                    EmptyState(
                        title = "Nothing happening yet",
                        subtitle = "Conversations and scheduled runs for this profile will show up here.",
                    )
                }
                else -> state.sections.forEach { section ->
                    item(key = "h-${section.title}") { SectionHeader(section.title, section.items.size) }
                    items(section.items, key = { it.id }) { activity ->
                        ActivityRow(activity, now, onClick = { onNavigate(activity.route) })
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(label: String, count: Int) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    ) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Text(
            count.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ActivityRow(item: ActivityItem, nowMs: Long, onClick: () -> Unit) {
    val icon: ImageVector = when {
        item.kind == ActivityKind.CONVERSATION -> Icons.AutoMirrored.Rounded.Chat
        item.upcoming -> Icons.Rounded.Schedule
        item.status.equals("success", ignoreCase = true) -> Icons.Rounded.CheckCircle
        item.status.equals("error", ignoreCase = true) ||
            item.status.equals("failed", ignoreCase = true) -> Icons.Rounded.ErrorOutline
        else -> Icons.Rounded.History
    }
    val tint = when {
        item.status.equals("error", ignoreCase = true) ||
            item.status.equals("failed", ignoreCase = true) -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    val time = relativeTime(item.timestampMs, nowMs)
    ListItem(
        leadingContent = { Icon(icon, contentDescription = null, tint = tint) },
        headlineContent = { Text(item.title) },
        supportingContent = {
            Text(listOfNotNull(item.subtitle, time).joinToString(" · "))
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
