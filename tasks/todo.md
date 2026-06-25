# TODO — Session Parity (Direction A)

Branch: `feature/session-parity` off `develop`. Full plan: `tasks/plan.md`.

## Phase 1 — Foundation
- [ ] **T1** All-profiles list; each session under its true profile
  - [ ] `SessionDto`: add `archived`, `isDefaultProfile`; add `ProfileSessionsDto`
  - [ ] `HermesRestApi.profileSessions(limit=500)` → `/api/profiles/sessions`
  - [ ] `Models`/`Mappers`: carry `archived`; normalize default profile label
  - [ ] `SessionRepository.listAllProfiles()`
  - [ ] `SessionsViewModel.refresh()` uses all-profiles; stop active-profile scoping
  - [ ] Row shows its profile (interim, until T2)
  - [ ] mockwebserver test: profiles map to correct per-session `profile`
- [ ] **CHECKPOINT A** — human review (all-profiles list renders correctly)

## Phase 2 — Structure & pins
- [ ] **T2** Two-tier collapsible Profile→Workspace groups, persisted
  - [ ] `GroupExpansionStore` (DataStore; store collapsed set; default expanded)
  - [ ] VM: collapsed-keys flow + `toggleGroup(key)`
  - [ ] Screen: two-tier headers, collapse hides children, persisted, unique keys
  - [ ] VM test: toggle flips set; collapsed profile excludes its rows
- [ ] **T5** Pins render cross-profile + labeled device-local
  - [ ] Pin match/toggle keyed by `session.profile` (not active profile)
  - [ ] "Device only" caption on Pinned section
  - [ ] VM test: pin for profile X reported pinned when active is Y
- [ ] **CHECKPOINT B** — human review (grouping/collapse + pins on-device)

## Phase 3 — Behavior & parity
- [ ] **T3** Auto-switch active profile on open + thread profile to RPCs
  - [ ] VM `prepareOpen(session)` → `switchTo(profile)` when differing, awaited
  - [ ] Screen: tap launches `prepareOpen` then `onOpen` (switch settles first)
  - [ ] VM test: switches only for non-active profile
  - [ ] On-device: open cross-profile session end-to-end (history+resume+model)
- [ ] **T4** Archive parity verified; archived view cross-profile
  - [ ] Archived view sources across all profiles
  - [ ] Confirm active list excludes archived + refreshes on resume
  - [ ] On-device: desktop archive ↔ mobile reflects both ways
- [ ] **CHECKPOINT C** — human review (full behavioral review)

## Phase 4 — Ship
- [ ] **T6** Build, test, beta APK, ship to develop
  - [ ] Bump versionCode/versionName
  - [ ] gitleaks clean; full unit suite green
  - [ ] Build beta APK, deliver, commit, PR into `develop`
  - [ ] Update README / idea doc status
