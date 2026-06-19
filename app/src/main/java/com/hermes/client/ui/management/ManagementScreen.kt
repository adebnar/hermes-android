package com.hermes.client.ui.management

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
fun ManagementScreen(onMenu: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Management") },
                navigationIcon = { IconButton(onClick = onMenu) { Text("☰") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            ListItem(
                headlineContent = { Text("Session admin") },
                supportingContent = { Text("Search, archived sessions, usage — coming next") },
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Agents & tools") },
                supportingContent = { Text("Tools, skills, MCP servers — coming next") },
            )
        }
    }
}
