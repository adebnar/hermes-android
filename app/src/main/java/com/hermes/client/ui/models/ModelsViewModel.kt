package com.hermes.client.ui.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.network.ModelProviderDto
import com.hermes.client.data.repository.ModelRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ModelsUiState(
    val providers: List<ModelProviderDto> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val message: String? = null,
)

@HiltViewModel
class ModelsViewModel @Inject constructor(
    private val models: ModelRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ModelsUiState())
    val state: StateFlow<ModelsUiState> = _state.asStateFlow()

    init { load() }

    fun load() = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true, error = null)
        runCatching { models.providers() }
            .onSuccess { _state.value = ModelsUiState(providers = it, loading = false) }
            .onFailure {
                _state.value = ModelsUiState(loading = false, error = it.message ?: "Failed to load models")
            }
    }

    fun select(provider: String, model: String) = viewModelScope.launch {
        runCatching { models.set(provider, model) }
            .onSuccess { _state.value = _state.value.copy(message = "Active model set to $model"); load() }
            .onFailure { _state.value = _state.value.copy(message = "Failed to set model: ${it.message}") }
    }

    fun clearMessage() { _state.value = _state.value.copy(message = null) }
}
