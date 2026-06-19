package com.hermes.client.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.client.data.network.ConnectionState
import com.hermes.client.data.network.ModelOptionDto
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
    val models by vm.models.collectAsStateWithLifecycle()
    val activeProfile by vm.activeProfile.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf("") }
    val connected = connState is ConnectionState.Connected
    val canSend = connected && draft.isNotBlank() && !state.isGenerating

    fun submit() {
        if (!canSend) return
        vm.send(draft)
        draft = ""
    }

    // I1: route back to Setup when the server returns 401
    LaunchedEffect(unauthorized) {
        if (unauthorized) onUnauthorized()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onMenu) { Text("☰") } },
                title = {
                    Column {
                        Text("Chat")
                        activeProfile?.let {
                            Text("Profile: $it", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                },
                actions = {
                    if (models.isNotEmpty()) {
                        ModelPickerButton(
                            models = models,
                            onSelect = { vm.selectModel(it.provider, it.model) },
                        )
                    }
                    StatusDot(connState)
                },
            )
        },
        bottomBar = {
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message Hermes…") },
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submit() }),
                )
                Spacer(Modifier.width(8.dp))
                if (state.isGenerating) {
                    IconButton(onClick = { vm.stop() }) { Text("■") }
                } else {
                    IconButton(onClick = { submit() }, enabled = canSend) { Text("➤") }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (!connected) {
                ConnectionBanner(connState, onRetry = { vm.reconnect() })
            }
            ChatMessageList(state = state, modifier = Modifier.weight(1f))
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

@Composable
private fun ModelPickerButton(
    models: List<ModelOptionDto>,
    onSelect: (ModelOptionDto) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    TextButton(onClick = { expanded = true }) {
        Text("Model")
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            models.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label ?: "${option.provider}/${option.model}") },
                    onClick = { onSelect(option); expanded = false },
                )
            }
        }
    }
}

