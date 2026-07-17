# Per-Tenant Persona Picker Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Checkbox steps.

**Goal:** Pick a gateway-configured personality and apply it to the session, from the chat overflow menu.

**Spec:** `docs/superpowers/specs/2026-07-17-persona-picker-design.md`

## Global Constraints
- Client-only; read personas via `ConfigRepository.get(activeProfile)`, set via `slashExec("/personality <name>")`. No AI attribution.
- Build env: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`.
- Branch `feature/persona-picker` (off `dev`).

---

### Task 1: Persona model + pure parsers

**Files:** Create `app/src/main/java/com/hermes/client/ui/chat/Persona.kt`; Test `app/src/test/java/com/hermes/client/ui/chat/PersonaTest.kt`

- [ ] **Step 1: Write the failing test** `PersonaTest.kt`:
```kotlin
package com.hermes.client.ui.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PersonaTest {
    private fun obj(s: String): JsonObject = Json.decodeFromString(JsonObject.serializer(), s)

    @Test fun parses_string_and_object_personas_sorted() {
        val c = obj("""{"agent":{"personalities":{"witty":"Be witty","coach":{"description":"A coach","tone":"warm"}}}}""")
        val ps = parsePersonas(c)
        assertEquals(listOf("coach", "witty"), ps.map { it.name })          // sorted
        assertEquals("A coach", ps.first { it.name == "coach" }.description) // object → description
        assertEquals("", ps.first { it.name == "witty" }.description)        // string → no description
    }

    @Test fun missing_agent_or_personalities_is_empty() {
        assertEquals(emptyList<Persona>(), parsePersonas(obj("""{}""")))
        assertEquals(emptyList<Persona>(), parsePersonas(obj("""{"agent":{}}""")))
    }

    @Test fun active_persona_reads_display_and_maps_none_to_null() {
        assertEquals("coach", activePersonaOf(obj("""{"display":{"personality":"coach"}}""")))
        assertNull(activePersonaOf(obj("""{"display":{"personality":"none"}}""")))
        assertNull(activePersonaOf(obj("""{"display":{"personality":""}}""")))
        assertNull(activePersonaOf(obj("""{}""")))
    }
}
```
(Delete the stray `cfg` helper if it doesn't compile — only `obj(...)` is needed. Match the project's kotlinx.serialization imports.)

- [ ] **Step 2:** Run `./gradlew :app:testDebugUnitTest --tests "com.hermes.client.ui.chat.PersonaTest"` → FAIL.

- [ ] **Step 3: Implement** `Persona.kt`:
```kotlin
package com.hermes.client.ui.chat

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A gateway-configured personality the user can apply to a session. */
data class Persona(val name: String, val description: String)

private fun JsonObject.objOrNull(key: String): JsonObject? = (this[key] as? JsonObject)
private fun JsonObject.strOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** Personalities from `agent.personalities` (name → String | object). Sorted by name; never throws. */
fun parsePersonas(config: JsonObject): List<Persona> {
    val personalities = config.objOrNull("agent")?.objOrNull("personalities") ?: return emptyList()
    return personalities.entries.map { (name, value) ->
        val desc = (value as? JsonObject)?.let {
            it.strOrNull("description") ?: it.strOrNull("tone") ?: it.strOrNull("style") ?: ""
        }.orEmpty().trim()
        Persona(name, desc)
    }.sortedBy { it.name.lowercase() }
}

/** The active personality (`display.personality`); blank/"none" → null. */
fun activePersonaOf(config: JsonObject): String? =
    config.objOrNull("display")?.strOrNull("personality")
        ?.takeIf { it.isNotBlank() && !it.equals("none", ignoreCase = true) }
```

- [ ] **Step 4:** Run the test → PASS.

- [ ] **Step 5: Commit**
```bash
git add app/src/main/java/com/hermes/client/ui/chat/Persona.kt \
        app/src/test/java/com/hermes/client/ui/chat/PersonaTest.kt
git commit -m "feat: parse configured personas from gateway config"
```

---

### Task 2: ChatViewModel persona wiring

**Files:** Modify `app/src/main/java/com/hermes/client/ui/chat/ChatViewModel.kt`, `app/src/main/java/com/hermes/client/di/AppModule.kt` (only if `ConfigRepository` isn't already provided — check first); Test `app/src/test/java/com/hermes/client/ui/chat/ChatViewModelTest.kt` (extend)

**Interfaces:** Consumes `parsePersonas`/`activePersonaOf` (Task 1), `ConfigRepository.get(profile)`, `ChatRepository.slashExec`.

- [ ] **Step 1: Confirm `ConfigRepository` is Hilt-provided.** `grep -n "ConfigRepository" app/src/main/java/com/hermes/client/di/AppModule.kt` — it's already used by the settings screens, so a provider likely exists. If NOT, add `@Provides @Singleton fun provideConfigRepository(rest: HermesRestApi): ConfigRepository = ConfigRepository(rest)`.

- [ ] **Step 2: Write the failing test (extend `ChatViewModelTest`).** Add a mock + `buildVm` arg + tests:
```kotlin
    private val configRepo = mockk<com.hermes.client.data.repository.ConfigRepository>(relaxed = true)
```
Update `buildVm()` to pass `configRepo` as the new last arg. Add:
```kotlin
    @Test fun setPersona_sends_personality_slash() = runTest {
        val vm = buildVm()
        vm.setPersona("witty"); advanceUntilIdle()
        io.mockk.coVerify { chatRepo.slashExec(any(), "/personality witty") }
    }

    @Test fun setPersona_null_clears_with_none() = runTest {
        val vm = buildVm()
        vm.setPersona(null); advanceUntilIdle()
        io.mockk.coVerify { chatRepo.slashExec(any(), "/personality none") }
    }
```
(If the test file's mockk/runTest conventions differ, match them; the `slashExec` verifications are the contract. `chatRepo` is the existing ChatRepository mock name in this test.)

- [ ] **Step 3:** Run `./gradlew :app:testDebugUnitTest --tests "com.hermes.client.ui.chat.ChatViewModelTest"` → FAIL.

- [ ] **Step 4: Implement** in `ChatViewModel.kt`:
Add the constructor param (new last):
```kotlin
    private val configRepo: com.hermes.client.data.repository.ConfigRepository,
```
Add the state + methods:
```kotlin
    data class PersonaUi(
        val personas: List<Persona> = emptyList(),
        val active: String? = null,
        val loading: Boolean = false,
        val error: String? = null,
    )
    private val _personaUi = MutableStateFlow(PersonaUi())
    val personaUi: StateFlow<PersonaUi> = _personaUi.asStateFlow()

    /** Fetch the profile's configured personalities (called when the persona sheet opens). */
    fun loadPersonas() {
        _personaUi.value = _personaUi.value.copy(loading = true, error = null)
        viewModelScope.launch {
            runCatching { configRepo.get(profileManager.active.value) }
                .onSuccess { cfg -> _personaUi.value = PersonaUi(parsePersonas(cfg), activePersonaOf(cfg)) }
                .onFailure { _personaUi.value = _personaUi.value.copy(loading = false, error = "Couldn't load personas") }
        }
    }

    /** Apply a persona to this session (null / "none" clears it). */
    fun setPersona(name: String?) {
        val wire = name?.takeIf { it.isNotBlank() && !it.equals("none", true) } ?: "none"
        viewModelScope.launch {
            runCatching { chat.slashExec(sessionId, "/personality $wire") }
                .onSuccess { _personaUi.value = _personaUi.value.copy(active = if (wire == "none") null else wire) }
        }
    }
```
(Ensure `MutableStateFlow`/`StateFlow`/`asStateFlow`/`launch` imports exist — they do, used throughout this VM.)

- [ ] **Step 5:** Run the test → PASS. Then `:app:compileDebugKotlin` → BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**
```bash
git add app/src/main/java/com/hermes/client/ui/chat/ChatViewModel.kt \
        app/src/main/java/com/hermes/client/di/AppModule.kt \
        app/src/test/java/com/hermes/client/ui/chat/ChatViewModelTest.kt
git commit -m "feat: ChatViewModel persona load + apply"
```
(Drop `AppModule.kt` from the add if you didn't need to change it.)

---

### Task 3: Persona sheet + overflow item

**Files:** Create `app/src/main/java/com/hermes/client/ui/chat/PersonaSheet.kt`; Modify `app/src/main/java/com/hermes/client/ui/chat/ChatScreen.kt`

**Interfaces:** Consumes `ChatViewModel.personaUi`/`loadPersonas`/`setPersona` (Task 2).

- [ ] **Step 1: PersonaSheet composable.** Create `PersonaSheet.kt` with `@OptIn(ExperimentalMaterial3Api::class) @Composable fun PersonaSheet(ui: PersonaUi, onPick: (String?) -> Unit, onRetry: () -> Unit, onDismiss: () -> Unit)`: a `ModalBottomSheet` containing a title "Persona"; when `ui.loading` a centered spinner; when `ui.error != null` the error text + a "Retry" `TextButton(onRetry)`; else a `LazyColumn` with a "None (default)" `ListItem` (`onClick = { onPick(null) }`) followed by one `ListItem` per `ui.personas` (headline = name, supporting = description when non-blank, `onClick = { onPick(p.name) }`); mark the active row (`p.name == ui.active`, or None when `ui.active == null`) with a trailing check `Icon` tinted `LocalProfileAccent.current.accent`. When `ui.personas` is empty and not loading/error, show a hint `Text("No personas configured — add them in your gateway config.")` under the None row. (`PersonaUi` is nested in `ChatViewModel`; reference it as `ChatViewModel.PersonaUi`.)

- [ ] **Step 2: Overflow item + host in ChatScreen.** In `ChatScreen.kt`:
  - Add `var showPersonaSheet by remember { mutableStateOf(false) }` and `val personaUi by vm.personaUi.collectAsStateWithLifecycle()`.
  - In the top-bar overflow `DropdownMenu` (the Copy/Share transcript one), add a `DropdownMenuItem(text = { Text("Persona") }, onClick = { transcriptMenu = false; vm.loadPersonas(); showPersonaSheet = true })`.
  - After the existing sheets/hosts, add: `if (showPersonaSheet) { PersonaSheet(ui = personaUi, onPick = { vm.setPersona(it); showPersonaSheet = false }, onRetry = { vm.loadPersonas() }, onDismiss = { showPersonaSheet = false }) }`.

- [ ] **Step 3: Compile + full suite + assembleBeta** — all three (JAVA_HOME set) BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**
```bash
git add app/src/main/java/com/hermes/client/ui/chat/PersonaSheet.kt \
        app/src/main/java/com/hermes/client/ui/chat/ChatScreen.kt
git commit -m "feat: persona picker sheet in the chat overflow menu"
```

---

### Task 4: On-device verification (best-effort)

- [ ] **Step 1:** `:app:installBeta`. Connect to a gateway that has ≥1 configured personality (`agent.personalities` in its config).
- [ ] **Step 2:** Open a chat → overflow ⋮ → Persona → the sheet lists the configured personas + "None (default)", marking the active one. Tap one → applies (sheet closes; the active mark moves). Tap None → clears.
- [ ] **Step 3:** If no personas are configured, confirm only "None (default)" + the hint appears; if the config fetch fails, the error + Retry appears.
- [ ] **Step 4:** Note: requires a connected session + configured personas; if unavailable, record that the parse + apply are covered by `PersonaTest` + `ChatViewModelTest` + review. Record in the PR.

---

## Notes for the executor
- Do NOT add persona creation/editing (gateway-config-owned), profile-default persistence (`config.set` — deferred), or a non-chat persona surface — anti-scope.
- Read personas for the SESSION's active profile (the `profileManager.active.value` passed to `configRepo.get`) so a name valid for this tenant isn't rejected.
