package com.hermes.client.ui.models

import com.hermes.client.data.network.ModelProviderDto
import com.hermes.client.data.repository.favKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelSelectorTest {
    private val providers = listOf(
        ModelProviderDto(slug = "openai-codex", name = null, isCurrent = true,
            models = listOf("gpt-5.5", "gpt-5.5-mini")),
        ModelProviderDto(slug = "OpenRouter", name = "OpenRouter", isCurrent = false,
            models = listOf("stepfun/step-3.7-flash:free")),
    )

    private fun rows(items: List<ModelListItem>) =
        items.filterIsInstance<ModelListItem.Row>().map { it.row }
    private fun headers(items: List<ModelListItem>) =
        items.filterIsInstance<ModelListItem.Header>().map { it.title }

    @Test fun groups_by_provider_with_headers_in_input_order() {
        val items = modelSelectorRows(providers, emptySet(), "", null, null)
        assertEquals(listOf("openai-codex", "OpenRouter"), headers(items))
        assertEquals(3, rows(items).size)
    }

    @Test fun favorites_pinned_first_and_also_shown_in_group() {
        val favs = setOf(favKey("openai-codex", "gpt-5.5"))
        val items = modelSelectorRows(providers, favs, "", null, null)
        assertEquals("Favorites", (items.first() as ModelListItem.Header).title)
        // appears in the Favorites section AND its provider group, both flagged
        assertEquals(2, rows(items).count { it.model == "gpt-5.5" && it.isFavorite })
    }

    @Test fun no_favorites_header_when_none_present() {
        val items = modelSelectorRows(providers, emptySet(), "", null, null)
        assertTrue(headers(items).none { it == "Favorites" })
    }

    @Test fun query_filters_model_and_provider_case_insensitively() {
        assertEquals(
            listOf("stepfun/step-3.7-flash:free"),
            rows(modelSelectorRows(providers, emptySet(), "STEP", null, null)).map { it.model },
        )
        assertEquals(2, rows(modelSelectorRows(providers, emptySet(), "codex", null, null)).size)
        assertTrue(modelSelectorRows(providers, emptySet(), "zzz", null, null).isEmpty())
    }

    @Test fun marks_exactly_the_current_row() {
        val items = modelSelectorRows(providers, emptySet(), "", "openai-codex", "gpt-5.5")
        val current = rows(items).filter { it.isCurrent }
        assertEquals(1, current.size)
        assertEquals("gpt-5.5", current[0].model)
        assertEquals("openai-codex", current[0].provider)
    }

    @Test fun provider_header_marks_the_current_provider() {
        val items = modelSelectorRows(providers, emptySet(), "", null, null)
        val codex = items.filterIsInstance<ModelListItem.Header>().first { it.title == "openai-codex" }
        assertTrue(codex.isCurrent)
    }
}
