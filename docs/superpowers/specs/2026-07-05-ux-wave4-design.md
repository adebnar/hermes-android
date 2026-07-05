# UX Wave 4 — P2 Polish (lean) — Design

**Date:** 2026-07-05
**Status:** Approved (brainstorming) → implementation plan next
**Branch:** `feature/ux-wave4`
**Source:** `docs/ideas/ux-review-2026-07-04.md` (remaining P2s)

## Goal

Three small, honest P2 polish fixes (one task each): a 48 dp touch target for the model picker, grouped Usage stat tiles, and a copy-only rename that disambiguates the two "Models" entry points. Compose/copy only.

## Hard constraints

- **No gateway/bridge API changes.** Compose UI + copy only. Material 3.
- Multi-tenant isolation preserved; follow existing patterns.
- No AI/assistant attribution in commits, files, or PRs.

## Dropped after exploration (premises didn't hold)

- **Right-size the app bar — dropped.** `HermesTopBar` is a *standard* M3 `TopAppBar` (no explicit height); its only extra height is a small `labelMedium` subtitle line. The review's "eats a third of the viewport / ~250 px band" was the bar **plus the two chip rows below it** — already addressed in wave 3 (quicklinks moved to a footer, Home subtitle dropped). There is no oversized custom band to shrink.
- **Contrast bump on muted text — dropped.** Measured: dark `onSurfaceVariant` (#C9C5D0 on #121218) ≈ **11:1**, light ≈ **9:1** — both clear WCAG AA and AAA. The review's "~3:1" is inaccurate at the token level; the only dimmer spots are *intentional* `alpha` reductions (0.75 subtitle, 0.4 disabled send icon). No real contrast defect.
- **"Est. cost (7d)" label — dropped.** `UsageDto`/`UsageDayDto` carry no time-window field; `estimatedCost` is a sum over whatever `daily` rows the backend returns. Labeling it "7d" would be inaccurate. The Usage task keeps the honest "Est. cost" label and only adds visual grouping.

## Item 1 — Model-picker touch target (P2 §7)

The model `AssistChip` in `ChatScreen`'s `ModelPickerButton` has M3's default ~32 dp height and — unlike `Button`/`IconButton` — is **not** auto-expanded to a 48 dp touch target (verified: it's the only interactive control below 48 dp; the "Archived"/"Retry" `TextButton`s and the copy `IconButton` already get 48 dp from M3 defaults).

- Add `Modifier.minimumInteractiveComponentSize()` to the `AssistChip` (`ChatScreen.kt` ~line 142). This enforces a 48 dp minimum touch target **without** changing the chip's 32 dp visual height. Add the import `androidx.compose.material3.minimumInteractiveComponentSize`.

*File: `ui/chat/ChatScreen.kt`.*

## Item 2 — Usage stat tiles (P2 §2 [08])

`UsageScreen`'s private `Stat(label, value, modifier)` renders a bare `Column { Text(label); Text(value) }`, so the four stats (Sessions / API calls / Tokens in-out / Est. cost) float with no container.

- Wrap the `Stat` body in a tonal container so the stats read as grouped tiles:
```kotlin
@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    androidx.compose.material3.Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium)
        }
    }
}
```
Keep the `modifier` passthrough (the "Est. cost" call site passes `Modifier.weight(1f)`); the existing call sites and the "Est. cost" text are unchanged.

*File: `ui/usage/UsageScreen.kt`.*

## Item 3 — "Models" disambiguation (P2 §3/§6 [06/07], copy-only)

Two entry points both currently advertise model defaults, so it's unclear which owns model selection:
- You-tab (`YouHubScreen.kt:93`): `HubRow(Icons.Rounded.AutoAwesome, "Models", "Model catalog & defaults") { onNavigate("models") }` → `ModelsScreen` (a browse / favourite / pick screen; picking sets the global default).
- Settings (`SettingsScreen.kt:41`): `Entry("Models & memory", "Default model, memory toggles & budgets") { onNavigate("settings_memory") }` → `MemorySettingsScreen` (memory toggles, char-limit budgets, and a default-model field).

Rename so "Models" is unambiguously the picker and Settings stops competing on the word "Models" (copy-only — routes and behaviour unchanged):
- **You-tab:** title stays `"Models"`; subtitle `"Model catalog & defaults"` → **`"Browse & pick models"`**.
- **Settings:** `"Models & memory"` → **`"Memory & budgets"`**; subtitle `"Default model, memory toggles & budgets"` → **`"Memory, user profile & default model"`** (still honest that it holds a default-model field).

*Files: `ui/nav/YouHubScreen.kt`, `ui/settings/SettingsScreen.kt`.*

## Testing

No unit tests (all three are pure UI/copy with no logic branch). Verification is on-device:
1. The chat model chip has a comfortable tap area (48 dp) though it still looks the same size.
2. The Usage screen shows the four stats as grouped tiles (tonal containers).
3. The You tab shows **"Models" / "Browse & pick models"**; Settings shows **"Memory & budgets" / "Memory, user profile & default model"**; both destinations still open correctly.

## Not doing (YAGNI)

- App-bar height change, muted-text contrast bump, "(7d)" cost label (see "Dropped after exploration").
- The deeper dual-ownership of the default-model setting (both the picker and Settings write it) — a behavioural change, out of scope for a copy-only pass.
- Any gateway/API change.
