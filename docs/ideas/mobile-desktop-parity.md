# Mobile↔Desktop Session Parity

## Problem Statement
How might we make the mobile session list a faithful mirror of the desktop —
right profile, right archive state, scannable groups — without pretending to
sync a "pin" feature the gateway doesn't actually have?

## Recommended Direction
Switch the mobile list from `/api/sessions?profile=<active>` to
`/api/profiles/sessions`, which returns every session tagged with its real
`profile` plus `profile_totals`. This fixes the "wrong profile shown" bug at the
root (the app no longer guesses the profile from the active selection) and
simultaneously delivers the desktop's cross-profile view.

The mobile list **defaults to all-profiles** (a true desktop mirror). Render a
two-tier, collapsible tree: **Profile** (count from `profile_totals`) →
**Workspace** (`cwd` basename). Persist each group's expand/collapse state
locally so the layout survives navigation.

Each session carries its true `profile`, so:
- Tapping a session in a non-active profile **auto-switches the active profile**
  to that session's profile before opening it (the per-profile state DB requires
  the active profile to be set for resume/slash/submit).
- That same per-session profile is threaded into resume/slash calls, making
  model-switch and resume more robust as a side effect.

Archive already mirrors desktop — `archived` is a real server field on every
session — so this is purely a verify-the-refresh task, optionally surfacing
archived state inline.

Pins remain **device-local** and are explicitly **labeled device-local** in the
UI. True pin sync is filed upstream as a Hermes feature request (a `pinned` bool
on the session model + PATCH support) — the only path that can actually sync
desktop↔mobile.

## Ground Truth (verified against the live gateway, 2026-06-24)
- `/api/profiles/sessions` → `{ sessions[], total, profile_totals, limit, offset,
  errors }`; each session includes `profile`, `is_default_profile`, `archived`,
  `last_active`, `preview`, `is_active`, `cwd`. Live totals: default 29,
  personal 70, odos 13, semiotic 4, dito 3 (119 total).
- `PATCH /api/sessions/{id}` accepts only `{ title, archived, profile }`.
- **No pin/star/favorite concept exists** anywhere: not on the session object,
  not in any of the 197 API paths, not in the 100+-key gateway config. Desktop
  "pins" are therefore a desktop-frontend (localStorage) feature, invisible to
  the gateway API and to mobile.

## Key Assumptions to Validate
- [ ] Desktop pins are localStorage-only — confirm via desktop devtools (strong
      evidence: zero server pin surface). If false, find where they live and revisit.
- [ ] `/api/profiles/sessions` supports the archived filter + paging the same way
      (`personal` alone has 70 sessions; `limit=50` needs paging or a higher limit).
- [ ] Resume/slash/submit still require the active profile to be set for the
      per-profile DB, even when the list is cross-profile — hence the auto-switch
      on tapping a cross-profile session (actions are profile-scoped; reads aren't).

## MVP Scope
**IN:** cross-profile list via `/api/profiles/sessions`, defaulting to all
profiles; Profile→Workspace collapsible groups with persisted expand state;
per-session correct `profile` threaded to resume/slash/submit; auto-switch active
profile when opening a session from another profile; archive refresh verified;
"device-local" label on pins.

**OUT:** server-synced pins; custom sort/reorder; message-level grouping changes.

## Not Doing (and Why)
- **Server-synced pins** — the gateway exposes no pin concept; blocked until an
  upstream `pinned` field exists. Filed as a Hermes feature request.
- **Storing pins in `/api/config`** — unsafe; that endpoint is the entire gateway
  config (models, providers, secrets), and a bad PUT could corrupt it.
- **Re-deriving profile client-side** — this guessing is the root cause of the
  current "wrong profile" bug; use the server's `profile` field instead.

## Resolved Decisions
- Default view: **all-profiles** (true desktop mirror), not single-active-profile.
- Opening a session from another profile: **auto-switch** the active profile to it.

## Next Step
Break the recommended direction into tasks via `/plan` (or
`agent-skills:planning-and-task-breakdown`). No code written yet.
