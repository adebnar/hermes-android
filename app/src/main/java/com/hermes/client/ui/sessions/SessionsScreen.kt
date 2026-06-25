package com.hermes.client.ui.sessions

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.client.domain.Session
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    vm: SessionsViewModel = hiltViewModel(),
    onOpen: (String) -> Unit,
    onMenu: () -> Unit = {},
    onOpenArchived: () -> Unit = {},
    onUnauthorized: () -> Unit = {},
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val activeProfile by vm.activeProfile.collectAsStateWithLifecycle()
    val pinnedIds by vm.pinnedIds.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val messageResults by vm.messageResults.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // I1: route to Setup when a 401 is received
    LaunchedEffect(state.unauthorized) {
        if (state.unauthorized) onUnauthorized()
    }

    // Re-fetch on every resume — notably when returning from a chat. The "sessions" nav entry
    // (and its ViewModel) stays alive across navigation, so init() runs only once; without this
    // a session created or updated while in a chat never appears until a profile switch or app
    // restart. Mirrors the same ON_RESUME refresh used by CronScreen.
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        vm.refresh()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Sessions")
                        activeProfile?.let {
                            Text(
                                "Profile: $it",
                                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                },
                navigationIcon = { IconButton(onClick = onMenu) { Text("☰") } },
                actions = { TextButton(onClick = onOpenArchived) { Text("Archived") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { scope.launch { vm.createSession()?.let { onOpen(it) } } },
                text = { Text("New") },
                icon = {},
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // Search: typing filters the loaded list by title/workspace instantly; the keyboard
            // Search action runs a full message-content search via the gateway.
            OutlinedTextField(
                value = query,
                onValueChange = vm::onQueryChange,
                placeholder = { Text("Search sessions…") },
                singleLine = true,
                trailingIcon = {
                    if (query.isNotBlank()) {
                        IconButton(onClick = { vm.onQueryChange("") }) { Text("✕") }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.searchMessages() }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            )
            Box(Modifier.fillMaxSize()) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.error != null -> Text(state.error!!, Modifier.align(Alignment.Center))
                else -> {
                    val q = query.trim()
                    // Instant client-side title/workspace filter over the loaded sessions.
                    val matches = if (q.isEmpty()) state.sessions
                    else state.sessions.filter {
                        it.title.contains(q, ignoreCase = true) ||
                            it.workspace.contains(q, ignoreCase = true)
                    }
                    val pinned = matches.filter { it.id in pinnedIds }
                    // Group the rest by workspace; "No workspace" sorts last.
                    val groups = matches.filterNot { it.id in pinnedIds }
                        .groupBy { it.workspace }
                        .toSortedMap(compareBy({ it == "No workspace" }, { it }))

                    LazyColumn {
                        // Gateway message-content search results (populated on the Search action).
                        if (messageResults.isNotEmpty()) {
                            item(key = "h-msg") { SectionHeader("Message matches", messageResults.size) }
                            // No custom key: results are transient and snippets can collide
                            // (duplicate/empty), which would crash the list. Index keys are safe.
                            items(messageResults) { r ->
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
                        // Hint when nothing matches by title — message search is one tap away.
                        if (q.isNotEmpty() && matches.isEmpty() && messageResults.isEmpty()) {
                            item(key = "no-title-match") {
                                Text(
                                    "No titles match \"$q\". Press search on the keyboard to search message text.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(16.dp),
                                )
                            }
                        }
                        if (pinned.isNotEmpty()) {
                            item(key = "h-pinned") { SectionHeader("Pinned", pinned.size) }
                            items(pinned, key = { "p-${it.id}" }) { s ->
                                SessionRow(
                                    session = s,
                                    isPinned = true,
                                    onOpen = { onOpen(s.id) },
                                    onTogglePin = { vm.togglePin(s.id) },
                                    onRename = { vm.rename(s.id, it) },
                                    onArchive = { vm.archive(s.id) },
                                    onDelete = { vm.delete(s.id) },
                                )
                            }
                        }
                        groups.forEach { (workspace, list) ->
                            item(key = "h-$workspace") { SectionHeader(workspace, list.size) }
                            items(list, key = { it.id }) { s ->
                                SessionRow(
                                    session = s,
                                    isPinned = false,
                                    onOpen = { onOpen(s.id) },
                                    onTogglePin = { vm.togglePin(s.id) },
                                    onRename = { vm.rename(s.id, it) },
                                    onArchive = { vm.archive(s.id) },
                                    onDelete = { vm.delete(s.id) },
                                )
                            }
                        }
                    }
                }
            }
            }
        }
    }
}

@Composable
private fun SectionHeader(label: String, count: Int) {
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label.uppercase(),
            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
            color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Text(
            count.toString(),
            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(
    session: Session,
    isPinned: Boolean,
    onOpen: () -> Unit,
    onTogglePin: () -> Unit,
    onRename: (String) -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }

    Box {
        ListItem(
            headlineContent = { Text(if (isPinned) "📌  ${session.title}" else session.title) },
            // Show the session's true profile (from the cross-profile list) next to its model so
            // the tenant is unambiguous before the profile grouping lands.
            supportingContent = {
                Text(listOfNotNull(session.profile, session.model).joinToString(" · "))
            },
            // Tap opens the session; long-press opens the management menu.
            modifier = Modifier.combinedClickable(
                onClick = onOpen,
                onLongClick = { menuOpen = true },
            ),
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(if (isPinned) "Unpin" else "Pin") },
                onClick = { menuOpen = false; onTogglePin() },
            )
            DropdownMenuItem(
                text = { Text("Rename") },
                onClick = { menuOpen = false; renaming = true },
            )
            DropdownMenuItem(
                text = { Text("Archive") },
                onClick = { menuOpen = false; onArchive() },
            )
            DropdownMenuItem(
                text = { Text("Delete") },
                onClick = { menuOpen = false; confirmingDelete = true },
            )
        }
    }

    if (renaming) {
        var title by remember { mutableStateOf(session.title) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename session") },
            text = {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { renaming = false; if (title.isNotBlank()) onRename(title.trim()) },
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Delete session?") },
            text = { Text("\"${session.title}\" will be permanently deleted.") },
            confirmButton = {
                TextButton(onClick = { confirmingDelete = false; onDelete() }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") } },
        )
    }
}
