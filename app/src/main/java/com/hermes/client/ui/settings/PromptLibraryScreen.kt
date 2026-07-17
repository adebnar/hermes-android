package com.hermes.client.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.repository.PromptStore
import com.hermes.client.data.repository.SavedPrompt
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class PromptLibraryViewModel @Inject constructor(private val store: PromptStore) : ViewModel() {
    val prompts: StateFlow<List<SavedPrompt>> =
        store.prompts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Create (id == null) or edit an existing prompt. Blank title falls back to the body's first line. */
    fun save(id: String?, title: String, body: String) {
        val cleanBody = body.trim()
        if (cleanBody.isEmpty() && title.isBlank()) return
        val cleanTitle = title.trim().ifBlank { cleanBody.lineSequence().firstOrNull()?.take(60).orEmpty() }
        viewModelScope.launch { store.upsert(SavedPrompt(id ?: UUID.randomUUID().toString(), cleanTitle, cleanBody)) }
    }

    fun delete(id: String) = viewModelScope.launch { store.delete(id) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromptLibraryScreen(
    onBack: () -> Unit,
    vm: PromptLibraryViewModel = hiltViewModel(),
) {
    val prompts by vm.prompts.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<SavedPrompt?>(null) }
    var adding by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            com.hermes.client.ui.components.HermesTopBar(
                title = "Saved prompts",
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
                actions = {
                    IconButton(onClick = { adding = true }) {
                        Icon(Icons.Rounded.Add, contentDescription = "New")
                    }
                },
            )
        },
    ) { padding ->
        if (prompts.isEmpty()) {
            Text(
                "No saved prompts yet. Tap New to add one.",
                modifier = Modifier.padding(padding).padding(24.dp),
            )
        } else {
            LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                items(prompts, key = { it.id }) { p ->
                    ListItem(
                        headlineContent = { Text(p.title) },
                        supportingContent = { Text(p.body.lineSequence().firstOrNull().orEmpty()) },
                        trailingContent = {
                            IconButton(onClick = { vm.delete(p.id) }) {
                                Icon(Icons.Rounded.Delete, contentDescription = "Delete")
                            }
                        },
                        modifier = Modifier.clickable { editing = p },
                    )
                }
            }
        }
    }

    if (adding || editing != null) {
        val current = editing
        var title by remember(current) { mutableStateOf(current?.title.orEmpty()) }
        var body by remember(current) { mutableStateOf(current?.body.orEmpty()) }
        val dismiss = { adding = false; editing = null }
        AlertDialog(
            onDismissRequest = dismiss,
            title = { Text(if (current == null) "New prompt" else "Edit prompt") },
            text = {
                androidx.compose.foundation.layout.Column {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Title") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = body,
                        onValueChange = { body = it },
                        label = { Text("Prompt") },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { vm.save(current?.id, title, body); dismiss() },
                    enabled = title.isNotBlank() || body.isNotBlank(),
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
        )
    }
}
