# Share-to-Hermes — Design

**Date:** 2026-07-03
**Status:** Approved (brainstorming) → implementation plan next
**Branch:** `feature/share-to-hermes`
**Parent idea:** `docs/ideas/native-command-surface.md` (Ship 2 — Native Pager, piece 1 of 4)

## Goal

Let the user share a link or text from any Android app into Hermes: it opens a new chat in the active profile with the shared content pre-filled in the composer, ready to review, annotate, and send. Native Android + existing bridge APIs only — no gateway/bridge changes. This is a capability the official WebView app (PR #52673) structurally cannot offer.

## Hard constraints

- **No bridge/gateway API changes.** Reuse `ChatRepository.createSession()` (`session.create`) and the existing chat submit path; no new endpoints.
- Native Android: an `ACTION_SEND` intent-filter + the existing deep-link rail.
- Follow existing patterns: Hilt DI, MVVM + StateFlow, pure unit-tested helpers, the `pendingRoute` → `HermesNav.deepLinkRoute` navigation already in `MainActivity`.
- No AI/assistant attribution in commits, files, or PRs.

## Scope (v1)

- **In:** shared **text/plain** (links, selected text, notes) → new session in the **active profile** → chat opens with the content in the composer (not auto-sent).
- **Out (documented follow-ups):** images/screenshots (`image/*`, needs `/api/files/upload` + composer attachments), a profile/session chooser on the share path, `ACTION_SEND_MULTIPLE`, auto-send.

## What the app already provides (grounding)

- `MainActivity` (`@AndroidEntryPoint`) already handles intents: it reads `extra_route` into `pendingRoute: MutableState<String?>`, clears it, and handles `onNewIntent`; `HermesNav(deepLinkRoute = pendingRoute)` navigates when it changes.
- `ChatRepository.createSession(): String` → `session.create` WS RPC, returns a new session id.
- `ChatScreen` owns the composer text locally: `var draft by remember { mutableStateOf("") }`; send goes through `ChatViewModel.send(text)`.
- `ChatViewModel.open(id)` runs on chat open (loads history, resumes, loads providers/profiles/commands).
- Gateway-configured check: `credentialStore.load() != null` (in `MainActivity.onCreate`, drives `hasConfig`).

## Architecture

A pure text-extractor + a one-shot in-memory handoff store, wired into `MainActivity`'s existing intent/deep-link path and the chat composer. Each unit is small and independently testable.

### Components

1. **Pure `sharedText(action, type, subject, text): String?`** — `app/src/main/java/com/hermes/client/share/ShareIntent.kt`. The unit-tested core; takes primitives (not an `Intent`) so it stays pure/JVM-testable.
   - Returns null unless `action == Intent.ACTION_SEND` and `type` starts with `"text/"`.
   - Otherwise builds the shared text from `subject` + `text`:
     - both present and different → `"$subject\n$text"` (a shared link often carries the page title in SUBJECT and the URL in TEXT);
     - only one present → that one, trimmed;
     - both blank/absent → null.
   - Signature: `fun sharedText(action: String?, type: String?, subject: String?, text: String?): String?`

2. **`PendingShareStore`** — `app/src/main/java/com/hermes/client/share/PendingShareStore.kt`. A `@Singleton` in-memory one-shot handoff (no persistence needed — it lives for one navigation within the process).
   - Holds a nullable `Pair<String, String>?` = (sessionId, text).
   - `fun put(sessionId: String, text: String)`.
   - `fun take(sessionId: String): String?` — returns the text and clears the store **only if** the stored sessionId matches (race-proof: a normal chat open won't consume a share meant for a different session).
   - Provided via Hilt `@Provides @Singleton` in `AppModule` (mirrors the other store providers).

3. **`MainActivity` share branch** — inject `ChatRepository` (Hilt). Add a `handleShare(intent)` called from `onCreate` and `onNewIntent`:
   - `val shared = sharedText(intent.action, intent.type, intent.getStringExtra(EXTRA_SUBJECT), intent.getStringExtra(EXTRA_TEXT))`.
   - If `shared == null`, do nothing (fall through to existing `extra_route` handling).
   - If the gateway is not configured (`credentialStore.load() == null`), skip creating a session (the app opens to setup); optionally set a pending message — v1 drops the share.
   - Else `lifecycleScope.launch { chat.connect(); runCatching { chat.createSession() }.onSuccess { id -> pendingShareStore.put(id, shared); pendingRoute.value = "chat/$id" }.onFailure { /* brief toast: couldn't start a chat */ } }`. **`chat.connect()` first** — on a cold start from a share no screen has opened the socket yet, and `createSession` would otherwise block forever on the ready-gate. `connect()` is idempotent (made so in the notifications work), so this is safe even when already connected.

4. **`ChatViewModel` pre-fill** — inject `PendingShareStore`. In `open(id)`, `val shared = pendingShareStore.take(id)`; expose `initialDraft: StateFlow<String?>` set to `shared` (null for a normal open). Add `fun clearInitialDraft()`.

5. **`ChatScreen` pre-fill** — collect `initialDraft`; `LaunchedEffect(initialDraft) { initialDraft?.let { if (it.isNotEmpty()) { draft = it; vm.clearInitialDraft() } } }`. The composer TextField is unchanged otherwise.

6. **Manifest** — add to `MainActivity`'s `<activity>` (already `exported="true"` as launcher):
   ```xml
   <intent-filter>
       <action android:name="android.intent.action.SEND" />
       <category android:name="android.intent.category.DEFAULT" />
       <data android:mimeType="text/plain" />
   </intent-filter>
   ```

## Data flow

```
Another app → Share → "Hermes"
  → MainActivity (ACTION_SEND, text/plain)
  → sharedText(...) [pure] → text
  → gateway configured? → chat.connect() (idempotent) → chat.createSession() → new id
  → PendingShareStore.put(id, text); pendingRoute = "chat/<id>"
  → HermesNav deep-links to chat/<id>
  → ChatViewModel.open(id) → PendingShareStore.take(id) → initialDraft
  → ChatScreen sets draft = initialDraft (once)
  → user reviews / annotates / sends via ChatViewModel.send(text)
```

## Error handling

- **Not configured** (`credentialStore.load() == null`): the share does not create a session; the app opens to setup. The shared text is dropped in v1 (documented limitation).
- **`createSession` fails** (not connected / RPC error): no deep-link fires; the app opens to the sessions list; a brief message ("Couldn't start a chat — try again") is surfaced. Shared text is dropped.
- **Cold start latency:** `createSession` awaits the WS ready-gate (existing `client.call` behavior), so it's a short delay on a cold launch, not a failure.
- **Non-text share** (image, etc.): `sharedText` returns null → ignored (Hermes won't be offered for non-text in the share sheet anyway, given the `text/plain` filter).

## Testing

- **Unit (pure), TDD — `ShareIntentTest`:**
  - `ACTION_SEND` + `text/plain` + text only → that text (trimmed);
  - subject + text (different) → `"subject\ntext"`;
  - subject == text → text once (no duplication);
  - only subject → subject;
  - non-`ACTION_SEND` action → null;
  - non-`text/*` type → null;
  - both blank/null → null.
- **`PendingShareStore`:** small unit test — `put` then `take(sameId)` returns text and a second `take(sameId)` returns null; `take(otherId)` returns null and leaves the value.
- **On-device (emulator/device):** from Chrome (or any app) share a URL → the share sheet lists Hermes → tap → a new Hermes chat opens with the URL in the composer; add a note and send.

## Not doing (YAGNI)

- Images / `image/*` / `/api/files/upload` (fast follow-up).
- Profile or existing-session chooser on the share path (v1 = active profile, new session).
- `ACTION_SEND_MULTIPLE`, auto-send, or a custom share Chooser UI.
- Any new gateway endpoint.

## Open questions (non-blocking; plan may settle)

- Whether to keep the shared text across a not-configured share (route through setup then land it) — deferred; v1 drops it.
- Exact "couldn't start a chat" surface (Toast vs a Snackbar on the sessions screen) — plan picks one.
