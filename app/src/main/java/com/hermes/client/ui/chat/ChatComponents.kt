package com.hermes.client.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Role
import com.hermes.client.domain.ToolCall
import com.hermes.client.domain.ToolStatus
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography

@Composable
fun ChatMessageList(
    state: ChatUiState,
    modifier: Modifier = Modifier,
    listState: androidx.compose.foundation.lazy.LazyListState = rememberLazyListState(),
) {
    val lastIndex = state.messages.lastIndex
    // Length of the last (streaming) message: changes on every delta so we follow the stream.
    val tailLen = state.messages.lastOrNull()?.text?.length ?: 0

    // On first load of a non-empty thread (opening an existing session), jump straight to the
    // newest message so the latest reply is visible immediately — otherwise the list stays at
    // the top and the most recent response looks missing until you scroll down by hand.
    var landed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.messages.isNotEmpty()) {
        if (state.messages.isEmpty() || landed) return@LaunchedEffect
        // History loads after the list is already composed, so wait until the LazyColumn has
        // actually measured the loaded items before scrolling — jumping before first layout
        // lands short of the bottom.
        snapshotFlow { listState.layoutInfo.totalItemsCount }
            .filter { it >= state.messages.size }
            .first()
        // Converge to the ABSOLUTE bottom — the end of the newest message, not just the last
        // item's top. Scroll purely by the list's own "can I still scroll down?" signal rather
        // than a captured message index (which could go stale if the thread changes mid-loop):
        // keep scrolling until nothing remains below, letting a frame measure the next items
        // between steps.
        var guard = 0
        while (guard++ < 60 && listState.canScrollForward) {
            listState.scrollBy(100_000f)
            withFrameNanos {} // let a layout pass measure the next items, then continue
        }
        landed = true
    }

    // After the initial jump, follow new content. Always follow right after the user sends
    // (they want to see their message and the reply); otherwise only when already near the
    // bottom, so scrolling back through history isn't yanked away mid-stream.
    LaunchedEffect(state.messages.size, tailLen) {
        if (lastIndex < 0 || !landed) return@LaunchedEffect
        val justSent = state.messages.lastOrNull()?.role == Role.USER
        val visible = listState.layoutInfo.visibleItemsInfo
        val atBottom = visible.isEmpty() || (visible.lastOrNull()?.index ?: 0) >= lastIndex - 1
        if (justSent || atBottom) listState.animateScrollToItem(lastIndex)
    }

    if (state.messages.isEmpty()) {
        Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                "Send a message to start the conversation.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Key by position as well as id: the gateway reuses the model name as the
        // message id across a session's turns, so ids are NOT guaranteed unique.
        // The index makes the key collision-proof regardless of id source (the list
        // is append-only, so an item's index is stable across recompositions).
        itemsIndexed(
            state.messages,
            key = { index, msg -> "$index:${msg.id}" },
        ) { _, msg -> MessageBubble(msg) }
    }
}

// Hybrid layout: the user's own turns stay as compact right-aligned bubbles, while the agent's
// turns render full-width and document-style (like the desktop / modern AI apps) so long answers,
// code, and tool traces have room to breathe and read as a transcript rather than an SMS thread.
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(msg: ChatMessage) {
    when (msg.role) {
        Role.USER -> UserBubble(msg)
        else -> AssistantTurn(msg)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserBubble(msg: ChatMessage) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val bg = if (msg.isError) MaterialTheme.colorScheme.errorContainer
    else MaterialTheme.colorScheme.primaryContainer
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Column(
            Modifier
                .widthIn(max = 320.dp)
                // Asymmetric corners (a small "tail" corner) mark this as the sender's bubble.
                .clip(RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp))
                .background(bg)
                .combinedClickable(onClick = {}, onLongClick = { copyToClipboard(msg.text, clipboard, context) })
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            if (msg.text.isNotBlank()) Text(msg.text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AssistantTurn(msg: ChatMessage) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = {}, onLongClick = { copyToClipboard(msg.text, clipboard, context) })
            .padding(vertical = 2.dp),
    ) {
        if (msg.thinking.isNotBlank()) ThinkingCard(msg.thinking)
        msg.tools.forEach { ToolCard(it) }
        if (msg.text.isNotBlank()) {
            if (msg.isError) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        msg.text,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            } else {
                Markdown(content = msg.text, colors = markdownColor(), typography = markdownTypography())
            }
        }
        if (msg.isStreaming && msg.text.isBlank() && msg.tools.isEmpty()) {
            TypingIndicator()
        }
    }
}

private fun copyToClipboard(
    text: String,
    clipboard: androidx.compose.ui.platform.ClipboardManager,
    context: android.content.Context,
) {
    if (text.isNotBlank()) {
        clipboard.setText(AnnotatedString(text))
        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
    }
}

/** Three pulsing dots while the agent composes its first token — replaces the literal "…". */
@Composable
private fun TypingIndicator() {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 600, delayMillis = i * 160, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot$i",
            )
            Box(
                Modifier
                    .padding(end = 5.dp)
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)),
            )
        }
    }
}

@Composable
private fun ThinkingCard(text: String) {
    var expanded by remember { mutableStateOf(false) }
    AssistChip(
        onClick = { expanded = !expanded },
        label = { Text(if (expanded) "Hide thinking" else "Thinking") },
    )
    if (expanded) Text(text, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun ToolCard(tool: ToolCall) {
    // Product mode hides raw tool payloads; Technical shows them.
    val technical = com.hermes.client.ui.theme.LocalToolCallTechnical.current
    Surface(
        tonalElevation = 2.dp,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (tool.status == ToolStatus.RUNNING) Icons.Rounded.PlayArrow else Icons.Rounded.Check,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = if (tool.status == ToolStatus.RUNNING) "${tool.name}…" else tool.name,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
            if (technical && tool.output.isNotBlank()) {
                Text(
                    tool.output,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 6,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
fun ApprovalDialog(prompt: String, onApprove: () -> Unit, onDeny: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDeny,
        title = { Text("Approval requested") },
        text = { Text(prompt) },
        confirmButton = { TextButton(onClick = onApprove) { Text("Approve") } },
        dismissButton = { TextButton(onClick = onDeny) { Text("Deny") } },
    )
}
