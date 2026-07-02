package com.hermes.client.ui.settings
import androidx.compose.material.icons.automirrored.rounded.ArrowBack

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Settings hub — mirrors the desktop Settings sections (built out incrementally). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onMenu: () -> Unit,
    onNavigate: (String) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onMenu) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            Entry("Server & token", "Gateway URL and token this app connects to") { onNavigate("settings_connection") }
            HorizontalDivider()
            Entry("Appearance", "Theme, light/dark, tool-call display") { onNavigate("settings_appearance") }
            HorizontalDivider()
            Entry("Models & memory", "Default model, memory toggles & budgets") { onNavigate("settings_memory") }
            HorizontalDivider()
            Entry("MCP servers", "View and edit connected MCP servers") { onNavigate("settings_mcp") }
            HorizontalDivider()
            Entry("API keys & env", "Provider keys and tool env vars") { onNavigate("settings_env") }
            HorizontalDivider()
            Entry("Diagnostics", "Capture a shareable debug log to troubleshoot errors") { onNavigate("settings_diagnostics") }
            HorizontalDivider()
            Entry("About", "App and gateway version") { onNavigate("settings_about") }
        }
    }
}

@Composable
private fun Entry(title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        modifier = Modifier.clickable { onClick() },
    )
}
