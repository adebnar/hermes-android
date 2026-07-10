package com.hermes.client.ui.tools
import androidx.compose.material.icons.automirrored.rounded.ArrowBack

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.client.ui.theme.LocalProfileAccent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentsToolsScreen(
    onMenu: () -> Unit,
    vm: ToolsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            com.hermes.client.ui.components.HermesTopBar(
                title = "Agents & tools",
                navigationIcon = { IconButton(onClick = onMenu) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.loading -> com.hermes.client.ui.components.LoadingState()
                state.error != null -> com.hermes.client.ui.components.ErrorState(
                    message = state.error!!, onRetry = { vm.load() },
                )
                state.skills.isEmpty() && state.toolsets.isEmpty() -> com.hermes.client.ui.components.EmptyState(
                    title = "No agents or tools",
                )
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    item { SectionHeader("Skills (${state.skills.size})") }
                    items(state.skills, key = { it.name }) { skill ->
                        ListItem(
                            headlineContent = { Text(skill.name) },
                            supportingContent = {
                                Text(
                                    skill.description?.replace("\n", " ")?.take(120)
                                        ?: skill.category.orEmpty(),
                                )
                            },
                            trailingContent = {
                                Switch(
                                    checked = skill.enabled,
                                    onCheckedChange = { vm.toggleSkill(skill.name, it) },
                                )
                            },
                        )
                    }

                    item { SectionHeader("Toolsets (${state.toolsets.size})") }
                    items(state.toolsets, key = { it.name }) { ts ->
                        ListItem(
                            headlineContent = { Text(ts.label ?: ts.name) },
                            supportingContent = {
                                Text("${ts.tools.size} tools" + if (!ts.available) " · unavailable" else "")
                            },
                            trailingContent = { Text(if (ts.enabled) "on" else "off") },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = LocalProfileAccent.current.accent,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}
