package com.hermes.client.ui.chat

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.hermes.client.ui.components.SlideToConfirm
import com.hermes.client.ui.theme.LocalProfileAccent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApprovalSheet(req: ApprovalRequest, onRespond: (ApprovalChoice) -> Unit, onDismiss: () -> Unit) {
    val accent = LocalProfileAccent.current
    val tier = tierFor(req.allowPermanent)
    val error = MaterialTheme.colorScheme.error
    val badge = if (tier == ApprovalTier.ELEVATED) error else accent.accent
    val label = req.patternKeys.firstOrNull()?.let { " · $it" } ?: ""

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text(
                (if (tier == ApprovalTier.ELEVATED) "Elevated" else "Approval needed") + label,
                style = MaterialTheme.typography.titleMedium,
                color = badge,
            )
            if (req.command.isNotBlank()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
                    Text(req.command, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (req.description.isNotBlank()) {
                Text(req.description, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 16.dp))
            }

            if (tier == ApprovalTier.STANDARD) {
                Button(
                    onClick = { onRespond(ApprovalChoice.ONCE) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        .semantics { contentDescription = "Allow once" },
                    colors = ButtonDefaults.buttonColors(containerColor = accent.accent, contentColor = accent.onAccent),
                ) { Text("Allow once") }
                OutlinedButton(
                    onClick = { onRespond(ApprovalChoice.SESSION) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        .semantics { contentDescription = "Allow this run" },
                ) { Text("Allow this run") }
                OutlinedButton(
                    onClick = { onRespond(ApprovalChoice.ALWAYS) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        .semantics { contentDescription = "Always allow" },
                ) { Text("Always allow") }
                TextButton(
                    onClick = { onRespond(ApprovalChoice.DENY) },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Deny" },
                ) { Text("Deny") }
            } else {
                // Elevated: Deny is prominent; Allow requires a deliberate slide.
                var thisRun by remember { mutableStateOf(false) }
                Button(
                    onClick = { onRespond(ApprovalChoice.DENY) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        .semantics { contentDescription = "Deny" },
                    colors = ButtonDefaults.buttonColors(containerColor = error),
                ) { Text("Deny") }
                TextButton(
                    onClick = { thisRun = !thisRun },
                    modifier = Modifier.fillMaxWidth()
                        .semantics { contentDescription = "Toggle allow scope" },
                ) {
                    Text(if (thisRun) "Scope: allow for this run" else "Scope: allow once")
                }
                SlideToConfirm(
                    label = if (thisRun) "  → slide to allow this run" else "  → slide to allow once",
                    accent = accent.accent,
                    onConfirm = { onRespond(if (thisRun) ApprovalChoice.SESSION else ApprovalChoice.ONCE) },
                    modifier = Modifier.padding(vertical = 8.dp)
                        .semantics { contentDescription = "Slide to allow" },
                )
            }
        }
    }
}
