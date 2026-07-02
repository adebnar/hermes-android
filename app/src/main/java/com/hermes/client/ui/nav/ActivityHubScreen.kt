package com.hermes.client.ui.nav

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.hermes.client.ui.components.HermesTopBar

/**
 * "Agent Activity" tab — a hub to everything the gateway is doing on your behalf: scheduled
 * jobs, messaging integrations, usage, and agents/tools. This is the seed for the eventual
 * Phase 2 Mission Control (a unified live+scheduled activity timeline).
 */
@Composable
fun ActivityHubScreen(onNavigate: (String) -> Unit) {
    Scaffold(topBar = { HermesTopBar(title = "Agent Activity") }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            HubRow(Icons.Rounded.Schedule, "Cron jobs", "Scheduled agent runs") { onNavigate("cron") }
            HorizontalDivider()
            HubRow(Icons.Rounded.Forum, "Messaging", "Chat-platform integrations") { onNavigate("messaging") }
            HorizontalDivider()
            HubRow(Icons.Rounded.BarChart, "Usage", "Token & cost stats") { onNavigate("usage") }
            HorizontalDivider()
            HubRow(Icons.Rounded.Extension, "Agents & tools", "Available agents and tools") { onNavigate("agents_tools") }
        }
    }
}

@Composable
internal fun HubRow(icon: ImageVector, title: String, subtitle: String?, onClick: () -> Unit) {
    ListItem(
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = { Icon(Icons.Rounded.ChevronRight, contentDescription = null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
