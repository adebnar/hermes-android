package com.hermes.client.ui.admin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionAdminScreen(
    onMenu: () -> Unit,
    onOpen: (String) -> Unit,
    vm: SessionAdminViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Session admin") },
                navigationIcon = { IconButton(onClick = onMenu) { Text("☰") } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                state.stats?.let { s ->
                    Text(
                        "${s.total} sessions · ${s.archived} archived · ${s.messages} messages",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                OutlinedTextField(
                    value = state.query,
                    onValueChange = vm::onQueryChange,
                    placeholder = { Text("Search messages…") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { vm.search() }),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
            }

            if (state.results.isNotEmpty()) {
                item {
                    SectionHeader("Search results (${state.results.size})")
                }
                items(state.results, key = { it.sessionId + (it.snippet ?: "") }) { r ->
                    ListItem(
                        headlineContent = {
                            Text(r.snippet?.take(140)?.replace("\n", " ") ?: r.sessionId)
                        },
                        supportingContent = { Text(r.model ?: r.role ?: "") },
                        modifier = Modifier.clickable { onOpen(r.sessionId) },
                    )
                    HorizontalDivider()
                }
            }

            item { SectionHeader("Archived (${state.archived.size})") }
            if (state.archived.isEmpty()) {
                item {
                    Text(
                        "No archived sessions.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            } else {
                items(state.archived, key = { it.id }) { s ->
                    ListItem(
                        headlineContent = { Text(s.title) },
                        supportingContent = { Text(s.model ?: "") },
                        modifier = Modifier.clickable { onOpen(s.id) },
                    )
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
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}
