package com.hermes.client.ui.settings

import androidx.lifecycle.ViewModel
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
