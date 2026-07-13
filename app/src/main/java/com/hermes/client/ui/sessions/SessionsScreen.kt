package com.hermes.client.ui.sessions

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import com.hermes.client.ui.theme.LocalProfileAccent
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
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
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val pinnedTokens by vm.pinnedTokens.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val messageResults by vm.messageResults.collectAsStateWithLifecycle()
    val viewMode by vm.viewMode.collectAsStateWithLifecycle()
    val projectsState by vm.projectsState.collectAsStateWithLifecycle()
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
            Column {
                com.hermes.client.ui.components.HermesTopBar(
                    title = "Chats",
                    actions = {
                        TextButton(
                            onClick = onOpenArchived,
                            colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                                contentColor = com.hermes.client.ui.components.AccentChrome.onBar,
                            ),
                        ) { Text("Archived") }
                    },
                )
                // Same tenant switcher as Agent Activity: a chip row, active one selected. Tapping
                // switches the active profile and the list re-fetches.
                // Sessions mode spans all profiles (REST); Projects mode is single-profile (the
                // gateway's bound profile — projects.tree takes no profile param), so the switcher
                // would be misleading there. Show a caption instead.
                if (viewMode == ViewMode.SESSIONS) {
                    if (profiles.size > 1) {
                        com.hermes.client.ui.components.ProfileSwitcher(
                            names = profiles.map { it.name },
                            active = activeProfile,
                            onSelect = vm::switchProfile,
                        )
                    }
                } else {
                    Text(
                        "Projects · ${activeProfile ?: "default"}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
                val accent = LocalProfileAccent.current
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    val tabs = listOf(ViewMode.SESSIONS to "Sessions", ViewMode.PROJECTS to "Projects")
                    tabs.forEachIndexed { i, (mode, label) ->
                        SegmentedButton(
                            selected = viewMode == mode,
                            onClick = { vm.setViewMode(mode) },
                            shape = SegmentedButtonDefaults.itemShape(i, tabs.size),
                            colors = SegmentedButtonDefaults.colors(
                                activeContainerColor = accent.accent,
                                activeContentColor = accent.onAccent,
                            ),
                        ) { Text(label) }
                    }
                }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { scope.launch { vm.createSession()?.let { onOpen(it) } } },
                text = { Text("New") },
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                containerColor = com.hermes.client.ui.components.AccentChrome.fabContainer,
                contentColor = com.hermes.client.ui.components.AccentChrome.onFab,
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (viewMode == ViewMode.PROJECTS) {
                Box(Modifier.fillMaxSize()) {
                    when {
                        projectsState.loading && projectsState.tree.isEmpty() ->
                            com.hermes.client.ui.components.LoadingState()
                        projectsState.error != null ->
                            com.hermes.client.ui.components.ErrorState(
                                message = projectsState.error!!,
                                onRetry = { vm.loadProjectTree() },
                            )
                        projectsState.scope != null && projectsState.scopeLoading ->
                            com.hermes.client.ui.components.LoadingState()
                        projectsState.scope != null ->
                            ProjectScopeView(
                                project = projectsState.scope!!,
                                onBack = { vm.exitProject() },
                                onOpenSession = { s -> onOpen(s.id) },
                            )
                        projectsState.tree.isEmpty() ->
                            com.hermes.client.ui.components.EmptyState(
                                title = "No projects",
                                subtitle = "Projects are created on the desktop app.",
                                actionLabel = "Reload",
                                onAction = { vm.loadProjectTree() },
                            )
                        else -> ProjectOverview(projectsState.tree, onOpenProject = { vm.enterProject(it) })
                    }
                }
            } else {
                // ── Sessions mode (flat recency) ─────────────────────────────────────────────
                OutlinedTextField(
                    value = query,
                    onValueChange = vm::onQueryChange,
                    placeholder = { Text("Search sessions…") },
                    singleLine = true,
                    trailingIcon = {
                        if (query.isNotBlank()) {
                            IconButton(onClick = { vm.onQueryChange("") }) {
                                Icon(Icons.Rounded.Close, contentDescription = "Clear search")
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { vm.searchMessages() }),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                )
                Box(Modifier.fillMaxSize()) {
                    when {
                        state.loading -> com.hermes.client.ui.components.LoadingState()
                        state.error != null -> com.hermes.client.ui.components.ErrorState(
                            message = state.error!!,
                            onRetry = { vm.refresh() },
                        )
                        state.sessions.isEmpty() && query.isBlank() && messageResults.isEmpty() ->
                            com.hermes.client.ui.components.EmptyState(
                                title = "No sessions yet",
                                subtitle = "Start a conversation with the New button.",
                                actionLabel = "New session",
                                onAction = { scope.launch { vm.createSession()?.let { onOpen(it) } } },
                            )
                        else -> {
                            val q = query.trim()
                            val matches = if (q.isEmpty()) state.sessions
                            else state.sessions.filter {
                                it.title.contains(q, ignoreCase = true) ||
                                    it.workspace.contains(q, ignoreCase = true)
                            }
                            val isPinned = { s: Session ->
                                com.hermes.client.data.repository.PinStore.token(s.profile, s.id) in pinnedTokens
                            }
                            val pinned = matches.filter(isPinned)
                            val recent = sessionsByRecency(matches.filterNot(isPinned))

                            LazyColumn {
                                if (messageResults.isNotEmpty()) {
                                    item(key = "h-msg") { SectionHeader("Message matches", messageResults.size) }
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
                                    item(key = "h-pinned") { SectionHeader("Pinned", pinned.size, note = "Device only") }
                                    items(pinned, key = { "p-${it.id}" }) { s ->
                                        SessionRow(
                                            session = s, isPinned = true, showProfile = true,
                                            onOpen = { scope.launch { vm.prepareOpen(s); onOpen(s.id) } },
                                            onTogglePin = { vm.togglePin(s) },
                                            onRename = { vm.rename(s, it) },
                                            onArchive = { vm.archive(s) },
                                            onDelete = { vm.delete(s) },
                                            modifier = Modifier.animateItem(),
                                        )
                                    }
                                }
                                if (recent.isNotEmpty()) {
                                    item(key = "h-recent") { SectionHeader("Recent", recent.size) }
                                    items(recent, key = { it.id }) { s ->
                                        SessionRow(
                                            session = s, isPinned = false, showProfile = true,
                                            onOpen = { scope.launch { vm.prepareOpen(s); onOpen(s.id) } },
                                            onTogglePin = { vm.togglePin(s) },
                                            onRename = { vm.rename(s, it) },
                                            onArchive = { vm.archive(s) },
                                            onDelete = { vm.delete(s) },
                                            modifier = Modifier.animateItem(),
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
}

@Composable
private fun SectionHeader(label: String, count: Int, note: String? = null) {
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label.uppercase(),
            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
            color = LocalProfileAccent.current.accent,
        )
        note?.let {
            Text(
                "  ·  $it",
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
        Text(
            count.toString(),
            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun SessionRow(
    session: Session,
    isPinned: Boolean,
    showProfile: Boolean,
    onOpen: () -> Unit,
    onTogglePin: () -> Unit,
    onRename: (String) -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }

    // Swipe a row left to archive (frequent, reversible). Delete stays behind the long-press
    // menu + a confirm, since swipe-to-delete is easy to trigger by accident.
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) onArchive()
            // Never keep the box dismissed: on a successful archive the list refresh removes the
            // row; on failure it stays visible instead of getting stuck off-screen with no way back.
            false
        },
        positionalThreshold = { distance -> distance * 0.4f },
    )

    Box(modifier) {
        SwipeToDismissBox(
            state = dismissState,
            enableDismissFromStartToEnd = false,
            enableDismissFromEndToStart = true,
            backgroundContent = {
                Row(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.Archive,
                        contentDescription = "Archive",
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            },
        ) {
            ListItem(
            headlineContent = { Text(session.title) },
            leadingContent = if (isPinned) {
                {
                    Icon(
                        Icons.Rounded.PushPin,
                        contentDescription = "Pinned",
                        modifier = Modifier.size(20.dp),
                        tint = LocalProfileAccent.current.accent,
                    )
                }
            } else null,
            // Pinned rows pool across profiles, so the tenant prefix stays to disambiguate;
            // grouped rows already sit under a profile header, so it would be redundant there.
            supportingContent = {
                Text(listOfNotNull(session.profile?.takeIf { showProfile && it.isNotBlank() }, session.model).joinToString(" · "))
            },
            // Tap opens the session; long-press opens the management menu.
            modifier = Modifier.combinedClickable(
                onClick = onOpen,
                onLongClick = { menuOpen = true },
            ),
            )
        }
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
