package com.hermes.client.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Role
import com.hermes.client.domain.ToolCall
import com.hermes.client.domain.ToolStatus
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography

@Composable
fun ChatMessageList(state: ChatUiState, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(state.messages, key = { it.id }) { msg -> MessageBubble(msg) }
    }
}

@Composable
private fun MessageBubble(msg: ChatMessage) {
    val isUser = msg.role == Role.USER
    val bubbleColor = when {
        msg.isError -> MaterialTheme.colorScheme.errorContainer
        isUser -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(bubbleColor)
                .padding(12.dp),
        ) {
            if (msg.thinking.isNotBlank()) ThinkingCard(msg.thinking)
            msg.tools.forEach { ToolCard(it) }
            if (isUser) {
                // Plain text for user messages
                if (msg.text.isNotBlank()) Text(msg.text, style = MaterialTheme.typography.bodyMedium)
            } else {
                // Markdown for assistant messages
                if (msg.text.isNotBlank()) {
                    Markdown(
                        content = msg.text,
                        colors = markdownColor(),
                        typography = markdownTypography(),
                    )
                }
            }
            if (msg.isStreaming && msg.text.isBlank() && msg.tools.isEmpty()) {
                Text("…", style = MaterialTheme.typography.bodyMedium)
            }
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
    Surface(
        tonalElevation = 2.dp,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        Column(Modifier.padding(8.dp)) {
            Text(
                text = if (tool.status == ToolStatus.RUNNING) "▶ ${tool.name}…" else "✓ ${tool.name}",
                style = MaterialTheme.typography.labelMedium,
            )
            if (tool.output.isNotBlank()) {
                Text(tool.output, style = MaterialTheme.typography.bodySmall, maxLines = 6)
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
