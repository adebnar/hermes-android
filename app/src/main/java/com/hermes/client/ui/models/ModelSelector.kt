package com.hermes.client.ui.models

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
