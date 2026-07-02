package com.hermes.client.ui.settings
import androidx.compose.material.icons.automirrored.rounded.ArrowBack

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.client.data.diagnostics.DebugLog
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    onBack: () -> Unit,
    vm: DiagnosticsViewModel = hiltViewModel(),
) {
    val enabled by vm.enabled.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            com.hermes.client.ui.components.HermesTopBar(
                title = "Diagnostics",
                navigationIcon = { IconButton(onClick = onBack) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            ListItem(
                headlineContent = { Text("Diagnostic logging") },
                supportingContent = {
                    Text("Records network and session activity to diagnose errors like \"message not found\". Off by default; the session token is never logged.")
                },
                trailingContent = {
                    Switch(checked = enabled, onCheckedChange = { vm.setEnabled(it) })
                },
            )
            HorizontalDivider()

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "Hermes diagnostic log")
                            putExtra(Intent.EXTRA_TEXT, vm.export())
                        }
                        context.startActivity(Intent.createChooser(send, "Share diagnostic log"))
                    },
                ) { Text("Share") }
                OutlinedButton(onClick = { vm.clear() }) { Text("Clear") }
            }
            HorizontalDivider()

            if (entries.isEmpty()) {
                Text(
                    if (enabled) "Logging is on. Reproduce the issue and entries will appear here."
                    else "Turn on Diagnostic logging, then reproduce the issue.",
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // Newest first for quick reading.
                val reversed = entries.asReversed()
                LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(reversed, key = { i, _ -> "$i" }) { _, e -> LogRow(e) }
                }
            }
        }
    }
}

private val timeFmt =
    DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

@Composable
private fun LogRow(e: DebugLog.LogEntry) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            "${timeFmt.format(Instant.ofEpochMilli(e.timeMillis))}  ${e.category}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            e.message,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 6,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
