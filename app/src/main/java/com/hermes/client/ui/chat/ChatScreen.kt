package com.hermes.client.ui.chat
import androidx.compose.material.icons.automirrored.rounded.ArrowBack

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.client.data.network.ConnectionState
import com.hermes.client.ui.components.StatusDot
import com.hermes.client.ui.components.connectionLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    sessionId: String,
    vm: ChatViewModel = hiltViewModel(),
    onMenu: () -> Unit = {},
    onUnauthorized: () -> Unit = {},
) {
    LaunchedEffect(sessionId) { vm.open(sessionId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val connState by vm.connectionState.collectAsStateWithLifecycle()
    val unauthorized by vm.unauthorized.collectAsStateWithLifecycle()
    val currentModel by vm.currentModel.collectAsStateWithLifecycle()
    val providers by vm.providers.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val currentProvider by vm.currentProvider.collectAsStateWithLifecycle()
    val modelSheet by vm.modelSheet.collectAsStateWithLifecycle()
    var modelSheetOpen by remember { mutableStateOf(false) }
    val activeProfile by vm.activeProfile.collectAsStateWithLifecycle()
    val commands by vm.commands.collectAsStateWithLifecycle()
    val pathItems by vm.pathItems.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf("") }
    // Slash-command palette: when the draft is a "/query", show matching commands.
    val slashMatches = if (draft.startsWith("/") && !draft.contains(' ')) {
        val q = draft.drop(1).lowercase()
        commands.filter { it.first.removePrefix("/").lowercase().startsWith(q) }
    } else emptyList()
    // "@" mention picker: the last whitespace-separated token starting with "@".
    val atWord = draft.substringAfterLast(' ').takeIf { it.startsWith("@") }
    LaunchedEffect(atWord) { if (atWord != null) vm.completePath(atWord) else vm.clearPathItems() }
    val showPath = slashMatches.isEmpty() && atWord != null && pathItems.isNotEmpty()

    fun insertAt(text: String) {
        val base = draft.dropLast(atWord?.length ?: 0)
        draft = base + text + (if (text.endsWith(":")) "" else " ")
    }
    val connected = connState is ConnectionState.Connected
    val canSend = connected && draft.isNotBlank() && !state.isGenerating
    val haptic = LocalHapticFeedback.current

    fun submit() {
        if (!canSend) return
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        vm.send(draft)
        draft = ""
    }

    // Image attach: pick an image, base64-encode its bytes, attach to the session.
    val context = androidx.compose.ui.platform.LocalContext.current
    val pickImage = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                val mime = context.contentResolver.getType(uri) ?: "image/*"
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching
                val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                vm.attachImage(b64, mime)
            }
        }
    }

    // I1: route back to Setup when the server returns 401
    LaunchedEffect(unauthorized) {
        if (unauthorized) onUnauthorized()
    }

    Scaffold(
        topBar = {
            com.hermes.client.ui.components.HermesTopBar(
                title = "Chat",
                subtitle = activeProfile?.let { "Profile: $it" },
                navigationIcon = {
                    IconButton(onClick = onMenu) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (providers.isNotEmpty()) {
                        androidx.compose.material3.TextButton(onClick = { modelSheetOpen = true }) {
                            Text(currentModel ?: "Model", maxLines = 1)
                        }
                    }
                    StatusDot(connState)
                },
            )
        },
        bottomBar = {
            Row(
                // targetSdk 35+ forces edge-to-edge on Android 15+, so the window no
                // longer resizes for the keyboard — the composer must inset itself.
                // union() takes max(ime, navBars) so it lifts above the keyboard when
                // open and clears the nav bar when closed, without double-padding.
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { pickImage.launch("image/*") }, enabled = connected) {
                    Icon(Icons.Rounded.AttachFile, contentDescription = "Attach image")
                }
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message Hermes…  (/ commands · @ attach)") },
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submit() }),
                )
                Spacer(Modifier.width(8.dp))
                if (state.isGenerating) {
                    IconButton(onClick = { vm.stop() }) {
                        Icon(Icons.Rounded.Stop, contentDescription = "Stop")
                    }
                } else {
                    IconButton(onClick = { submit() }, enabled = canSend) {
                        Icon(
                            Icons.AutoMirrored.Rounded.Send,
                            contentDescription = "Send",
                            tint = if (canSend) com.hermes.client.ui.components.AccentChrome.fabContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (!connected) {
                ConnectionBanner(connState, onRetry = { vm.reconnect() })
            }
            if (slashMatches.isNotEmpty()) {
                // Typing "/" turns the message area into a full, scrollable command picker.
                Text(
                    "COMMANDS",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
                )
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    items(slashMatches) { (name, desc) ->
                        val cmd = if (name.startsWith("/")) name else "/$name"
                        androidx.compose.material3.ListItem(
                            headlineContent = { Text(cmd) },
                            supportingContent = { if (desc.isNotBlank()) Text(desc) },
                            modifier = Modifier.clickable { draft = "$cmd " },
                        )
                    }
                }
            } else if (showPath) {
                Text(
                    "ATTACH / MENTION",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
                )
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    items(pathItems) { item ->
                        androidx.compose.material3.ListItem(
                            headlineContent = { Text(item.display) },
                            supportingContent = { if (item.meta.isNotBlank()) Text(item.meta) },
                            modifier = Modifier.clickable { insertAt(item.text) },
                        )
                    }
                }
            } else {
                ChatMessageList(state = state, sessionId = sessionId, modifier = Modifier.weight(1f))
            }
        }
    }

    state.pendingApproval?.let { req ->
        ApprovalDialog(
            prompt = req.prompt,
            onApprove = { vm.approve(true) },
            onDeny = { vm.approve(false) },
        )
    }

    state.pendingClarify?.let { req ->
        var answer by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { vm.clarify("") },
            title = { Text("Clarification") },
            text = {
                Column {
                    Text(req.question)
                    OutlinedTextField(value = answer, onValueChange = { answer = it })
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.clarify(answer) }) { Text("Send") }
            },
        )
    }

    if (modelSheetOpen) {
        val items = com.hermes.client.ui.models.modelSelectorRows(
            providers = providers, favorites = favorites, query = modelSheet.query,
            currentProvider = currentProvider, currentModel = currentModel,
        )
        com.hermes.client.ui.models.ModelSelectorSheet(
            items = items,
            query = modelSheet.query, onQueryChange = vm::onSheetQuery,
            scope = modelSheet.scope, onScopeChange = vm::onSheetScope,
            onToggleFavorite = vm::toggleFavorite,
            onSelect = { p, m -> vm.onSelectFromSheet(p, m) { modelSheetOpen = false } },
            pending = modelSheet.pending, error = modelSheet.error,
            onDismiss = { modelSheetOpen = false },
        )
    }
}

@Composable
private fun ConnectionBanner(state: ConnectionState, onRetry: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = connectionLabel(state),
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        // While Connecting the client is already trying — no point offering a manual retry.
        if (state !is ConnectionState.Connecting) {
            TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

