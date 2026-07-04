package com.hermes.client.ui.models
import androidx.compose.material.icons.automirrored.rounded.ArrowBack

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(
    onMenu: () -> Unit,
    vm: ModelsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); vm.clearMessage() }
    }

    Scaffold(
        topBar = {
            com.hermes.client.ui.components.HermesTopBar(
                title = "Models",
                navigationIcon = { IconButton(onClick = onMenu) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.loading -> com.hermes.client.ui.components.LoadingState()
                state.error != null -> Text(state.error!!, Modifier.align(Alignment.Center))
                else -> {
                    val favorites by vm.favorites.collectAsStateWithLifecycle()
                    val items = com.hermes.client.ui.models.modelSelectorRows(
                        providers = state.providers, favorites = favorites, query = state.query,
                        currentProvider = null, currentModel = null,
                    )
                    com.hermes.client.ui.models.ModelSelectorContent(
                        items = items,
                        query = state.query, onQueryChange = vm::onQuery,
                        scope = null, onScopeChange = {},          // Settings sets the global default only
                        onToggleFavorite = vm::toggleFavorite,
                        onSelect = { p, m -> vm.select(p, m) },
                        pending = false, error = null,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}
