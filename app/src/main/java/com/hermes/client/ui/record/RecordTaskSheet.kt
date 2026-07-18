package com.hermes.client.ui.record

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hermes.client.ui.theme.LocalProfileAccent

/**
 * Bottom sheet driving the record -> transcribe -> new-chat flow. The host (SessionsScreen) owns
 * showing/hiding this sheet and starts recording when it opens; this composable only renders
 * [RecordUi] and forwards taps.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordTaskSheet(
    ui: RecordUi,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val accent = LocalProfileAccent.current.accent
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val error = ui.error
            when {
                error != null -> {
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                    Button(onClick = onRetry, modifier = Modifier.padding(bottom = 8.dp)) {
                        Text("Try again")
                    }
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                ui.phase == RecordPhase.RECORDING -> {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .padding(bottom = 16.dp)
                            .background(accent, CircleShape)
                            .semantics { contentDescription = "Recording in progress" },
                    )
                    Text("Recording…", modifier = Modifier.padding(bottom = 16.dp))
                    Button(onClick = onStop, modifier = Modifier.padding(bottom = 8.dp)) {
                        Text("Stop")
                    }
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }
                ui.phase == RecordPhase.TRANSCRIBING -> {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(bottom = 16.dp)
                            .semantics { contentDescription = "Transcribing recording" },
                    )
                    Text("Transcribing…")
                }
                else -> {
                    Text("Getting ready…")
                }
            }
        }
    }
}
