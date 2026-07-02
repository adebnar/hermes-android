package com.hermes.client.ui.management
import androidx.compose.material.icons.automirrored.rounded.ArrowBack

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManagementScreen(
    onMenu: () -> Unit,
    onNavigate: (String) -> Unit = {},
) {
    Scaffold(
        topBar = {
            com.hermes.client.ui.components.HermesTopBar(
                title = "Management",
                navigationIcon = { IconButton(onClick = onMenu) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            ListItem(
                headlineContent = { Text("Session admin") },
                supportingContent = { Text("Search messages, archived sessions, stats") },
                modifier = Modifier.clickable { onNavigate("session_admin") },
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Agents & tools") },
                supportingContent = { Text("Toggle skills, view toolsets") },
                modifier = Modifier.clickable { onNavigate("agents_tools") },
            )
        }
    }
}
