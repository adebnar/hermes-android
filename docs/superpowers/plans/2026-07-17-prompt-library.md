# Prompt / Snippet Library Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Checkbox steps.

**Goal:** Device-local saved prompts: a store, a composer picker (appends into the draft), and a Settings manage screen.

**Spec:** `docs/superpowers/specs/2026-07-17-prompt-library-design.md`

## Global Constraints
- Client-only, device-local (no gateway/sync). No AI attribution.
- Build env: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`.
- Branch `feature/prompt-library` (off `dev`).

---

### Task 1: Model + pure helpers

**Files:** Create `app/src/main/java/com/hermes/client/data/repository/PromptStore.kt` (model + pure helpers only in this task); Test `app/src/test/java/com/hermes/client/data/repository/PromptStoreTest.kt`

- [ ] **Step 1: Write the failing test** `PromptStoreTest.kt`:
```kotlin
package com.hermes.client.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class PromptStoreTest {
    private val a = SavedPrompt("1", "Greet", "Say hello")
    private val b = SavedPrompt("2", "Bye", "Say bye")

    @Test fun decode_bad_or_empty_is_empty_list() {
        assertEquals(emptyList<SavedPrompt>(), decodePrompts(null))
        assertEquals(emptyList<SavedPrompt>(), decodePrompts(""))
        assertEquals(emptyList<SavedPrompt>(), decodePrompts("not json"))
        assertEquals(emptyList<SavedPrompt>(), decodePrompts("{}"))
    }

    @Test fun encode_decode_round_trip() {
        assertEquals(listOf(a, b), decodePrompts(encodePrompts(listOf(a, b))))
    }

    @Test fun upsert_appends_new_and_replaces_existing() {
        assertEquals(listOf(a, b), upsertPrompt(listOf(a), b))
        val edited = a.copy(title = "Hi")
        assertEquals(listOf(edited, b), upsertPrompt(listOf(a, b), edited)) // replaced in place, order kept
    }

    @Test fun delete_removes_by_id_and_noops_when_absent() {
        assertEquals(listOf(b), deletePrompt(listOf(a, b), "1"))
        assertEquals(listOf(a, b), deletePrompt(listOf(a, b), "nope"))
    }
}
```

- [ ] **Step 2:** Run `./gradlew :app:testDebugUnitTest --tests "com.hermes.client.data.repository.PromptStoreTest"` → FAIL (unresolved).

- [ ] **Step 3: Implement** the model + pure helpers in `PromptStore.kt`:
```kotlin
package com.hermes.client.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException

/** A user-saved reusable prompt (device-local). */
@Serializable
data class SavedPrompt(val id: String, val title: String, val body: String)

private val promptJson = Json { ignoreUnknownKeys = true }

/** Decode the stored JSON list; never throws — corrupt/absent → empty. */
fun decodePrompts(raw: String?): List<SavedPrompt> =
    runCatching { promptJson.decodeFromString<List<SavedPrompt>>(raw ?: "[]") }.getOrDefault(emptyList())

fun encodePrompts(list: List<SavedPrompt>): String = promptJson.encodeToString(list)

/** Replace the element whose id matches [p], preserving position; otherwise append. */
fun upsertPrompt(list: List<SavedPrompt>, p: SavedPrompt): List<SavedPrompt> =
    if (list.any { it.id == p.id }) list.map { if (it.id == p.id) p else it } else list + p

fun deletePrompt(list: List<SavedPrompt>, id: String): List<SavedPrompt> = list.filterNot { it.id == id }
```
(The `PromptStore` class is added in Task 2 — this task ships only the `@Serializable` model + the four pure functions.)

- [ ] **Step 4:** Run the test → PASS (4 tests).

- [ ] **Step 5: Commit**
```bash
git add app/src/main/java/com/hermes/client/data/repository/PromptStore.kt \
        app/src/test/java/com/hermes/client/data/repository/PromptStoreTest.kt
git commit -m "feat: SavedPrompt model + pure prompt-list helpers"
```

---

### Task 2: PromptStore + DI + ViewModels

**Files:**
- Modify: `app/src/main/java/com/hermes/client/data/repository/PromptStore.kt` (add the class)
- Modify: `app/src/main/java/com/hermes/client/di/AppModule.kt`
- Modify: `app/src/main/java/com/hermes/client/ui/chat/ChatViewModel.kt`
- Create: `app/src/main/java/com/hermes/client/ui/settings/PromptLibraryScreen.kt` (the `PromptLibraryViewModel` part; the Composable UI is Task 3)
- Test: Create `app/src/test/java/com/hermes/client/ui/settings/PromptLibraryViewModelTest.kt`; extend `ChatViewModelTest.kt`

**Interfaces:** Consumes `SavedPrompt`/helpers (Task 1). Produces `PromptStore`, `ChatViewModel.savedPrompts`, `PromptLibraryViewModel(prompts/save/delete)`.

- [ ] **Step 1: Add the `PromptStore` class** to `PromptStore.kt`:
```kotlin
private val Context.promptDataStore by preferencesDataStore(name = "saved_prompts")

/** Device-local, global store of the user's saved prompts (JSON list under one key). */
class PromptStore(private val context: Context) {
    private val key = stringPreferencesKey("prompts")

    val prompts: Flow<List<SavedPrompt>> =
        context.promptDataStore.data
            .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
            .map { decodePrompts(it[key]) }

    suspend fun upsert(p: SavedPrompt) =
        context.promptDataStore.edit { it[key] = encodePrompts(upsertPrompt(decodePrompts(it[key]), p)) }

    suspend fun delete(id: String) =
        context.promptDataStore.edit { it[key] = encodePrompts(deletePrompt(decodePrompts(it[key]), id)) }
}
```

- [ ] **Step 2: Provide it via Hilt** — in `AppModule.kt` (mirror `providePinStore`):
```kotlin
    @Provides
    @Singleton
    fun providePromptStore(@ApplicationContext context: Context): com.hermes.client.data.repository.PromptStore =
        com.hermes.client.data.repository.PromptStore(context)
```

- [ ] **Step 3: ChatViewModel.savedPrompts** — add the constructor param (new last param) and the flow:
```kotlin
    private val promptStore: com.hermes.client.data.repository.PromptStore,
```
```kotlin
    /** Device-local saved prompts, for the composer's prompt picker. */
    val savedPrompts: kotlinx.coroutines.flow.StateFlow<List<com.hermes.client.data.repository.SavedPrompt>> =
        promptStore.prompts.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), emptyList())
```

- [ ] **Step 4: PromptLibraryViewModel** — create `PromptLibraryScreen.kt` with just the ViewModel for now:
```kotlin
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
```

- [ ] **Step 5: Tests.** Create `PromptLibraryViewModelTest.kt` (mock `PromptStore`, `runTest` + `Dispatchers.setMain`):
```kotlin
package com.hermes.client.ui.settings

import com.hermes.client.data.repository.PromptStore
import com.hermes.client.data.repository.SavedPrompt
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PromptLibraryViewModelTest {
    private val store = mockk<PromptStore>(relaxed = true)
    @Before fun setUp() { Dispatchers.setMain(StandardTestDispatcher()); every { store.prompts } returns MutableStateFlow(emptyList()) }
    @After fun tearDown() = Dispatchers.resetMain()
    private fun vm() = PromptLibraryViewModel(store)

    @Test fun save_new_generates_id_and_upserts() = runTest {
        vm().save(null, "T", "B"); advanceUntilIdle()
        coVerify { store.upsert(match { it.title == "T" && it.body == "B" && it.id.isNotBlank() }) }
    }

    @Test fun save_existing_keeps_id() = runTest {
        vm().save("keep-me", "T", "B"); advanceUntilIdle()
        coVerify { store.upsert(match { it.id == "keep-me" }) }
    }

    @Test fun blank_title_falls_back_to_first_body_line() = runTest {
        vm().save(null, "  ", "first line\nsecond"); advanceUntilIdle()
        coVerify { store.upsert(match { it.title == "first line" }) }
    }

    @Test fun delete_delegates() = runTest {
        vm().delete("x"); advanceUntilIdle()
        coVerify { store.delete("x") }
    }
}
```
Extend `ChatViewModelTest.kt`: add `private val promptStore = mockk<com.hermes.client.data.repository.PromptStore>(relaxed = true)` with `every { promptStore.prompts } returns MutableStateFlow(emptyList())` in setUp, and pass it as the new last arg in `buildVm()`.

- [ ] **Step 6:** Run `./gradlew :app:testDebugUnitTest --tests "com.hermes.client.ui.settings.PromptLibraryViewModelTest" --tests "com.hermes.client.ui.chat.ChatViewModelTest"` → PASS. Then `:app:compileDebugKotlin` → BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**
```bash
git add app/src/main/java/com/hermes/client/data/repository/PromptStore.kt \
        app/src/main/java/com/hermes/client/di/AppModule.kt \
        app/src/main/java/com/hermes/client/ui/chat/ChatViewModel.kt \
        app/src/main/java/com/hermes/client/ui/settings/PromptLibraryScreen.kt \
        app/src/test/java/com/hermes/client/ui/settings/PromptLibraryViewModelTest.kt \
        app/src/test/java/com/hermes/client/ui/chat/ChatViewModelTest.kt
git commit -m "feat: PromptStore + prompt-library/chat view-model wiring"
```

---

### Task 3: UI — manage screen + composer picker

**Files:**
- Modify: `app/src/main/java/com/hermes/client/ui/settings/PromptLibraryScreen.kt` (add the Composable)
- Modify: `app/src/main/java/com/hermes/client/ui/nav/HermesNav.kt` (route), `app/src/main/java/com/hermes/client/ui/settings/SettingsScreen.kt` (Entry)
- Modify: `app/src/main/java/com/hermes/client/ui/chat/ChatScreen.kt` (composer icon + picker sheet)
- Test: none new (Compose glue). Compile + full suite + assembleBeta + Task 4.

- [ ] **Step 1: `PromptLibraryScreen` Composable.** Add to `PromptLibraryScreen.kt`: an `@OptIn(ExperimentalMaterial3Api::class) @Composable fun PromptLibraryScreen(onBack: () -> Unit, vm: PromptLibraryViewModel = hiltViewModel())` — a `Scaffold` with a top bar (title "Saved prompts", back arrow = `onBack`, mirror `MemorySettingsScreen`'s top bar) and a "New" action; body = a `LazyColumn` of `ListItem`s over `vm.prompts.collectAsStateWithLifecycle()` (headline = `title`, supporting = `body` first line, trailing = a delete `IconButton` → `vm.delete(id)`, `Modifier.clickable` to open the edit dialog). An add/edit `AlertDialog` (state `var editing by remember { mutableStateOf<SavedPrompt?>(null) }` + `var adding by remember { mutableStateOf(false) }`) with two `OutlinedTextField`s (title, body); confirm → `vm.save(editing?.id, title, body)`; confirm disabled when both blank; empty-list state text: "No saved prompts yet. Tap New to add one." Follow the existing settings-screen composition style.

- [ ] **Step 2: Register the route** — in `HermesNav.kt`, next to the other `settings_*` routes:
```kotlin
            composable("settings_prompts") {
                com.hermes.client.ui.settings.PromptLibraryScreen(onBack = { nav.popBackStack() })
            }
```

- [ ] **Step 3: Settings entry** — in `SettingsScreen.kt`, add (e.g. after the "Memory & budgets" entry, with a `HorizontalDivider`):
```kotlin
            HorizontalDivider()
            Entry("Saved prompts", "Reusable prompts for the composer") { onNavigate("settings_prompts") }
```

- [ ] **Step 4: Composer picker** — in `ChatScreen.kt`:
  - Collect: `val savedPrompts by vm.savedPrompts.collectAsStateWithLifecycle()` and `var showPromptSheet by remember { mutableStateOf(false) }`.
  - Add an `IconButton` in the composer `Row` (near the Attach/Mic leading icons) using an available Material icon (e.g. `Icons.AutoMirrored.Rounded.NoteAdd` or `Icons.Rounded.Bookmark`; pick one that exists in `material-icons-extended`) with `onClick = { showPromptSheet = true }`, `contentDescription = "Saved prompts"`.
  - Host a `ModalBottomSheet` when `showPromptSheet`: a `LazyColumn` of the `savedPrompts` (`ListItem` headline=title, supporting=body preview, `Modifier.clickable {}` → on pick:
    ```kotlin
    draft = if (draft.isBlank()) p.body else draft.trimEnd() + "\n" + p.body
    showPromptSheet = false
    focusRequester.requestFocus()
    ```
    ); when empty, a `Text("No saved prompts yet — add them in Settings › Saved prompts.")`.

- [ ] **Step 5: Compile + full suite + assembleBeta** — all three (JAVA_HOME set) BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**
```bash
git add app/src/main/java/com/hermes/client/ui/settings/PromptLibraryScreen.kt \
        app/src/main/java/com/hermes/client/ui/nav/HermesNav.kt \
        app/src/main/java/com/hermes/client/ui/settings/SettingsScreen.kt \
        app/src/main/java/com/hermes/client/ui/chat/ChatScreen.kt
git commit -m "feat: saved-prompts manage screen + composer picker"
```

---

### Task 4: On-device verification

- [ ] **Step 1:** `:app:installBeta` (target `emulator-5554` if multiple devices).
- [ ] **Step 2:** Settings › Saved prompts → New → enter title + body → Save → appears in the list. Edit it (change body) → persists. Delete → removed.
- [ ] **Step 3:** Open/create a chat → tap the composer's saved-prompts icon → the sheet lists the prompt → pick → it's appended into the draft and the field is focused. Type first, then pick → the prompt appends after a newline.
- [ ] **Step 4:** With no prompts saved, the picker sheet shows the empty-state copy.
- [ ] **Step 5:** Record pass/fail in the PR description.

---

## Notes for the executor
- Do NOT add share-sheet export, App Actions, a `/`-command trigger, or sync — explicit anti-scope.
- Pick composer/picker icons that actually exist in `androidx.compose.material.icons` (`material-icons-extended` is a dependency); if an icon name is unavailable, substitute a present one — the behavior (a picker that appends into `draft`) is the contract.
