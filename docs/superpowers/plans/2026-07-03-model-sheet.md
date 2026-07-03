# Model Sheet Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the plain in-chat model dropdown and the flat Settings→Models list with one searchable model sheet — pinned Favorites, per-provider groups, a This-chat/Default scope toggle, and switch errors shown inline.

**Architecture:** One DataStore favorites store + one pure `modelSelectorRows()` grouping/filter function (unit-tested) + one stateless `ModelSelectorContent` composable, reused by the in-chat `ModalBottomSheet` and the Settings screen. Each entry point keeps its scope behavior in its existing ViewModel. No new gateway endpoints.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), Hilt, DataStore, JUnit.

## Global Constraints

- **No bridge/gateway API changes.** Reuse `ModelRepository` (`GET /api/model/options`, `POST /api/model/set`) and the `/model … --session` slash via `ChatRepository.slashExec`.
- JDK 21 toolchain: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`. compileSdk/targetSdk 36, minSdk 26.
- Compile: `./gradlew :app:compileDebugKotlin --console=plain 2>&1 | tail -5`. Unit tests: `./gradlew :app:testDebugUnitTest --console=plain`. Beta APK: `./gradlew :app:assembleBeta`.
- Follow existing patterns: DataStore stores mirror `ProfileAccentStore`; Hilt providers mirror `provideProfileAccentStore` in `AppModule`; pure unit tests mirror `NotificationMapperTest`; MVVM + StateFlow.
- **No AI/assistant attribution** in commits, files, or PRs.

## File Structure

- Create `app/src/main/java/com/hermes/client/data/repository/ModelFavoritesStore.kt` — DataStore favorites + `favKey`.
- Create `app/src/main/java/com/hermes/client/ui/models/ModelSelector.kt` — types, pure `modelSelectorRows`, `ModelSelectorContent`, `ModelSelectorSheet`.
- Create `app/src/test/java/com/hermes/client/ui/models/ModelSelectorTest.kt` — pure-function tests.
- Modify `app/src/main/java/com/hermes/client/di/AppModule.kt` — provide `ModelFavoritesStore`.
- Modify `app/src/main/java/com/hermes/client/ui/chat/ChatViewModel.kt` — favorites/query/scope/sheet state + providers + `onSelectFromSheet` + `toggleFavorite`.
- Modify `app/src/main/java/com/hermes/client/ui/chat/ChatScreen.kt` — open `ModelSelectorSheet` from the model button.
- Modify `app/src/main/java/com/hermes/client/ui/models/ModelsViewModel.kt` — favorites + query + `items`.
- Modify `app/src/main/java/com/hermes/client/ui/models/ModelsScreen.kt` — render `ModelSelectorContent`.

---

### Task 1: `ModelFavoritesStore` + `favKey` + Hilt provider

**Files:**
- Create: `app/src/main/java/com/hermes/client/data/repository/ModelFavoritesStore.kt`
- Modify: `app/src/main/java/com/hermes/client/di/AppModule.kt` (after `provideProfileAccentStore`)

**Interfaces:**
- Produces: top-level `fun favKey(provider: String, model: String): String`; `class ModelFavoritesStore(context)` with `val favorites: Flow<Set<String>>`, `suspend fun toggle(provider: String, model: String)`.

- [ ] **Step 1: Create the store (mirrors `ProfileAccentStore`)**

```kotlin
package com.hermes.client.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.modelFavoritesDataStore by preferencesDataStore(name = "model_favorites")

/**
 * Stable key for a (provider, model) pair. NUL (`\u0000`) separator: provider slugs and model
 * names can contain ':', '/', '(', ')', '.', '-', and spaces, but never NUL — so the key never
 * collides across different (provider, model) splits.
 */
fun favKey(provider: String, model: String): String = "$provider\u0000$model"

/** Device-local, global (not per-profile) set of starred favorite models. */
class ModelFavoritesStore(private val context: Context) {
    private val key = stringSetPreferencesKey("favorites")

    val favorites: Flow<Set<String>> =
        context.modelFavoritesDataStore.data.map { it[key] ?: emptySet() }

    suspend fun toggle(provider: String, model: String) {
        val k = favKey(provider, model)
        context.modelFavoritesDataStore.edit { prefs ->
            val cur = prefs[key] ?: emptySet()
            prefs[key] = if (k in cur) cur - k else cur + k
        }
    }
}
```

- [ ] **Step 2: Add the Hilt provider in `AppModule.kt`** (immediately after `provideProfileAccentStore`)

```kotlin
    @Provides
    @Singleton
    fun provideModelFavoritesStore(
        @ApplicationContext context: Context,
    ): com.hermes.client.data.repository.ModelFavoritesStore =
        com.hermes.client.data.repository.ModelFavoritesStore(context)
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin --console=plain 2>&1 | tail -3`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/hermes/client/data/repository/ModelFavoritesStore.kt app/src/main/java/com/hermes/client/di/AppModule.kt
git commit -m "feat(models): model favorites DataStore + favKey"
```

---

### Task 2: Pure `modelSelectorRows` + types (TDD)

**Files:**
- Create: `app/src/main/java/com/hermes/client/ui/models/ModelSelector.kt`
- Test: `app/src/test/java/com/hermes/client/ui/models/ModelSelectorTest.kt`

**Interfaces:**
- Consumes: `com.hermes.client.data.network.ModelProviderDto(slug, name?, isCurrent, models: List<String>)`; `favKey` (Task 1).
- Produces: `enum class ModelScope { SESSION, DEFAULT }`; `data class ModelRow(provider, model, isFavorite, isCurrent)`; `sealed interface ModelListItem { data class Header(title, isCurrent=false); data class Row(row: ModelRow) }`; `fun modelSelectorRows(providers, favorites, query, currentProvider, currentModel): List<ModelListItem>`.

- [ ] **Step 1: Write the failing test**

```kotlin
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
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*ModelSelectorTest*' --console=plain 2>&1 | tail -6`
Expected: FAIL — unresolved `modelSelectorRows`/`ModelListItem`.

- [ ] **Step 3: Write the types + pure function**

```kotlin
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
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests '*ModelSelectorTest*' --console=plain 2>&1 | tail -6`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hermes/client/ui/models/ModelSelector.kt app/src/test/java/com/hermes/client/ui/models/ModelSelectorTest.kt
git commit -m "feat(models): pure modelSelectorRows grouping/filter with tests"
```

---

### Task 3: `ModelSelectorContent` + `ModelSelectorSheet` composables

**Files:**
- Modify: `app/src/main/java/com/hermes/client/ui/models/ModelSelector.kt` (append composables)

**Interfaces:**
- Consumes: `ModelListItem`, `ModelRow`, `ModelScope` (Task 2).
- Produces: `@Composable fun ModelSelectorContent(items, query, onQueryChange, scope, onScopeChange, onToggleFavorite, onSelect, pending, error, modifier)`; `@Composable fun ModelSelectorSheet(...same..., onDismiss)`.

- [ ] **Step 1: Append the composables to `ModelSelector.kt`**

```kotlin
// ---- UI (stateless) ----

import androidx.compose.foundation.layout.Arrangement
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
                        color = if (item.isCurrent) MaterialTheme.colorScheme.primary
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
                tint = if (row.isFavorite) MaterialTheme.colorScheme.primary
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
```

Add the missing `clickable` import at the top of the file's import block:

```kotlin
import androidx.compose.foundation.clickable
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin --console=plain 2>&1 | tail -6`
Expected: `BUILD SUCCESSFUL`. (If `SingleChoiceSegmentedButtonRow`/`SegmentedButton` need `@OptIn(ExperimentalMaterial3Api::class)`, add it to `ModelSelectorContent` too — the Material3 BOM in this repo supports segmented buttons.)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/hermes/client/ui/models/ModelSelector.kt
git commit -m "feat(models): stateless ModelSelectorContent + ModelSelectorSheet"
```

---

### Task 4: In-chat wiring (session scope)

**Files:**
- Modify: `app/src/main/java/com/hermes/client/ui/chat/ChatViewModel.kt`
- Modify: `app/src/main/java/com/hermes/client/ui/chat/ChatScreen.kt`

**Interfaces:**
- Consumes: `ModelFavoritesStore` (Task 1), `modelSelectorRows`/`ModelListItem`/`ModelScope`/`ModelSelectorSheet` (Tasks 2–3), existing `ChatRepository.slashExec`, `ModelRepository.providers()`, `models.set`.
- Produces (on `ChatViewModel`): `val providers: StateFlow<List<ModelProviderDto>>`, `val favorites: StateFlow<Set<String>>`, `val modelSheet: StateFlow<ModelSheetUi>`, `fun onSheetQuery(q)`, `fun onSheetScope(s)`, `fun onSelectFromSheet(provider, model)`, `fun toggleFavorite(provider, model)`.

- [ ] **Step 1: Add state + methods to `ChatViewModel`**

Add the DI parameter (constructor already has `chat, sessions, modelRepo, profileRepo, profileManager` — add one):

```kotlin
class ChatViewModel @Inject constructor(
    private val chat: ChatRepository,
    private val sessions: SessionRepository,
    private val modelRepo: ModelRepository,
    private val profileRepo: ProfileRepository,
    private val profileManager: ProfileManager,
    private val favoritesStore: com.hermes.client.data.repository.ModelFavoritesStore,
) : ViewModel() {
```

Add fields near the existing `_currentModel` (keep `_currentModel`; add a `_currentProvider` so the sheet can mark the current row, and a providers list for grouping):

```kotlin
    // Provider list for the model sheet (grouped by real slug); loaded alongside options.
    private val _providers = MutableStateFlow<List<com.hermes.client.data.network.ModelProviderDto>>(emptyList())
    val providers: kotlinx.coroutines.flow.StateFlow<List<com.hermes.client.data.network.ModelProviderDto>> = _providers.asStateFlow()

    // Provider of the confirmed session model (set together with _currentModel on a successful switch).
    private val _currentProvider = MutableStateFlow<String?>(null)
    val currentProvider: kotlinx.coroutines.flow.StateFlow<String?> = _currentProvider.asStateFlow()

    val favorites: kotlinx.coroutines.flow.StateFlow<Set<String>> =
        favoritesStore.favorites.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), emptySet())

    data class ModelSheetUi(
        val query: String = "",
        val scope: com.hermes.client.ui.models.ModelScope = com.hermes.client.ui.models.ModelScope.SESSION,
        val pending: Boolean = false,
        val error: String? = null,
    )
    private val _modelSheet = MutableStateFlow(ModelSheetUi())
    val modelSheet: kotlinx.coroutines.flow.StateFlow<ModelSheetUi> = _modelSheet.asStateFlow()
```

Load providers where options are loaded today (the `init` block has `launch { runCatching { _models.value = modelRepo.options() } }`) — add a sibling load:

```kotlin
            launch { runCatching { _providers.value = modelRepo.providers() } }
```

In the existing `selectModel(...)` success branch, also record the provider (find `_currentModel.value = model` and make it):

```kotlin
                .onSuccess { out ->
                    _currentModel.value = model
                    _currentProvider.value = provider
                    appendSystem(out?.takeIf { it.isNotBlank() } ?: "Model set to $model.")
                }
```

Add the sheet methods (place after `selectModel`):

```kotlin
    fun onSheetQuery(q: String) { _modelSheet.value = _modelSheet.value.copy(query = q) }
    fun onSheetScope(s: com.hermes.client.ui.models.ModelScope) {
        _modelSheet.value = _modelSheet.value.copy(scope = s, error = null)
    }
    fun toggleFavorite(provider: String, model: String) =
        viewModelScope.launch { favoritesStore.toggle(provider, model) }

    /**
     * Apply a model chosen in the sheet. SESSION → the /model --session slash (overrides just this
     * chat); DEFAULT → the global default via REST. On failure the gateway message is surfaced in
     * the sheet (kept open); on success the sheet is dismissed by the caller via [onSheetDismissed].
     */
    fun onSelectFromSheet(provider: String, model: String, onDone: () -> Unit) {
        _modelSheet.value = _modelSheet.value.copy(pending = true, error = null)
        viewModelScope.launch {
            when (_modelSheet.value.scope) {
                com.hermes.client.ui.models.ModelScope.SESSION ->
                    runCatching { chat.slashExec(sessionId, "/model $model --provider $provider --session") }
                        .onSuccess {
                            _currentModel.value = model
                            _currentProvider.value = provider
                            _modelSheet.value = ModelSheetUi()  // reset + clear pending/error
                            onDone()
                        }
                        .onFailure { e ->
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            _modelSheet.value = _modelSheet.value.copy(
                                pending = false,
                                error = e.message ?: "Couldn't switch model.",
                            )
                        }
                com.hermes.client.ui.models.ModelScope.DEFAULT ->
                    runCatching { modelRepo.set(provider, model) }
                        .onSuccess {
                            _modelSheet.value = _modelSheet.value.copy(pending = false, error = null)
                            onDone()
                        }
                        .onFailure { e ->
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            _modelSheet.value = _modelSheet.value.copy(
                                pending = false,
                                error = e.message ?: "Couldn't set default model.",
                            )
                        }
            }
        }
    }
```

> `sessionId`, `chat`, `modelRepo`, `viewModelScope`, `appendSystem` already exist. Ensure imports for `stateIn`/`SharingStarted` resolve (use the fully-qualified names above, or add `import kotlinx.coroutines.flow.stateIn` / `import kotlinx.coroutines.flow.SharingStarted`).

- [ ] **Step 2: Replace the dropdown with the sheet in `ChatScreen.kt`**

Collect the new state where the others are collected (near `val currentModel by vm.currentModel.collectAsStateWithLifecycle()`):

```kotlin
    val providers by vm.providers.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val currentProvider by vm.currentProvider.collectAsStateWithLifecycle()
    val modelSheet by vm.modelSheet.collectAsStateWithLifecycle()
    var modelSheetOpen by remember { mutableStateOf(false) }
```

Change the top-bar action to open the sheet (replace the `if (models.isNotEmpty()) { ModelPickerButton(...) }` block):

```kotlin
                actions = {
                    if (providers.isNotEmpty()) {
                        androidx.compose.material3.TextButton(onClick = { modelSheetOpen = true }) {
                            Text(currentModel ?: "Model", maxLines = 1)
                        }
                    }
                    StatusDot(connState)
                },
```

Render the sheet (place near the end of the composable body, e.g. just before the closing brace of the `Scaffold` content or alongside other overlays):

```kotlin
    if (modelSheetOpen) {
        val items = com.hermes.client.ui.models.modelSelectorRows(
            providers = providers, favorites = favorites, query = modelSheet.query,
            currentProvider = currentProvider, currentModel = currentModel,
        )
        com.hermes.client.ui.models.ModelSelectorSheet(
            items = items,
            query = modelSheet.query, onQueryChange = vm::onSheetQuery,
            scope = modelSheet.scope, onScopeChange = vm::onSheetScope,
            onToggleFavorite = vm::toggleFavorite,
            onSelect = { p, m -> vm.onSelectFromSheet(p, m) { modelSheetOpen = false } },
            pending = modelSheet.pending, error = modelSheet.error,
            onDismiss = { modelSheetOpen = false },
        )
    }
```

Delete the now-unused `private fun ModelPickerButton(...)` from `ChatScreen.kt` (and any now-unused `models` collection / imports for `DropdownMenu`, `DropdownMenuItem` if nothing else uses them).

- [ ] **Step 3: Compile + run the full unit suite**

Run: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --console=plain 2>&1 | grep -E "FAILED|BUILD" | head`
Expected: `BUILD SUCCESSFUL`, no `FAILED`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/hermes/client/ui/chat/ChatViewModel.kt app/src/main/java/com/hermes/client/ui/chat/ChatScreen.kt
git commit -m "feat(models): in-chat model sheet with This-chat/Default scope + inline errors"
```

---

### Task 5: Settings→Models wiring (default scope)

**Files:**
- Modify: `app/src/main/java/com/hermes/client/ui/models/ModelsViewModel.kt`
- Modify: `app/src/main/java/com/hermes/client/ui/models/ModelsScreen.kt`

**Interfaces:**
- Consumes: `ModelFavoritesStore` (Task 1), `modelSelectorRows`/`ModelListItem`/`ModelSelectorContent` (Tasks 2–3), existing `ModelRepository.providers()`/`set`.
- Produces (on `ModelsViewModel`): existing `state` gains `query` + `favorites` + inline error via `message`; `fun items(): List<ModelListItem>` derivation in the screen; `fun onQuery(q)`, `fun toggleFavorite(provider, model)`.

- [ ] **Step 1: Extend `ModelsViewModel`**

Add the store to the constructor and state, keeping the existing `providers`/`select`/`message`:

```kotlin
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
```

Add `query` to `ModelsUiState`:

```kotlin
data class ModelsUiState(
    val providers: List<ModelProviderDto> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val message: String? = null,
    val query: String = "",
)
```

- [ ] **Step 2: Render `ModelSelectorContent` in `ModelsScreen`**

Replace the `LazyColumn`/`ListItem` body (the current provider-loop with the hint item) with the shared content. Keep the `Scaffold`, the top bar, and the snackbar for `message`:

```kotlin
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
```

Add `import androidx.lifecycle.compose.collectAsStateWithLifecycle` if not already present. The keep-hint copy from the old screen is dropped — the sheet's search + provider grouping makes the intent clear, and the snackbar now says "Default set to …".

- [ ] **Step 3: Compile + full unit suite**

Run: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --console=plain 2>&1 | grep -E "FAILED|BUILD" | head`
Expected: `BUILD SUCCESSFUL`, no `FAILED`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/hermes/client/ui/models/ModelsViewModel.kt app/src/main/java/com/hermes/client/ui/models/ModelsScreen.kt
git commit -m "feat(models): Settings Models uses the shared selector (default scope)"
```

---

### Task 6: Package + on-device verification

**Files:** none (verification only).

- [ ] **Step 1: Build the beta APK**

Run: `./gradlew :app:assembleBeta --console=plain 2>&1 | grep -E "^e: |BUILD" | tail -2`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Verify on the emulator + mock gateway**

Boot the emulator, start the mock gateway (`$CLAUDE_JOB_DIR/tmp/mockgw.py`), install the beta (`adb -e install -r app/build/outputs/apk/beta/app-beta.apk`), point it at `http://10.0.2.2:8899`, then confirm:
- Open a chat → tap the model button → the sheet shows a search box, the **This chat / Default** toggle, and models grouped by provider.
- Star a model → it appears in a **Favorites** section at the top (and stays starred in its group); unstar removes it.
- Type in search → the list filters by model and provider substring.
- Pick a model with **This chat** → sheet closes, the button label updates to the model.
- Flip to **Default**, pick a model → an inline confirmation, no session change.
- In **Settings → Models**, the same grouped/searchable list renders with no scope toggle.
- Force a failure (e.g. a mock/gateway that rejects the slash) → the error text shows **inline in the sheet**, which stays open.

- [ ] **Step 3: Commit (if any verification-only fixups were needed)**

```bash
git add -A
git commit -m "chore(models): model sheet verification fixups"   # only if needed
```

---

## Self-Review

- **Spec coverage:** search + Favorites + per-provider groups (Task 2 `modelSelectorRows`, Task 3 UI) ✓; global DataStore favorites (Task 1) ✓; This-chat/Default scope toggle, in-chat only (Task 4; Settings passes `scope=null`, Task 5) ✓; descriptions omitted (row shows model + provider slug, Task 3) ✓; inline switch-error in the sheet (Task 4 `onSelectFromSheet` failure → `modelSheet.error`) ✓; provider `is_current` marked at header, session current marked at row (Tasks 2, 4, 5) ✓; unit-tested pure core mirroring `NotificationMapperTest` (Task 2) ✓; no bridge changes (reuses `ModelRepository` + slash) ✓; on-device sanity (Task 6) ✓.
- **Placeholder scan:** every code step contains full code; the only "if needed" is a conditional verification commit, not a deferred implementation.
- **Type consistency:** `modelSelectorRows(providers, favorites, query, currentProvider, currentModel)`, `ModelListItem.{Header(title,isCurrent),Row(row)}`, `ModelRow(provider,model,isFavorite,isCurrent)`, `ModelScope.{SESSION,DEFAULT}`, `favKey(provider,model)`, `ModelFavoritesStore.{favorites,toggle}`, `ModelSelectorContent(...scope: ModelScope?...)`, `ModelSelectorSheet(...onDismiss)` are used consistently across Tasks 1–5. `ChatViewModel` gains `providers/currentProvider/favorites/modelSheet/onSheetQuery/onSheetScope/onSelectFromSheet/toggleFavorite`; `ModelsViewModel` gains `favorites/onQuery/toggleFavorite` and `ModelsUiState.query` — all referenced consistently by their screens.

**Ordering note:** Tasks 1→3 are the pure/testable core and compile independently. Task 4 (in-chat) and Task 5 (Settings) both depend on Tasks 1–3 but are independent of each other; either order works. Task 6 verifies the whole.
