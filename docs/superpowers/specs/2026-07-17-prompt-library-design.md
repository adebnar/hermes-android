# Prompt / Snippet Library — Design

**Wave:** Quick-wins (client-only). **Branch:** `feature/prompt-library` (off `dev`).

**Goal:** Let the user save reusable prompts locally, insert one into the chat composer with a tap, and manage them (add/edit/delete) from a Settings screen. Client-only; device-local (no gateway, no sync).

**Constraints:** Kotlin/Compose/Material3/Hilt. No AI attribution; gitleaks before push; PR into `dev`.

## Scope
- **In:** a device-local `PromptStore` (DataStore + JSON `List<SavedPrompt>`); a composer icon-button → picker that **appends** the chosen prompt into `draft`; a "Saved prompts" manage screen (list + add/edit dialog + delete) reachable from Settings.
- **Out (deferred):** sharing a prompt *out* via the share-sheet / registering as a share target; App Actions / shortcuts; `/`-command trigger (the icon-button picker is v1); cloud/desktop sync; caret-precise insertion (`draft` is a `String`, so append, not insert-at-cursor).

## Architecture

### 1. Model + pure helpers — `data/repository/PromptStore.kt` (new)
```kotlin
@Serializable
data class SavedPrompt(val id: String, val title: String, val body: String)
```
Pure, unit-testable free functions (never throw):
- `fun decodePrompts(raw: String?): List<SavedPrompt>` — `runCatching { Json.decodeFromString(raw ?: "[]") }.getOrDefault(emptyList())`.
- `fun encodePrompts(list: List<SavedPrompt>): String` — `Json.encodeToString(list)`.
- `fun upsertPrompt(list, p): List<SavedPrompt>` — replace the element with `p.id` if present, else append.
- `fun deletePrompt(list, id): List<SavedPrompt>` — filter out `id`.

### 2. `PromptStore` (DataStore) — same file
Mirror `ModelFavoritesStore`: `preferencesDataStore(name = "saved_prompts")`, a single `stringPreferencesKey("prompts")`. Device-local, global (not per-profile).
```kotlin
val prompts: Flow<List<SavedPrompt>> =
    context.promptDataStore.data
        .catch { e -> if (e is java.io.IOException) emit(emptyPreferences()) else throw e }
        .map { decodePrompts(it[key]) }

suspend fun upsert(p: SavedPrompt) = context.promptDataStore.edit { it[key] = encodePrompts(upsertPrompt(decodePrompts(it[key]), p)) }
suspend fun delete(id: String) = context.promptDataStore.edit { it[key] = encodePrompts(deletePrompt(decodePrompts(it[key]), id)) }
```
Provided `@Singleton` in `di/AppModule.kt` (mirror `providePinStore`).

### 3. Composer picker — `ui/chat/ChatViewModel.kt` + `ChatScreen.kt` (modify)
- `ChatViewModel`: inject `PromptStore`; expose `val savedPrompts: StateFlow<List<SavedPrompt>> = promptStore.prompts.stateIn(...)`.
- `ChatScreen`: add an `IconButton` (a notes/library icon) in the composer `Row` (leading, near the Attach/Mic buttons). On tap → open a `ModalBottomSheet` listing `savedPrompts` (title + body preview). On pick → append into the existing `draft` and refocus, reusing the edit-resend gesture:
  ```kotlin
  draft = if (draft.isBlank()) p.body else draft.trimEnd() + "\n" + p.body
  showPromptSheet = false
  focusRequester.requestFocus()
  ```
  Empty state in the sheet: "No saved prompts yet — add them in Settings › Saved prompts."

### 4. Manage screen — `ui/settings/PromptLibraryScreen.kt` (new) + nav/settings wiring
- `PromptLibraryViewModel` (`@HiltViewModel`, injects `PromptStore`): `val prompts: StateFlow<List<SavedPrompt>>`; `fun save(id: String?, title: String, body: String)` (new `SavedPrompt(id ?: randomUUID, title, body)` → `store.upsert`); `fun delete(id: String)`.
- `PromptLibraryScreen(onBack)`: `Scaffold` + a top bar with a back arrow (mirror `MemorySettingsScreen`) + a `LazyColumn` of `ListItem`s (headline=title, supporting=body preview; tap → edit dialog; a trailing delete `IconButton`) + a "New prompt" button/FAB. Add/edit uses an `AlertDialog` with two `OutlinedTextField`s (title, body); Save disabled when both blank; blank title falls back to the first line of the body.
- `HermesNav.kt`: `composable("settings_prompts") { PromptLibraryScreen(onBack = { nav.popBackStack() }) }`.
- `SettingsScreen.kt`: a new `Entry("Saved prompts", "Reusable prompts for the composer") { onNavigate("settings_prompts") }` (+ a `HorizontalDivider`).

## Data flow
```
Settings › Saved prompts → PromptLibraryScreen → add/edit/delete → PromptLibraryViewModel → PromptStore.upsert/delete → DataStore
Composer library icon → ModalBottomSheet(savedPrompts from ChatViewModel) → pick → append to draft + focus
```

## Error handling
- Corrupt/missing store JSON → `decodePrompts` returns `[]` (never throws); `IOException` on read → empty via `.catch`.
- Blank title → derive from body's first line; both blank → Save disabled (can't create an empty prompt).
- Appending to a non-empty draft inserts a newline separator; empty draft → just the body.

## Testing
- **`PromptStoreTest`** (pure): `decodePrompts` of valid JSON, of null/`""`/garbage → `[]`; `encodePrompts`/`decodePrompts` round-trip; `upsertPrompt` appends a new id and replaces an existing id (preserving order); `deletePrompt` removes by id and is a no-op for a missing id.
- **`PromptLibraryViewModelTest`** (mock `PromptStore`): `save(null, …)` calls `store.upsert` with a generated id + given title/body; `save(existingId, …)` upserts with that id; `delete(id)` calls `store.delete(id)`; blank-title fallback to body's first line.
- DataStore wiring, the bottom sheet, and the manage screen are Android/Compose — verified on-device.

## On-device verification
Settings › Saved prompts → add a prompt (title + body) → it appears in the list; edit it; delete it. Open a chat → tap the composer library icon → the saved prompt shows → pick → it's appended to the draft and the field is focused. Empty-state copy shows when no prompts exist.

## Files
| Action | Path |
|--------|------|
| New | `data/repository/PromptStore.kt` (model + pure helpers + store) + `PromptStoreTest.kt` |
| Modify | `di/AppModule.kt` (provide store) |
| Modify | `ui/chat/ChatViewModel.kt` (savedPrompts) |
| Modify | `ui/chat/ChatScreen.kt` (composer library icon + picker sheet) |
| New | `ui/settings/PromptLibraryScreen.kt` (screen + `PromptLibraryViewModel`) + `PromptLibraryViewModelTest.kt` |
| Modify | `ui/nav/HermesNav.kt` (route), `ui/settings/SettingsScreen.kt` (Entry) |

## Build & gates
`JAVA_HOME=…`: `:app:compileDebugKotlin`, `:app:testDebugUnitTest`, `:app:assembleBeta`. gitleaks before push; PR into `dev`.
