package com.hermes.client.ui.sessions

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
    onUnauthorized: () -> Unit = {},
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val activeProfile by vm.activeProfile.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // I1: route to Setup when a 401 is received
    LaunchedEffect(state.unauthorized) {
        if (state.unauthorized) onUnauthorized()
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
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.error != null -> Text(state.error!!, Modifier.align(Alignment.Center))
                else -> LazyColumn {
                    items(state.sessions, key = { it.id }) { s ->
                        SessionRow(
                            session = s,
                            onOpen = { onOpen(s.id) },
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(
    session: Session,
    onOpen: () -> Unit,
    onRename: (String) -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }

    Box {
        ListItem(
            headlineContent = { Text(session.title) },
            supportingContent = { Text(session.model ?: "") },
            // Tap opens the session; long-press opens the management menu.
            modifier = Modifier.combinedClickable(
                onClick = onOpen,
                onLongClick = { menuOpen = true },
            ),
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
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
