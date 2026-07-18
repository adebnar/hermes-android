# `hermes://` Deep Links — Design

**Wave:** Quick-wins (client-only). **Branch:** `feature/hermes-deep-links` (off `dev`).

**Goal:** Accept a public `hermes://` link that routes into a specific tab or chat — the foundation for a future home-screen widget and share-sheet. Client-only; reuses the existing `pendingRoute → HermesNav.deepLinkRoute → nav.navigate` rail.

**Constraints:** Kotlin/Compose/Hilt. No AI attribution; gitleaks before push; PR into `dev`.

## Scope

- **In:** a `hermes://` VIEW/BROWSABLE intent-filter; a pure allowlist `Uri`→route mapper; wiring into the existing deep-link rail; hardening the currently-unguarded `nav.navigate` (a bad route currently crashes).
- **URLs:** `hermes://tab/sessions`, `hermes://tab/activity`, `hermes://tab/you`, `hermes://chat/<id>`.
- **Out (deferred follow-ups):** `?profile=<p>` profile-switch for chat links (has an async switch-then-navigate ordering subtlety — its own design); `hermes://cron/<id>` (cron is per-profile, same profile concern); App Links (`https://` + `autoVerify`); generating links (widget/share — later waves).

## Security & tenant notes
- A `hermes://` link is **untrusted external input** (BROWSABLE = any app/web page can fire it). The mapper is a strict allowlist returning `null` for anything unknown; unknown/malformed links are silently ignored. Never forward a raw URL segment to `nav.navigate`.
- `hermes://chat/<id>` opens in the **current active profile**. If the id belongs to another tenant, `ChatViewModel` loads against the active profile and shows "session not found" — it **fails safe** (session ids are UUIDs; no cross-tenant collision, so no other tenant's data is shown). Profile-aware chat links are a deferred enhancement.

## Architecture

### 1. `ui/nav/DeepLinkMapper.kt` (new) — pure mapper
Takes the raw URI **string** (parsed with `java.net.URI`, pure JVM — so it's unit-testable without Android `Uri`):
```kotlin
private val TAB_ROUTES = setOf("sessions", "activity", "you")

/** Map a hermes:// URI string to an internal nav route, or null if it isn't a recognised link. */
fun deepLinkRouteFor(raw: String): String? {
    if (raw.isBlank()) return null
    val uri = runCatching { java.net.URI(raw) }.getOrNull() ?: return null
    if (!"hermes".equals(uri.scheme, ignoreCase = true)) return null
    val host = uri.host ?: return null
    val segs = uri.path.orEmpty().split('/').filter { it.isNotBlank() }
    return when (host) {
        "tab" -> segs.singleOrNull()?.takeIf { it in TAB_ROUTES }
        "chat" -> segs.singleOrNull()?.takeIf { it.isNotBlank() && '/' !in it }?.let { "chat/$it" }
        else -> null
    }
}
```

### 2. `AndroidManifest.xml` (modify) — intent-filter
Add to the existing `MainActivity` `<activity>` block (already `exported="true"`):
```xml
<intent-filter android:autoVerify="false">
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="hermes" />
</intent-filter>
```

### 3. `MainActivity.kt` (modify) — read `intent.data`
In `onCreate` and `onNewIntent`, fall back to a mapped `hermes://` link when there's no `extra_route`, and consume `intent.data` (mirroring the existing `removeExtra` discipline so a config-change recreation doesn't re-fire):
```kotlin
pendingRoute.value = intent?.getStringExtra("extra_route")
    ?: intent?.data?.let { deepLinkRouteFor(it.toString()) }
intent?.removeExtra("extra_route")
intent?.data = null
```
(`onNewIntent` uses the non-null `intent`; same two-line pattern after `setIntent(intent)`.)

### 4. `HermesNav.kt` (modify) — harden the navigate
The `LaunchedEffect(deepLinkRoute)` currently does `nav.navigate(it)` unguarded — an unknown route throws. Wrap it so a bad/unknown route is ignored, not crashing (this also protects the notification `extra_route` path):
```kotlin
LaunchedEffect(deepLinkRoute) {
    deepLinkRoute?.let {
        runCatching { nav.navigate(it) }
        onDeepLinkConsumed()
    }
}
```

## Data flow
```
external hermes://tab/sessions (or chat/<id>) → VIEW intent → MainActivity onCreate/onNewIntent
  → deepLinkRouteFor(intent.data.toString()) → "sessions" / "chat/<id>" (or null → ignored)
  → pendingRoute → HermesNav.deepLinkRoute → runCatching { nav.navigate(route) } → onDeepLinkConsumed
```

## Error handling
- Unknown host / bad path / wrong scheme / malformed URI → `deepLinkRouteFor` returns null → nothing navigated.
- A mapped route that somehow isn't registered → `runCatching` swallows the navigation exception (no crash).
- `intent.data` consumed (`= null`) after read so recreation doesn't re-navigate.

## Testing
- **`DeepLinkMapperTest`** (pure JUnit, string input): each tab route maps; `hermes://chat/abc-123` → `chat/abc-123`; unknown host → null; `hermes://tab/nope` → null; `hermes://chat` (no id) → null; `hermes://chat/a/b` (2 segs) → null; wrong scheme `http://...` → null; blank/garbage → null; scheme case-insensitive.
- MainActivity/manifest/HermesNav are Android glue — verified on-device (deep links are directly testable via `adb am start -a VIEW -d "hermes://…"`).

## On-device verification
`adb shell am start -a android.intent.action.VIEW -d "hermes://tab/sessions" com.hermes.client.beta` → app opens on the Chats tab. Repeat for `tab/activity`, `tab/you`, and `hermes://chat/<real-id>` (opens that chat if in the right profile). `adb ... -d "hermes://bogus/x"` → app opens normally (link ignored, no crash).

## Files
| Action | Path |
|--------|------|
| New | `ui/nav/DeepLinkMapper.kt` + `DeepLinkMapperTest.kt` |
| Modify | `AndroidManifest.xml` (intent-filter) |
| Modify | `MainActivity.kt` (read intent.data) |
| Modify | `ui/nav/HermesNav.kt` (harden navigate) |

## Build & gates
`JAVA_HOME=…`: `:app:compileDebugKotlin`, `:app:testDebugUnitTest`, `:app:assembleBeta`. gitleaks before push; PR into `dev`.
