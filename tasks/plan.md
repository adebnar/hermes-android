# Plan — Direction A: Mobile↔Desktop Session Parity

Spec: `docs/ideas/mobile-desktop-parity.md`
Branch: `feature/session-parity` off `develop` (PRs into `develop`; never push `main`)

## Goal
Make the mobile session list a faithful desktop mirror: all profiles in one
view with each session under its **true** profile, two-tier collapsible
Profile→Workspace groups with persisted expand state, correct-profile threading
into resume/slash/submit, auto-switch active profile on cross-profile open, and
verified archive parity. Pins stay device-local and are labeled as such.

## Out of scope
Server-synced pins (no gateway pin concept — filed upstream), custom sort/reorder,
message-level grouping changes, cross-profile message-content search.

---

## Verified ground truth (live gateway, 2026-06-24)
- `GET /api/profiles/sessions?limit=N` → `{ sessions[], total, profile_totals,
  limit, offset, errors }`. Each session carries `id, profile, is_default_profile,
  archived, cwd, last_active, preview, model, title, message_count, source, …`.
- Live `profile_totals`: `default:29, personal:70, odos:13, semiotic:4, dito:3`
  (total 119). A single page must request `limit ≥ total` (use 500) — no paging in MVP.
- `PATCH /api/sessions/{id}` accepts only `{ title, archived, profile }`.
- No pin/star/favorite anywhere (197 paths, all config keys checked).

## Dependency graph
```
T1 (all-profiles data + list)  ──┬──> T2 (collapsible groups)
                                 ├──> T3 (auto-switch + profile threading)
                                 ├──> T4 (archive parity)
                                 └──> T5 (pins cross-profile + label)
T6 (ship) depends on T1..T5
```
T1 is the enabling slice. T2–T5 are independent of each other and may land in
any order after T1. Each task is a complete vertical path (data → VM → UI →
test), not a horizontal layer.

---

## Phase 1 — Foundation

### T1 — All-profiles list, each session under its true profile
Replace the single-profile data source so the list shows every session tagged
with the profile the server reports (fixes the "wrong profile shown" bug at root).

Changes:
- `Dtos.kt`: add `archived: Boolean = false` and `@SerialName("is_default_profile")
  isDefaultProfile: Boolean = false` to `SessionDto`; add
  `ProfileSessionsDto(sessions, total, profileTotals: Map<String,Int>, errors)`.
- `HermesRestApi.kt`: add `suspend fun profileSessions(limit: Int = 500):
  ProfileSessionsDto` hitting `/api/profiles/sessions?limit=$limit&order=recent`.
- `domain/Models.kt` + `Mappers.kt`: carry `archived` (already has `profile`,
  `workspace`). Normalize a null/blank `profile` to `"default"` when
  `isDefaultProfile`.
- `SessionRepository.kt`: add `suspend fun listAllProfiles(): List<Session>`.
- `SessionsViewModel.kt`: `refresh()` calls `listAllProfiles()`; stop scoping the
  list to the active profile (keep the active-profile subtitle for context only).
- `SessionsScreen.kt`: each row's supporting text shows its profile (until T2's
  profile grouping lands).

Acceptance:
- Opening Sessions shows sessions from **all** profiles (≈119), each with the
  correct profile, regardless of which profile is active.
- Switching the active profile no longer reloads/filters the visible list.

Verify:
- `JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr/Contents/Home ./gradlew :app:testDebugUnitTest`
- New mockwebserver test: a `/api/profiles/sessions` payload with two profiles maps
  to domain sessions whose `profile` matches the server (not the active profile).

### ✅ Checkpoint A — human review
Confirm the all-profiles list renders with correct profiles before adding UX.

---

## Phase 2 — Structure & pins

### T2 — Two-tier collapsible Profile→Workspace groups, persisted
Group the list by Profile (top, count from `profile_totals`/derived) → Workspace
(`cwd` basename). Headers are tap-to-collapse/expand; state persists locally.

Changes:
- New `GroupExpansionStore` (DataStore, mirrors `PinStore`) keyed by
  `"p:<profile>"` and `"w:<profile>/<workspace>"`; default = expanded;
  store only the *collapsed* set.
- `SessionsViewModel`: expose collapsed-keys `StateFlow` + `toggleGroup(key)`.
- `SessionsScreen`: render two-tier headers; a collapsed profile hides its
  workspaces and rows; a collapsed workspace hides its rows. Profile order:
  active profile first, then by descending count; "default" handling explicit.
  Keep unique LazyColumn keys.

Acceptance:
- Tapping a profile header collapses all its workspaces+rows; tapping again
  expands. Same for a workspace header. Collapse state survives leaving the
  screen and an app restart.

Verify:
- `./gradlew :app:testDebugUnitTest` (VM test: `toggleGroup` flips collapsed set;
  collapsed profile excludes its rows from the rendered model).

### T5 — Pins render in the cross-profile list + labeled device-local
In a cross-profile list, pin matching must use each session's **own** profile,
not the active one, or pins vanish.

Changes:
- `SessionsViewModel`: expose the raw pinned token set (`"<profile>/<id>"`);
  the UI checks `"${session.profile}/${session.id}"`. `togglePin(session)` uses
  `session.profile`.
- `SessionsScreen`: small "Device only" caption on the Pinned section header (and
  optionally in the pin menu item) so it's clear pins don't sync to desktop.

Acceptance:
- A session pinned while its profile was active still shows pinned when a
  different profile is active. The Pinned section reads as device-local.

Verify:
- `./gradlew :app:testDebugUnitTest` (VM test: a pinned token for profile X is
  reported pinned for that session even when active profile is Y).

### ✅ Checkpoint B — human review
Confirm grouping/collapse and pin behavior on-device before behavioral changes.

---

## Phase 3 — Behavior & parity

### T3 — Auto-switch active profile on open + thread profile to RPCs
Opening a session from another profile must switch the active profile to it
**before** the chat loads (history/resume read `profileManager.active.value`).

Changes:
- `SessionsViewModel`: add `suspend fun prepareOpen(session): Unit` that calls
  `profileManager.switchTo(session.profile)` only when it differs from active,
  and awaits completion.
- `SessionsScreen`: row tap does `scope.launch { vm.prepareOpen(s); onOpen(s.id) }`
  so navigation happens after the switch settles.
- Sanity-check `ChatViewModel.open()` now sees the correct active profile for
  `history()`/`resume()` (already threads `profileManager.active.value`).

Acceptance:
- With active = `personal`, tapping an `odos` session switches active to `odos`,
  loads its history, resumes without "session not found", and model-switch targets
  the right profile. The Sessions subtitle reflects the new active profile on return.

Verify:
- `./gradlew :app:testDebugUnitTest` (VM test: `prepareOpen` for a non-active
  profile calls `switchTo(session.profile)`; for the active profile it does not).
- On-device: open a session from a non-active profile end-to-end.

### T4 — Archive parity verified (and archived view cross-profile)
Archive is already server state (`archived` field); confirm parity and remove the
single-profile assumption from the archived view.

Changes:
- `ArchivedSessionsViewModel`/`Screen`: source archived sessions across all
  profiles (filter `archived == true` from `listAllProfiles()`, or an
  `archived=only` cross-profile call) instead of per-active-profile.
- Confirm the active list already excludes archived (server default) and refreshes
  on resume.

Acceptance:
- A session archived on desktop is absent from the mobile active list after
  refresh and present in the mobile Archived view (and vice-versa). Archived view
  shows archived sessions from every profile.

Verify:
- `./gradlew :app:testDebugUnitTest`
- On-device: archive on desktop → pull mobile → gone from active, present in Archived.

### ✅ Checkpoint C — human review
Full behavioral review before building the beta.

---

## Phase 4 — Ship

### T6 — Build, test, beta APK, ship to develop
- Bump `versionCode`/`versionName` in `app/build.gradle.kts`.
- `gitleaks git --no-banner --redact` (must be clean).
- Full unit suite green.
- Build the **beta** variant APK, deliver to the user, commit to
  `feature/session-parity`, open PR into `develop`.
- Update `README.md`/`docs/ideas/mobile-desktop-parity.md` status as needed.

Acceptance: signed beta APK installs alongside release; all parity behaviors work
on-device; PR open into `develop` with green CI.

---

## Risks & notes
- **Paging**: one `limit=500` page covers current volume (119). If totals grow,
  add offset paging — tracked, not in MVP.
- **Message search** stays active-profile-scoped (title filter is client-side over
  the full list, so it already spans profiles). Cross-profile message search is a
  follow-up.
- **`profile` null/default**: default profile may report `profile=null` +
  `is_default_profile=true`; normalize to a stable `"default"` label everywhere
  (grouping, pin tokens, switchTo).
- **Pins remain device-local by design** — gateway has no pin concept; do NOT
  store pins in `/api/config` (that's the whole gateway config; corruption risk).
