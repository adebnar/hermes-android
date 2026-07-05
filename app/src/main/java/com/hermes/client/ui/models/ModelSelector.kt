package com.hermes.client.ui.models

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.hermes.client.ui.theme.LocalProfileAccent
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermes.client.data.network.ModelProviderDto
import com.hermes.client.data.repository.favKey

/** Which model slot a selection applies to. */
enum class ModelScope { SESSION, DEFAULT }

data class ModelRow(
    val provider: String,       // real provider slug
    val model: String,
    val isFavorite: Boolean,
    val isCurrent: Boolean,
)

sealed interface ModelListItem {
    data class Header(val title: String, val isCurrent: Boolean = false) : ModelListItem
    data class Row(val row: ModelRow) : ModelListItem
}

/**
 * Pure: flattens providers into a display list — a pinned Favorites section (only favorites that
 * survive the query), then one header + rows per provider that has >=1 surviving row. A favorited
 * model appears in both places. Deterministic: providers and models keep their input order.
 */
fun modelSelectorRows(
    providers: List<ModelProviderDto>,
    favorites: Set<String>,
    query: String,
    currentProvider: String?,
    currentModel: String?,
): List<ModelListItem> {
    val q = query.trim().lowercase()
    fun matches(provider: String, model: String) =
        q.isEmpty() || model.lowercase().contains(q) || provider.lowercase().contains(q)
    fun rowOf(provider: String, model: String) = ModelRow(
        provider = provider,
        model = model,
        isFavorite = favKey(provider, model) in favorites,
        isCurrent = provider == currentProvider && model == currentModel,
    )

    val items = mutableListOf<ModelListItem>()

    val favRows = providers
        .flatMap { p -> p.models.map { m -> p.slug to m } }
        .filter { (prov, m) -> favKey(prov, m) in favorites && matches(prov, m) }
        .map { (prov, m) -> rowOf(prov, m) }
    if (favRows.isNotEmpty()) {
        items += ModelListItem.Header("Favorites")
        favRows.forEach { items += ModelListItem.Row(it) }
    }

    for (p in providers) {
        val rows = p.models.filter { matches(p.slug, it) }.map { rowOf(p.slug, it) }
        if (rows.isEmpty()) continue
        items += ModelListItem.Header(p.name ?: p.slug, isCurrent = p.isCurrent)
        rows.forEach { items += ModelListItem.Row(it) }
    }
    return items
}

// ---- UI (stateless) ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSelectorContent(
    items: List<ModelListItem>,
    query: String,
    onQueryChange: (String) -> Unit,
    scope: ModelScope?,
    onScopeChange: (ModelScope) -> Unit,
    onToggleFavorite: (provider: String, model: String) -> Unit,
    onSelect: (provider: String, model: String) -> Unit,
    pending: Boolean,
    error: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        if (scope != null) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                SegmentedButton(
                    selected = scope == ModelScope.SESSION,
                    onClick = { onScopeChange(ModelScope.SESSION) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) { Text("This chat") }
                SegmentedButton(
                    selected = scope == ModelScope.DEFAULT,
                    onClick = { onScopeChange(ModelScope.DEFAULT) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) { Text("Default") }
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text("Search models…") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        )

        if (error != null) {
            Text(
                error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
        }
        if (pending) {
            CircularProgressIndicator(Modifier.padding(vertical = 8.dp))
        }

        LazyColumn(Modifier.fillMaxWidth()) {
            items(items) { item ->
                when (item) {
                    is ModelListItem.Header -> Text(
                        text = item.title + if (item.isCurrent) "  (current)" else "",
                        style = MaterialTheme.typography.titleSmall,
                        color = if (item.isCurrent) LocalProfileAccent.current.accent
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 4.dp),
                    )
                    is ModelListItem.Row -> ModelRowItem(item.row, onToggleFavorite, onSelect)
                }
            }
        }
    }
}

@Composable
private fun ModelRowItem(
    row: ModelRow,
    onToggleFavorite: (String, String) -> Unit,
    onSelect: (String, String) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onSelect(row.provider, row.model) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                row.model + if (row.isCurrent) "  ·  current" else "",
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                row.provider,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = { onToggleFavorite(row.provider, row.model) }) {
            Icon(
                imageVector = if (row.isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                contentDescription = if (row.isFavorite) "Unfavorite" else "Favorite",
                tint = if (row.isFavorite) LocalProfileAccent.current.accent
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSelectorSheet(
    items: List<ModelListItem>,
    query: String,
    onQueryChange: (String) -> Unit,
    scope: ModelScope?,
    onScopeChange: (ModelScope) -> Unit,
    onToggleFavorite: (provider: String, model: String) -> Unit,
    onSelect: (provider: String, model: String) -> Unit,
    pending: Boolean,
    error: String?,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Text(
            "Select model",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 4.dp),
        )
        ModelSelectorContent(
            items = items, query = query, onQueryChange = onQueryChange,
            scope = scope, onScopeChange = onScopeChange,
            onToggleFavorite = onToggleFavorite, onSelect = onSelect,
            pending = pending, error = error,
            modifier = Modifier.padding(bottom = 24.dp),
        )
    }
}
