package com.hermes.client.ui.sessions
import androidx.compose.material.icons.automirrored.rounded.ArrowBack

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.client.domain.Session

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchivedSessionsScreen(
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
    onUnauthorized: () -> Unit = {},
    vm: ArchivedSessionsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.unauthorized) { if (state.unauthorized) onUnauthorized() }
    // Reload on resume so a session archived from the active list shows up here, and a restore
    // disappears, without needing a profile switch or app restart.
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        vm.refresh()
    }

    Scaffold(
        topBar = {
            com.hermes.client.ui.components.HermesTopBar(
                title = "Archived",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        androidx.compose.material3.Icon(
                            androidx.compose.material.icons.Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.loading && state.sessions.isEmpty() ->
                    com.hermes.client.ui.components.LoadingState()
                state.error != null -> com.hermes.client.ui.components.ErrorState(
                    message = state.error!!,
                    onRetry = { vm.refresh() },
                )
                state.sessions.isEmpty() -> com.hermes.client.ui.components.EmptyState(
                    title = "No archived sessions",
                    subtitle = "Archived conversations will appear here.",
                )
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(state.sessions, key = { it.id }) { s ->
                        ArchivedRow(
                            session = s,
                            onOpen = { onOpen(s.id) },
                            onUnarchive = { vm.unarchive(s) },
                            onDelete = { vm.delete(s) },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArchivedRow(
    session: Session,
    onOpen: () -> Unit,
    onUnarchive: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }

    Box {
        ListItem(
            headlineContent = { Text(session.title) },
            supportingContent = { Text(session.model ?: "") },
            // Tap opens the archived session; long-press opens the manage menu.
            modifier = Modifier.combinedClickable(onClick = onOpen, onLongClick = { menuOpen = true }),
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Unarchive") },
                onClick = { menuOpen = false; onUnarchive() },
            )
            DropdownMenuItem(
                text = { Text("Delete") },
                onClick = { menuOpen = false; confirmingDelete = true },
            )
        }
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
