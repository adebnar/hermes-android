# TODO — Session Parity (Direction A)

Branch: `feature/session-parity` off `dev`. Full plan: `tasks/plan.md`.

## Phase 1 — Foundation
- [x] **T1** All-profiles list; each session under its true profile
  - [x] `SessionDto`: add `archived`, `isDefaultProfile`; add `ProfileSessionsDto`
  - [x] `HermesRestApi.profileSessions(limit=500)` → `/api/profiles/sessions`
  - [x] `Models`/`Mappers`: carry `archived`; normalize default profile label
  - [x] `SessionRepository.listAllProfiles()`
  - [x] `SessionsViewModel.refresh()` uses all-profiles; stop active-profile scoping
  - [x] Row shows its profile (interim, until T2)
  - [x] mockwebserver test: profiles map to correct per-session `profile`
- [x] **CHECKPOINT A** — code verified (58 tests green); on-device check deferred to T6 beta

## Phase 2 — Structure & pins
- [x] **T2** Two-tier collapsible Profile→Workspace groups, persisted
  - [x] `GroupExpansionStore` (DataStore; store collapsed set; default expanded)
  - [x] VM: collapsed-keys flow + `toggleGroup(key)`
  - [x] Screen: two-tier headers, collapse hides children, persisted, unique keys
  - [x] VM test: toggle flips set; collapsed profile excludes its rows
- [x] **T5** Pins render cross-profile + labeled device-local
  - [x] Pin match/toggle keyed by `session.profile` (not active profile)
  - [x] "Device only" caption on Pinned section
  - [x] VM test: pin for profile X reported pinned when active is Y
- [x] **CHECKPOINT B** — human review (grouping/collapse + pins on-device)

## Phase 3 — Behavior & parity
- [x] **T3** Auto-switch active profile on open + thread profile to RPCs
  - [x] VM `prepareOpen(session)` → `switchTo(profile)` when differing, awaited
  - [x] Screen: tap launches `prepareOpen` then `onOpen` (switch settles first)
  - [x] VM test: switches only for non-active profile
  - [x] On-device: open cross-profile session end-to-end (history+resume+model)
- [x] **T4** Archive parity verified; archived view cross-profile
  - [x] Archived view sources across all profiles
  - [x] Confirm active list excludes archived + refreshes on resume
  - [x] On-device: desktop archive ↔ mobile reflects both ways
- [x] **CHECKPOINT C** — human review (full behavioral review)

## Phase 4 — Ship
- [x] **T6** Build, test, beta APK, ship to dev
  - [x] Bump versionCode/versionName
  - [x] gitleaks clean; full unit suite green
  - [x] Build beta APK, deliver, commit, PR into `dev`
  - [x] Update README / idea doc status
