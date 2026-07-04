package com.hermes.client.ui.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.network.ModelProviderDto
import com.hermes.client.data.repository.ModelRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ModelsUiState(
    val providers: List<ModelProviderDto> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val message: String? = null,
    val query: String = "",
)

@HiltViewModel
class ModelsViewModel @Inject constructor(
    private val models: ModelRepository,
    private val favoritesStore: com.hermes.client.data.repository.ModelFavoritesStore,
) : ViewModel() {
    private val _state = MutableStateFlow(ModelsUiState())
    val state: StateFlow<ModelsUiState> = _state.asStateFlow()

    val favorites: StateFlow<Set<String>> =
        favoritesStore.favorites.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), emptySet())

    init { load() }

    fun load() = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true, error = null)
        runCatching { models.providers() }
            .onSuccess { _state.value = _state.value.copy(providers = it, loading = false, error = null) }
            .onFailure { _state.value = _state.value.copy(loading = false, error = it.message ?: "Failed to load models") }
    }

    fun onQuery(q: String) { _state.value = _state.value.copy(query = q) }

    fun toggleFavorite(provider: String, model: String) =
        viewModelScope.launch { favoritesStore.toggle(provider, model) }

    fun select(provider: String, model: String) = viewModelScope.launch {
        runCatching { models.set(provider, model) }
            .onSuccess { _state.value = _state.value.copy(message = "Default set to $model"); load() }
            .onFailure { _state.value = _state.value.copy(message = "Failed to set model: ${it.message}") }
    }

    fun clearMessage() { _state.value = _state.value.copy(message = null) }
}
