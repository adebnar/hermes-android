# Home Landing (2a) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The app opens to the Home tab (Mission Control activity feed) instead of the Sessions list, with the bottom nav leading with Home.

**Architecture:** A navigation-config change entirely in `HermesNav.kt` — reorder/relabel the `TABS` list, flip the start destination to `"activity"`, and point the post-setup jump at `"activity"`. The `"activity"` route id is unchanged (only its visible label changes).

**Tech Stack:** Kotlin, Jetpack Compose, Navigation Compose.

## Global Constraints

- **No bridge/gateway API changes.** Pure navigation config.
- JDK 21: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`. compileSdk/targetSdk 36, minSdk 26.
- Compile: `./gradlew :app:compileDebugKotlin --console=plain 2>&1 | tail -5`. Full suite: `./gradlew :app:testDebugUnitTest --console=plain`. Beta: `./gradlew :app:assembleBeta`.
- Do **not** touch `MissionControlScreen`, the deep-link rail, `SessionsScreen`, or any repository. Keep the `"activity"` route id.
- **No AI/assistant attribution** in commits, files, or PRs.

## File Structure

- Modify `app/src/main/java/com/hermes/client/ui/nav/HermesNav.kt` — `TABS`, `start`, post-setup nav, imports.

---

### Task 1: Home landing nav change

**Files:**
- Modify: `app/src/main/java/com/hermes/client/ui/nav/HermesNav.kt`

**Interfaces:** none exported (internal nav config).

- [ ] **Step 1: Reorder + relabel `TABS`**

Replace the existing `TABS` list:

```kotlin
private val TABS = listOf(
    Tab("sessions", "Chats", Icons.AutoMirrored.Rounded.Chat),
    Tab("activity", "Agent Activity", Icons.Rounded.Bolt),
    Tab("you", "You", Icons.Rounded.Person),
)
```

with (Home first, relabeled, Home icon):

```kotlin
private val TABS = listOf(
    Tab("activity", "Home", Icons.Rounded.Home),
    Tab("sessions", "Chats", Icons.AutoMirrored.Rounded.Chat),
    Tab("you", "You", Icons.Rounded.Person),
)
```

- [ ] **Step 2: Fix icon imports**

Add `import androidx.compose.material.icons.rounded.Home`. If `Icons.Rounded.Bolt` is now unused elsewhere in the file, remove `import androidx.compose.material.icons.rounded.Bolt` (verify with a search before removing).

- [ ] **Step 3: Flip the start destination**

Change:

```kotlin
val start = if (hasConfig) "sessions" else "setup"
```

to:

```kotlin
val start = if (hasConfig) "activity" else "setup"
```

- [ ] **Step 4: Point the post-setup jump at Home**

Change (around line 126):

```kotlin
                        nav.navigate("sessions") { popUpTo("setup") { inclusive = true } }
```

to:

```kotlin
                        nav.navigate("activity") { popUpTo("setup") { inclusive = true } }
```

- [ ] **Step 5: Compile + full unit suite + assemble**

Run: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --console=plain 2>&1 | grep -E "^e: |FAILED|BUILD" | head`
Expected: `BUILD SUCCESSFUL`, no `FAILED`. (No tests change; this confirms nothing regressed and the icon import resolves.)
Run: `./gradlew :app:assembleBeta --console=plain 2>&1 | grep -E "^e: |BUILD" | tail -2`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/hermes/client/ui/nav/HermesNav.kt
git commit -m "feat(nav): land on Home tab; bottom nav leads with Home"
```

---

### Task 2: On-device verification

**Files:** none (verification only).

- [ ] **Step 1: Build + install the beta**

Run `./gradlew :app:assembleBeta` then `adb -e install -r app/build/outputs/apk/beta/app-beta.apk`. Use a build already configured with a gateway (or complete setup against the mock at `http://10.0.2.2:8899`).

- [ ] **Step 2: Verify**

- **Cold launch (configured):** the app opens on **Home** — the Mission Control activity feed (LIVE NOW / UPCOMING / RECENT) — not the Sessions list. The bottom nav reads **Home · Chats · You** with **Home** selected.
- **Chats tab:** tapping Chats shows the Sessions list; its **New** button still creates + opens a session.
- **Notification deep-link:** launching from a notification (or `adb shell am start ... --es extra_route "chat/<id>"`) still opens that session, not Home.
- **Fresh setup:** clear data, complete setup → lands on **Home** (not Sessions).

- [ ] **Step 3: Commit (only if verification-only fixups were needed)**

```bash
git add -A
git commit -m "chore(nav): home-landing verification fixups"   # only if needed
```

---

## Self-Review

- **Spec coverage:** `TABS` reorder + relabel + Home icon (Task 1 Steps 1–2) ✓; `start = "activity"` (Step 3) ✓; post-setup jump → `"activity"` (Step 4) ✓; route id `"activity"` kept (only label changes) ✓; no touch to MissionControlScreen/deep-link/SessionsScreen/repos ✓; on-device incl. notification deep-link + fresh-setup + Chats/New regression (Task 2) ✓; no bridge changes ✓; "Needs you"/approvals/messaging excluded ✓.
- **Placeholder scan:** every code step shows the exact before/after; the only conditional is the "remove Bolt import if now unused" (guarded by a search) and the Task 2 verification-only commit.
- **Type consistency:** `Tab(route, label, icon)`, the `"activity"`/`"sessions"`/`"you"` route ids, and `Icons.Rounded.Home` are consistent with the existing `HermesNav` structure.

**Ordering note:** Task 1 is the whole change (one file, compiles + suite green). Task 2 verifies on-device.
