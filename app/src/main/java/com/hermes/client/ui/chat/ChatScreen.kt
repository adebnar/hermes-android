package com.hermes.client.ui.chat
import androidx.compose.material.icons.automirrored.rounded.ArrowBack

import android.net.Uri
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import java.io.File
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import com.hermes.client.ui.theme.LocalProfileAccent
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.launch
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.client.data.network.ConnectionState
import com.hermes.client.ui.components.StatusDot
import com.hermes.client.ui.components.bannerLabel
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
    var modelSheetOpen by rememberSaveable { mutableStateOf(false) }
    val activeProfile by vm.activeProfile.collectAsStateWithLifecycle()
    val commands by vm.commands.collectAsStateWithLifecycle()
    val pathItems by vm.pathItems.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf("") }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var currentMatch by rememberSaveable { mutableStateOf(0) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val matches = remember(query, state.messages) { matchIndices(state.messages, query) }
    // Reset the cursor when the QUERY changes — not when `matches` changes: `matches` is a fresh
    // list instance on every streamed token, which would otherwise yank the cursor to 0 mid-search.
    LaunchedEffect(query, searchOpen) { currentMatch = 0 }
    val highlightIndex = if (searchOpen) matches.getOrNull(currentMatch) else null
    // Key the scroll on the resolved match index, so it only animates when the active match actually
    // moves — not on every streamed token (which changes `matches`'s identity but not the target).
    LaunchedEffect(highlightIndex) { highlightIndex?.let { listState.animateScrollToItem(it) } }
    val focusRequester = remember { FocusRequester() }
    val initialDraft by vm.initialDraft.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(initialDraft) {
        initialDraft?.takeIf { it.isNotEmpty() }?.let { draft = it; vm.clearInitialDraft() }
    }
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
    val canSend = canSend(connected, draft.isNotBlank(), state.pendingAttachments.isNotEmpty(), state.isGenerating)
    val haptic = LocalHapticFeedback.current

    fun submit() {
        if (!canSend) return
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        vm.send(draft)
        draft = ""
    }

    // Image attach: read picked/captured bytes and stage them onto the session.
    val context = androidx.compose.ui.platform.LocalContext.current
    val attachScope = androidx.compose.runtime.rememberCoroutineScope()

    fun readBytes(uri: Uri): ByteArray? =
        runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()

    // Photo library: multi-select via the system photo picker (no permission).
    // Read bytes off the main thread (large images would otherwise jank/ANR the UI).
    val pickPhotos = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(ATTACH_CAP),
    ) { uris ->
        attachScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            uris.forEach { uri ->
                readBytes(uri)?.let { vm.stageAttachment(it, context.contentResolver.getType(uri) ?: "image/*") }
            }
        }
    }

    // Camera: capture into a FileProvider cache uri, then read it back. No CAMERA permission (delegates).
    // rememberSaveable (Uri is Parcelable): survive process death while the camera app is foregrounded,
    // so the captured photo isn't dropped when we return.
    var captureUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    val takePhoto = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { ok ->
        if (ok) captureUri?.let { uri ->
            attachScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                readBytes(uri)?.let { vm.stageAttachment(it, "image/jpeg") }
            }
        }
    }
    fun launchCamera() {
        attachScope.launch {
            // Do the cache sweep + file creation off the main thread (disk I/O can jank/ANR),
            // then return to the main thread to set the uri and launch the camera.
            val uri = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                // Prior captures are already staged in-memory, so their temp files are disposable.
                context.cacheDir.listFiles { f -> f.name.startsWith("capture_") }?.forEach { it.delete() }
                val file = File(context.cacheDir, "capture_${System.currentTimeMillis()}.jpg")
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            }
            captureUri = uri
            runCatching { takePhoto.launch(uri) }
        }
    }

    // Voice dictation: the system speech recognizer returns a transcript we append to the draft.
    // RecognizerIntent needs no RECORD_AUDIO (the system speech app owns the mic + permission).
    val speechAvailable = remember(context) { android.speech.SpeechRecognizer.isRecognitionAvailable(context) }
    val speech = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull().orEmpty()
            draft = appendDictation(draft, spoken)
        }
    }
    fun startDictation() {
        val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault().toLanguageTag())
            putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Speak your message")
        }
        runCatching { speech.launch(intent) }
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
                    IconButton(onClick = { searchOpen = !searchOpen; if (!searchOpen) query = "" }) {
                        Icon(
                            androidx.compose.material.icons.Icons.Rounded.Search,
                            contentDescription = "Search in chat",
                            tint = com.hermes.client.ui.components.AccentChrome.onBar,
                        )
                    }
                    AssistChip(
                        onClick = { modelSheetOpen = true },
                        modifier = Modifier.minimumInteractiveComponentSize(),
                        label = { Text(currentModel ?: "Model", maxLines = 1) },
                        trailingIcon = {
                            Icon(
                                Icons.Rounded.ArrowDropDown,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                        colors = androidx.compose.material3.AssistChipDefaults.assistChipColors(
                            labelColor = com.hermes.client.ui.components.AccentChrome.onBar,
                            trailingIconContentColor = com.hermes.client.ui.components.AccentChrome.onBar,
                        ),
                    )
                    StatusDot(connState)
                },
            )
        },
        bottomBar = {
            Column(
                // targetSdk 35+ forces edge-to-edge on Android 15+, so the window no
                // longer resizes for the keyboard — the composer must inset itself.
                // union() takes max(ime, navBars) so it lifts above the keyboard when
                // open and clears the nav bar when closed, without double-padding.
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
            ) {
                if (state.pendingAttachments.isNotEmpty()) {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.pendingAttachments, key = { it.id }) { a ->
                            // Decode off the main thread (produceState) — bitmap decode is CPU-heavy.
                            val thumb by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, a.id) {
                                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    decodeThumbnail(a.bytes, reqPx = 200)?.asImageBitmap()
                                }
                            }
                            Box(Modifier.size(56.dp)) {
                                val bmp = thumb
                                if (bmp != null) {
                                    Image(
                                        bitmap = bmp,
                                        contentDescription = "Attachment",
                                        modifier = Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)),
                                        contentScale = ContentScale.Crop,
                                    )
                                } else {
                                    Box(Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant))
                                }
                                // Remove badge: a dark circular scrim + white ✕ for contrast over any
                                // thumbnail, with a comfortably tappable target (a 20.dp icon was too small).
                                Box(
                                    Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(2.dp)
                                        .size(26.dp)
                                        .clip(androidx.compose.foundation.shape.CircleShape)
                                        .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f))
                                        .clickable { vm.removeAttachment(a.id) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Rounded.Close,
                                        contentDescription = "Remove attachment",
                                        tint = androidx.compose.ui.graphics.Color.White,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    com.hermes.client.ui.components.ProfileAvatar(activeProfile)
                    Spacer(Modifier.width(4.dp))
                    var attachMenu by remember { mutableStateOf(false) }
                    Box {
                        // Not gated on connection: attaching only stages locally now; the upload
                        // happens on Send, which canSend() already gates on being connected.
                        IconButton(onClick = { attachMenu = true }) {
                            Icon(Icons.Rounded.AttachFile, contentDescription = "Attach")
                        }
                        DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Camera") },
                                onClick = { attachMenu = false; launchCamera() },
                            )
                            DropdownMenuItem(
                                text = { Text("Photo library") },
                                onClick = {
                                    attachMenu = false
                                    pickPhotos.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                    )
                                },
                            )
                        }
                    }
                    if (speechAvailable) {
                        IconButton(onClick = { startDictation() }) {
                            Icon(
                                Icons.Rounded.Mic,
                                contentDescription = "Voice input",
                                tint = com.hermes.client.ui.components.AccentChrome.fabContainer,
                            )
                        }
                    }
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.weight(1f).focusRequester(focusRequester),
                        placeholder = { Text("Message Hermes…") },
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
                    color = LocalProfileAccent.current.accent,
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
                    color = LocalProfileAccent.current.accent,
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
                Column(Modifier.fillMaxSize()) {
                    if (searchOpen) {
                        val accent = LocalProfileAccent.current.accent
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                placeholder = { Text("Search in chat…") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            // Coerce into range: `currentMatch` can transiently exceed a shrunk match
                            // set before the reset effect runs — avoids a glitchy counter like "5/2".
                            val displayIndex = if (matches.isEmpty()) 0 else currentMatch.coerceAtMost(matches.lastIndex) + 1
                            Text(
                                "$displayIndex/${matches.size}",
                                color = accent,
                                modifier = Modifier.padding(horizontal = 8.dp),
                            )
                            IconButton(
                                onClick = { if (matches.isNotEmpty()) currentMatch = (currentMatch - 1 + matches.size) % matches.size },
                                enabled = matches.isNotEmpty(),
                            ) {
                                Icon(
                                    androidx.compose.material.icons.Icons.Rounded.KeyboardArrowUp,
                                    contentDescription = "Previous match",
                                    tint = accent,
                                )
                            }
                            IconButton(
                                onClick = { if (matches.isNotEmpty()) currentMatch = (currentMatch + 1) % matches.size },
                                enabled = matches.isNotEmpty(),
                            ) {
                                Icon(
                                    androidx.compose.material.icons.Icons.Rounded.KeyboardArrowDown,
                                    contentDescription = "Next match",
                                    tint = accent,
                                )
                            }
                            IconButton(onClick = { searchOpen = false; query = "" }) {
                                Icon(androidx.compose.material.icons.Icons.Rounded.Close, contentDescription = "Close search")
                            }
                        }
                    }
                    ChatMessageList(
                        state = state,
                        sessionId = sessionId,
                        listState = listState,
                        highlightIndex = highlightIndex,
                        isGenerating = state.isGenerating,
                        onEditResend = { text -> draft = text; focusRequester.requestFocus() },
                        onRegenerate = { vm.regenerate() },
                        modifier = Modifier.weight(1f),
                    )
                }
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
            text = bannerLabel(state),
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        // While Connecting the client is already trying — no point offering a manual retry.
        if (state !is ConnectionState.Connecting) {
            TextButton(
                onClick = onRetry,
                colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) { Text("Retry") }
        }
    }
}


/**
 * Decode [bytes] to a Bitmap downsampled so its largest side is roughly [reqPx] px — a chip thumbnail
 * never needs full resolution, and decoding a 12MP photo at full size (×ATTACH_CAP) risks OOM/jank.
 */
private fun decodeThumbnail(bytes: ByteArray, reqPx: Int): android.graphics.Bitmap? {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
    while (maxDim > 0 && maxDim / sample > reqPx * 2) sample *= 2
    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
    return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
}
