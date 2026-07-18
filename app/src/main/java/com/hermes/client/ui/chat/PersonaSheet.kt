package com.hermes.client.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hermes.client.ui.theme.LocalProfileAccent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonaSheet(
    ui: ChatViewModel.PersonaUi,
    onPick: (String?) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val accent = LocalProfileAccent.current.accent
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text("Persona", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))

            when {
                ui.loading -> {
                    Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                ui.error != null -> {
                    Text(ui.error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp))
                    TextButton(onClick = onRetry) { Text("Retry") }
                }
                else -> {
                    LazyColumn(Modifier.fillMaxWidth()) {
                        item {
                            ListItem(
                                headlineContent = { Text("None (default)") },
                                trailingContent = {
                                    if (ui.active == null) {
                                        Icon(Icons.Rounded.Check, contentDescription = "Active", tint = accent)
                                    }
                                },
                                modifier = Modifier.clickable { onPick(null) },
                            )
                        }
                        if (ui.personas.isEmpty()) {
                            item {
                                Text(
                                    "No personas configured — add them in your gateway config.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                            }
                        }
                        items(ui.personas, key = { it.name }) { p ->
                            ListItem(
                                headlineContent = { Text(p.name) },
                                supportingContent = if (p.description.isNotBlank()) {
                                    { Text(p.description) }
                                } else null,
                                trailingContent = {
                                    if (p.name == ui.active) {
                                        Icon(Icons.Rounded.Check, contentDescription = "Active", tint = accent)
                                    }
                                },
                                modifier = Modifier.clickable { onPick(p.name) },
                            )
                        }
                    }
                }
            }
        }
    }
}
