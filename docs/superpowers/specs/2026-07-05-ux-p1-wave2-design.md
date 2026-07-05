# UX P1 Wave 2 (batch) — Design

**Date:** 2026-07-05
**Status:** Approved (brainstorming) → implementation plan next
**Branch:** `feature/ux-p1-wave2`
**Source:** P1 findings in `docs/ideas/ux-review-2026-07-04.md`

## Goal

Four Compose-only, no-bridge-change UI fixes (one task each): a legible/deduplicated offline banner, "Chats" naming, a bold per-tenant app bar in light mode, and an accent-tinted Cron FAB.

## Hard constraints

- **No gateway/bridge API changes.** Compose UI + existing state/data only. Material 3.
- Multi-tenant isolation preserved; follow existing patterns (`AccentChrome`, `HermesTopBar`, `LocalProfileAccent`).
- No AI/assistant attribution in commits, files, or PRs.

## Dropped from the original 5

**Chat code-block horizontal scroll — already implemented.** `ChatComponents.CodeWithCopy` already applies `.horizontalScroll(rememberScrollState())` and reserves 44 dp for the copy button; long code lines already scroll. The review misread a scrollable block as truncated. No change.

## Item 1 — Offline banner: contrast, dedupe, copy

`ChatScreen.ConnectionBanner` (~line 290) renders on an `errorContainer` background; its **"Retry" `TextButton` uses the default `primary` (lavender) content colour** → poor contrast on the red banner. And "Offline" is shown **twice** at once — in the banner text and in the top-bar `StatusDot`'s label.

- **Retry legibility:** `TextButton(onClick = onRetry, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer)) { Text("Retry") }`.
- **Friendlier copy:** add a pure `bannerLabel(state): String` (in `StatusDot.kt`, beside `connectionLabel`) → `Disconnected → "You're offline — new messages send when you reconnect."`, `is Error → "Connection error — tap Retry."`, else `connectionLabel(state)` (Connecting…/Reconnecting…). The banner uses `bannerLabel(state)`; `StatusDot` keeps `connectionLabel` semantics but loses its label (below).
- **Dedupe:** make `StatusDot` **dot-only** — drop its trailing `Text(connectionLabel(state))`, keep just the coloured dot. The banner carries the words when offline; the green dot alone reads as "connected" in the top bar, and it declutters the now-crowded bar (back + title + model chip + status). Add a `showLabel: Boolean = false` param to `StatusDot` (default dot-only) so any other caller can opt back into the label; audit callers (likely only `ChatScreen`).

*Files: `ui/chat/ChatScreen.kt`, `ui/components/StatusDot.kt`.*

## Item 2 — "Chats" naming

- `SessionsScreen` `HermesTopBar(title = "Sessions")` → `title = "Chats"` (matches the bottom-nav tab).
- The row subtitle `listOfNotNull(session.profile, session.model).joinToString(" · ")` (`SessionRow`, ~line 418) shows `"acme · model"`, which is **redundant under a profile group header** — but **not** in the **Pinned** section (which pools sessions across profiles, unheadered). Add a `showProfile: Boolean` param to `SessionRow`: subtitle = `listOfNotNull(session.profile.takeIf { showProfile }, session.model).joinToString(" · ")`. **Pinned** rows pass `showProfile = true`; **grouped** rows pass `showProfile = false` (subtitle becomes just the model).

*File: `ui/sessions/SessionsScreen.kt`.*

## Item 3 — Bold per-tenant app bar in light mode

**Decided: bold coloured bar.** In light mode `profileAccentColors` sets `container` to HSL lightness `0.90` (near-white), so `HermesTopBar` (which paints `containerColor = accent.container`) loses the tenant hue. Fix in the **chrome only** (verified: `container`/`onContainer` are consumed solely by `HermesTopBar` + `AccentChrome.onBar`):

- **`HermesTopBar`** — compute `val dark = isSystemInDarkTheme()`; use `val barBg = if (dark) accent.container else accent.accent` and `val barOn = if (dark) accent.onContainer else accent.onAccent`. Replace the four `accent.container`/`accent.onContainer` uses (TopAppBar `containerColor`/`titleContentColor`/`navigationIconContentColor`/`actionIconContentColor`, plus the title `color` and the subtitle `color = barOn.copy(alpha = 0.75f)`) with `barBg`/`barOn`. Dark mode is unchanged; light mode now shows the saturated tenant accent bar with legible on-accent text.
- **`AccentChrome.onBar`** — `@Composable get() = if (isSystemInDarkTheme()) LocalProfileAccent.current.onContainer else LocalProfileAccent.current.onAccent`, so action text (Sessions "Archived") and the chat model chip stay legible on the now-accent light bar.

No change to `container`/`onContainer` values or other elements (this is scoped to the app bar chrome). Dark mode's look is preserved.

*Files: `ui/components/HermesTopBar.kt`.*

## Item 5 — Cron FAB accent + icon

`CronScreen`'s `ExtendedFloatingActionButton` uses Material's default `primaryContainer` (static brand indigo) and an empty `icon = {}`; `SessionsScreen`'s already uses `AccentChrome.fabContainer`/`onFab` + an Add icon. Make Cron's match:
```kotlin
androidx.compose.material3.ExtendedFloatingActionButton(
    onClick = onNew,
    text = { Text("New") },
    icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
    containerColor = com.hermes.client.ui.components.AccentChrome.fabContainer,
    contentColor = com.hermes.client.ui.components.AccentChrome.onFab,
)
```

*File: `ui/cron/CronScreen.kt`.*

## Testing

- **Unit (pure), TDD — `bannerLabel`:** `Disconnected` → the friendly offline copy; `Error("x")` → the error copy; `Connecting`/`Reconnecting` → `connectionLabel(...)` (delegates).
- **On-device:**
  1. Chat while offline → one "You're offline…" banner with a **legible** Retry; the top bar shows only a red dot (no second "Offline"). Reconnect → banner clears, dot goes green.
  2. Chats tab title reads **"Chats"**; grouped session rows show just the model in the subtitle; Pinned rows still show `profile · model`.
  3. Switch to **light mode** and across profiles → the app bar is a saturated tenant colour (acme green / globex gold) with legible text; dark mode unchanged.
  4. The Cron **New** FAB is the tenant accent with an Add icon (matches Sessions).

## Not doing (YAGNI)

- Chat code-block scroll (already implemented); a scroll-affordance fade (P2).
- Any deeper offline/queue behaviour beyond copy + styling.
- The other P1/P2 review items (separate future batches).
- Any gateway/API change.

## Open questions (non-blocking; plan may settle)

- Whether any non-`ChatScreen` caller of `StatusDot` needs the label (the `showLabel` param covers it; the plan pins the audit result).
