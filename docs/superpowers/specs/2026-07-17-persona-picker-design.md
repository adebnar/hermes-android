# Per-Tenant Persona Picker — Design

**Wave:** Quick-wins (client-only). **Branch:** `feature/persona-picker` (off `dev`).

**Goal:** Let the user pick one of the personalities already configured in the gateway (per-profile) and apply it to the current session — exposing an existing gateway capability with no backend change.

**Constraints:** Kotlin/Compose/Material3/Hilt, per-tenant. No AI attribution; gitleaks before push; PR into `dev`.

## Feasibility (from the gateway audit)
- **Read:** `GET /api/config` (already used via `ConfigRepository.get(profile)`) returns `agent.personalities` (a map `name → String | {system_prompt, tone, style, …}`) and `display.personality` (the active one). No gateway change to populate a picker.
- **Set:** the existing `slashExec(sessionId, "/personality <name>")` applies a pre-registered persona to the session (session-scoped/ephemeral; gated to configured names by the gateway; `none`/`default` clears it). Mirrors the model picker's `/model … --session`.
- **Per-tenant for free:** config read + the slash apply are both profile-scoped. Read personalities for the **session's** profile (the active profile).

## Scope
- **In:** parse personalities + active from the config; a "Persona" item in the chat overflow menu → a bottom sheet listing them (+ "None (default)"), highlighting the active; apply via `slashExec`.
- **Out (deferred):** persisting the choice as the profile default (`config.set key=personality` — a separate small RPC); creating/editing personas (they're gateway-config-managed); a persona picker on non-chat surfaces.

## Architecture

### 1. `ui/chat/Persona.kt` (new) — model + pure parsers
```kotlin
data class Persona(val name: String, val description: String)

/** Personalities configured for this profile, from the gateway config JSON (agent.personalities). */
fun parsePersonas(config: JsonObject): List<Persona>
/** The active personality name (display.personality), or null/"none" when unset. */
fun activePersonaOf(config: JsonObject): String?
```
`parsePersonas`: read `config["agent"]?.jsonObject?["personalities"]?.jsonObject`; for each entry, `name = key`; `description` = if the value is a JSON object, its `description`/`tone`/`style` (first available, trimmed) else "" (string-valued personas have no separate description). Sorted by name. Never throws (missing keys → empty list). `activePersonaOf`: `config["display"]?.jsonObject?["personality"]` as string; map blank/`"none"` → null.

### 2. `ui/chat/ChatViewModel.kt` (modify)
Inject `ConfigRepository` (new last constructor param). Add:
```kotlin
data class PersonaUi(val personas: List<Persona> = emptyList(), val active: String? = null, val loading: Boolean = false, val error: String? = null)
val personaUi: StateFlow<PersonaUi>  // MutableStateFlow-backed

fun loadPersonas() {  // called when the sheet opens
    _personaUi.value = _personaUi.value.copy(loading = true, error = null)
    viewModelScope.launch {
        runCatching { configRepo.get(profileManager.active.value) }
            .onSuccess { cfg -> _personaUi.value = PersonaUi(parsePersonas(cfg), activePersonaOf(cfg)) }
            .onFailure { _personaUi.value = _personaUi.value.copy(loading = false, error = "Couldn't load personas") }
    }
}

fun setPersona(name: String?) {  // null / "none" clears
    val wire = name?.takeIf { it.isNotBlank() } ?: "none"
    viewModelScope.launch {
        runCatching { chat.slashExec(sessionId, "/personality $wire") }
            .onSuccess { _personaUi.value = _personaUi.value.copy(active = name?.takeIf { it != "none" }) }
    }
}
```

### 3. `ui/chat/ChatScreen.kt` (modify) — overflow item + sheet
- In the existing top-bar overflow `DropdownMenu` (the one with Copy/Share transcript), add a **"Persona"** `DropdownMenuItem` → `showPersonaSheet = true; transcriptMenu = false; vm.loadPersonas()`.
- Host a `PersonaSheet` (a `ModalBottomSheet`, gated by `var showPersonaSheet`) that renders `vm.personaUi`:
  - a "None (default)" row + one row per `Persona` (headline = name, supporting = description if non-blank), the active one visually marked (accent check / bold);
  - loading → a spinner; error → the error text + a Retry (`vm.loadPersonas()`);
  - on a row tap → `vm.setPersona(name)` (or `null` for None) + close the sheet.
  Use `LocalProfileAccent` for the active highlight (per-tenant accent).

### 4. `ui/chat/PersonaSheet.kt` (new) — the sheet composable (or inline in ChatScreen)
A small `@Composable fun PersonaSheet(ui: PersonaUi, onPick: (String?) -> Unit, onRetry: () -> Unit, onDismiss: () -> Unit)`.

## Data flow
```
overflow ⋮ → Persona → vm.loadPersonas() → ConfigRepository.get(activeProfile) → parsePersonas + activePersonaOf → PersonaUi
PersonaSheet → pick name → vm.setPersona(name) → slashExec("/personality <name>") → active updated → sheet closes
```

## Error handling
- Config fetch fails → error state + Retry; no crash.
- No `agent.personalities` configured → empty list; the sheet shows only "None (default)" with an "Add personas in your gateway config" hint.
- `slashExec` failure → active unchanged (the gateway rejects unknown names; only configured names are offered, so this is rare).

## Testing
- **`PersonaTest`** (pure): `parsePersonas` of a config with string-valued + object-valued personas → correct names/descriptions, sorted; missing `agent`/`personalities` → empty; `activePersonaOf` reads `display.personality`, maps blank/"none" → null.
- **`ChatViewModelTest`** (extend, mock `ConfigRepository` + `ChatRepository`): `setPersona("witty")` calls `slashExec(sid, "/personality witty")`; `setPersona(null)` → `"/personality none"`; `loadPersonas()` populates `personaUi` from a stubbed config.
- The sheet + overflow are Compose glue — verified on-device (best-effort).

## On-device verification
With a gateway that has ≥1 configured personality: chat overflow ⋮ → Persona → the sheet lists them + None, marking the active; tap one → applies (subsequent replies reflect it), sheet closes. With none configured → only "None (default)" + the hint.

## Files
| Action | Path |
|--------|------|
| New | `ui/chat/Persona.kt` (model + parsers) + `PersonaTest.kt` |
| Modify | `ui/chat/ChatViewModel.kt` (ConfigRepository + personaUi/loadPersonas/setPersona) + `ChatViewModelTest.kt` |
| New | `ui/chat/PersonaSheet.kt` |
| Modify | `ui/chat/ChatScreen.kt` (overflow item + sheet host) |

## Build & gates
`JAVA_HOME=…`: `:app:compileDebugKotlin`, `:app:testDebugUnitTest`, `:app:assembleBeta`. gitleaks before push; PR into `dev`.
