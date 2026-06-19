package com.hermes.client.ui.tools

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.network.SkillDto
import com.hermes.client.data.network.ToolsetDto
import com.hermes.client.data.repository.ToolsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ToolsUiState(
    val skills: List<SkillDto> = emptyList(),
    val toolsets: List<ToolsetDto> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

@HiltViewModel
class ToolsViewModel @Inject constructor(
    private val tools: ToolsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ToolsUiState())
    val state: StateFlow<ToolsUiState> = _state.asStateFlow()

    init { load() }

    fun load() = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true, error = null)
        val skills = runCatching { tools.skills() }.getOrNull()
        val toolsets = runCatching { tools.toolsets() }.getOrNull()
        _state.value = ToolsUiState(
            skills = skills ?: emptyList(),
            toolsets = toolsets ?: emptyList(),
            loading = false,
            error = if (skills == null && toolsets == null) "Failed to load" else null,
        )
    }

    fun toggleSkill(name: String, enabled: Boolean) = viewModelScope.launch {
        // Optimistic update; revert on failure.
        val prev = _state.value.skills
        _state.value = _state.value.copy(
            skills = prev.map { if (it.name == name) it.copy(enabled = enabled) else it },
        )
        runCatching { tools.toggleSkill(name, enabled) }
            .onFailure { _state.value = _state.value.copy(skills = prev) }
    }
}
