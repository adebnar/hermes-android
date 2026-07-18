# Home-Screen Quick-Launch Widget — Design

**Wave:** Quick-wins (client-only). **Branch:** `feature/home-widget` (off `dev`).

**Goal:** A home-screen widget with quick-launch buttons — **New chat**, **Chats**, **Home** — using Jetpack Glance. Reuses the `hermes://` deep links (just shipped) for launch, plus one new `hermes://new` verb for new-chat. Client-only.

**Constraints:** Kotlin/Compose/Glance/Hilt. No AI attribution; gitleaks before push; PR into `dev`.

## Scope
- **In:** a Glance `GlanceAppWidget` + receiver + provider XML + manifest receiver; the `androidx.glance:glance-appwidget` dependency; a `hermes://new` verb + `MainActivity.openNewChat()` reusing the existing share rail.
- **Out (deferred):** a dynamic "needs you" glance (no persisted attention count exists — all live socket/network; would need a persisted counter first); per-tenant accent in the widget (not available in Glance — use a static theme); a "scan" button (setup-time action, not a daily quick-launch); resizable rich content.

## Architecture

### 1. `hermes://new` verb — `ui/nav/DeepLinkMapper.kt` (modify)
`hermes://new` is **not** a static nav route (new-chat is an async `createSession` RPC), so it isn't handled by `deepLinkRouteFor`. Add a pure predicate:
```kotlin
fun isNewChatLink(raw: String): Boolean {
    val uri = runCatching { java.net.URI(raw) }.getOrNull() ?: return false
    return "hermes".equals(uri.scheme, ignoreCase = true) && uri.host == "new"
}
```

### 2. `MainActivity.kt` (modify) — handle `hermes://new`
Extract the new-chat rail from `handleShare` into a reusable helper (identical `connect → refresh → createSession → pendingRoute` sequence, minus the share payload):
```kotlin
/** Create a fresh chat and navigate to it (widget "New chat" / hermes://new). No-op if unconfigured. */
private fun openNewChat() {
    if (credentialStore.load() == null) return
    lifecycleScope.launch {
        chat.connect() // idempotent; a cold start has no socket yet
        runCatching {
            profileManager.refresh() // load active profile so the session isn't orphaned to default
            chat.createSession(profileManager.active.value)
        }.onSuccess { id -> pendingRoute.value = "chat/$id" }
            .onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.widget.Toast.makeText(this@MainActivity, "Couldn't start a chat", android.widget.Toast.LENGTH_SHORT).show()
            }
    }
}
```
In `onCreate` and `onNewIntent`, branch on the incoming `hermes://new` link before the existing route handling:
```kotlin
val data = intent?.data
if (data != null && isNewChatLink(data.toString())) {
    openNewChat()
    intent?.data = null
} else {
    pendingRoute.value = intent?.getStringExtra("extra_route")
        ?: data?.let { deepLinkRouteFor(it.toString()) }
    intent?.removeExtra("extra_route")
    intent?.data = null
}
```
(`onNewIntent` uses the non-null `intent`; same branch.)

### 3. Widget — `widget/HermesWidget.kt` + `widget/HermesWidgetReceiver.kt` (new)
A `GlanceAppWidget` rendering three buttons; each starts an **explicit** VIEW intent (`setPackage(context.packageName)`) into the already-`singleTask` MainActivity:
```kotlin
class HermesWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { Content(context) }
    }

    @Composable
    private fun Content(context: Context) {
        Column(
            modifier = GlanceModifier.fillMaxSize().background(ColorProvider(Color(0xFF3B3BAF))).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Item(context, "New chat", "hermes://new")
            Item(context, "Chats", "hermes://tab/sessions")
            Item(context, "Home", "hermes://tab/activity")
        }
    }

    @Composable
    private fun Item(context: Context, label: String, uri: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(context.packageName)
        Text(
            label,
            modifier = GlanceModifier.fillMaxWidth().padding(vertical = 6.dp).clickable(actionStartActivity(intent)),
            style = TextStyle(color = ColorProvider(Color.White), fontSize = 16.sp, textAlign = TextAlign.Center),
        )
    }
}

class HermesWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = HermesWidget()
}
```
(Static color — per-tenant accent is a Compose-runtime local unavailable in Glance. Exact Glance API/imports may need minor adjustment to the resolved `glance-appwidget` version; behavior is the contract.)

### 4. Resources + manifest
- `res/xml/hermes_widget_info.xml` — `<appwidget-provider>` (minWidth/Height, `resizeMode`, `widgetCategory="home_screen"`, `initialLayout="@layout/glance_default_loading_layout"` which `glance-appwidget` provides, a `description` string).
- `res/values/strings.xml` — add `<string name="widget_description">Quick actions for Hermes</string>`.
- `AndroidManifest.xml` — a `<receiver android:name=".widget.HermesWidgetReceiver" android:exported="true">` with an `APPWIDGET_UPDATE` intent-filter + `android.appwidget.provider` meta-data (mirror the existing `NotificationActionReceiver` receiver block).

### 5. Dependency
`gradle/libs.versions.toml`: `glance = "1.1.1"` + `glance-appwidget = { module = "androidx.glance:glance-appwidget", version.ref = "glance" }`. `app/build.gradle.kts`: `implementation(libs.glance.appwidget)`. (If 1.1.1 doesn't resolve against the current AGP/Compose, bump to the latest 1.x that does.)

## Data flow
```
widget button tap → actionStartActivity(VIEW hermes://…, pkg=self) → MainActivity (singleTask, onNewIntent/onCreate)
  hermes://new  → isNewChatLink → openNewChat() → connect→refresh→createSession → pendingRoute="chat/$id" → nav
  hermes://tab/sessions | tab/activity → deepLinkRouteFor → pendingRoute → nav
```

## Error handling
- `openNewChat` when unconfigured → no-op (returns early). On a createSession failure → toast, no crash (mirrors share).
- Widget buttons target an explicit intent (setPackage self) — can't be hijacked.
- Deep-link routes reuse the already-hardened `runCatching { nav.navigate }`.

## Testing
- **`DeepLinkMapperTest`** (extend, pure): `isNewChatLink("hermes://new")` → true; `hermes://new/x`, `hermes://tab/sessions`, `http://new`, blank/garbage → false.
- Glance widget, receiver, `openNewChat`, and MainActivity intent glue are Android — verified on-device (best-effort).

## On-device verification
- `adb shell am start -a android.intent.action.VIEW -d "hermes://new" com.hermes.client.beta` → opens a new chat (if configured) / no crash if not.
- Confirm the widget is registered: `adb shell dumpsys appwidget | grep -i hermes` (provider present), and it appears in the launcher's widget picker. Placing + tapping on the emulator is best-effort; the deep-link targets are already verified from wave 2.

## Files
| Action | Path |
|--------|------|
| Modify | `ui/nav/DeepLinkMapper.kt` (`isNewChatLink`) + `DeepLinkMapperTest.kt` |
| Modify | `MainActivity.kt` (`openNewChat` + hermes://new branch) |
| New | `widget/HermesWidget.kt`, `widget/HermesWidgetReceiver.kt` |
| New | `res/xml/hermes_widget_info.xml`; add `widget_description` to `res/values/strings.xml` |
| Modify | `AndroidManifest.xml` (receiver) |
| Modify | `gradle/libs.versions.toml`, `app/build.gradle.kts` (glance dep) |

## Build & gates
`JAVA_HOME=…`: `:app:compileDebugKotlin`, `:app:testDebugUnitTest`, `:app:assembleBeta`. gitleaks before push; PR into `dev`.
