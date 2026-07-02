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
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandMore
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val pinnedTokens by vm.pinnedTokens.collectAsStateWithLifecycle()
    val collapsedGroups by vm.collapsedGroups.collectAsStateWithLifecycle()
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
            Column {
                com.hermes.client.ui.components.HermesTopBar(
                    title = "Sessions",
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
                if (profiles.size > 1) {
                    com.hermes.client.ui.components.ProfileChips(
                        names = profiles.map { it.name },
                        active = activeProfile,
                        onSelect = vm::switchProfile,
                    )
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
            // Search: typing filters the loaded list by title/workspace instantly; the keyboard
            // Search action runs a full message-content search via the gateway.
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
                    // Instant client-side title/workspace filter over the loaded sessions.
                    val matches = if (q.isEmpty()) state.sessions
                    else state.sessions.filter {
                        it.title.contains(q, ignoreCase = true) ||
                            it.workspace.contains(q, ignoreCase = true)
                    }
                    // Pins are keyed by each session's OWN profile (the list spans all profiles),
                    // so a pin made in another tenant still shows here.
                    val isPinned = { s: Session ->
                        com.hermes.client.data.repository.PinStore.token(s.profile, s.id) in pinnedTokens
                    }
                    val pinned = matches.filter(isPinned)
                    // Two-tier tree: Profile → Workspace → rows, with collapsed groups already
                    // pruned. The active profile sorts first.
                    val tree = groupSessions(
                        matches.filterNot(isPinned),
                        collapsedGroups,
                        activeProfile,
                    )

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
                            // "Device only" makes clear pins live on this phone and don't sync to
                            // the desktop app (the gateway has no pin concept).
                            item(key = "h-pinned") { SectionHeader("Pinned", pinned.size, note = "Device only") }
                            items(pinned, key = { "p-${it.id}" }) { s ->
                                SessionRow(
                                    session = s,
                                    isPinned = true,
                                    // Switch to the session's profile (awaited) before navigating,
                                    // so the chat resumes against the right per-profile DB.
                                    onOpen = { scope.launch { vm.prepareOpen(s); onOpen(s.id) } },
                                    onTogglePin = { vm.togglePin(s) },
                                    onRename = { vm.rename(s, it) },
                                    onArchive = { vm.archive(s) },
                                    onDelete = { vm.delete(s) },
                                    modifier = Modifier.animateItem(),
                                )
                            }
                        }
                        tree.forEach { pg ->
                            item(key = "ph-${pg.profile}") {
                                CollapsibleHeader(
                                    label = pg.profile,
                                    count = pg.count,
                                    collapsed = pg.collapsed,
                                    indent = false,
                                    onToggle = {
                                        vm.toggleGroup(
                                            com.hermes.client.data.repository.GroupExpansionStore
                                                .profileKey(pg.profile),
                                        )
                                    },
                                )
                            }
                            pg.workspaces.forEach { wg ->
                                item(key = "wh-${pg.profile}-${wg.workspace}") {
                                    CollapsibleHeader(
                                        label = wg.workspace,
                                        count = wg.count,
                                        collapsed = wg.collapsed,
                                        indent = true,
                                        onToggle = {
                                            vm.toggleGroup(
                                                com.hermes.client.data.repository.GroupExpansionStore
                                                    .workspaceKey(pg.profile, wg.workspace),
                                            )
                                        },
                                    )
                                }
                                items(wg.sessions, key = { it.id }) { s ->
                                    SessionRow(
                                        session = s,
                                        isPinned = false,
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

/**
 * A tap-to-collapse group header (profile = top tier, workspace = indented sub-tier). A leading
 * chevron shows the state; the whole row toggles. Profile labels read in caps; workspace labels
 * are indented and lighter so the two tiers are visually distinct.
 */
@Composable
private fun CollapsibleHeader(
    label: String,
    count: Int,
    collapsed: Boolean,
    indent: Boolean,
    onToggle: () -> Unit,
) {
    androidx.compose.foundation.layout.Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(
                start = if (indent) 28.dp else 16.dp,
                end = 16.dp,
                top = if (indent) 6.dp else 16.dp,
                bottom = 4.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (collapsed) Icons.Rounded.ChevronRight else Icons.Rounded.ExpandMore,
            contentDescription = if (collapsed) "Expand" else "Collapse",
            tint = if (indent) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 8.dp).size(18.dp),
        )
        Text(
            if (indent) label else label.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = if (indent) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Text(
            count.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
            color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
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
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            } else null,
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
