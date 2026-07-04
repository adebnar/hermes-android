# Native Command Surface

## Problem Statement
**How might we** make Hermes for Android a *full-reach, focused-action* remote for self-hosted agents — one that lets you see and unblock any agent from your phone using only the bridge's existing APIs plus native Android capabilities — **without** shipping Hermes core to the device (the official WebView approach) or requiring a single new gateway endpoint?

## Status (as of 2026-07-04, production 0.1.31)

- **Ship 1 — Model sheet:** ✅ **shipped.** Model picker clarity (0.1.22) + the model selection sheet with provider tabs, local favorites, descriptions, and session-vs-default (0.1.23). The switch-failure reason is surfaced in chat; a **Settings** inline-error remains a minor follow-up.
- **Ship 2 — Native Pager:** mostly shipped.
  - Share-to-Hermes: text ✅ (0.1.24), images ✅ (0.1.27).
  - Quick Settings tile ✅ (0.1.26).
  - **App Widget:** ❌ **shelved** — its headline content (pending approvals) is already covered by the high-priority notification, sits at "0" most of the time, and is only fresh while the foreground service runs. Opt-in only if revisited.
  - **Voice quick-capture:** ⏸ **deferred** (off-by-default vitamin).
- **Ship 3 — Situation Room:** ✅ **shipped, reframed as "Home"** (see `activity-home-and-cron-response.md`). Rather than a new screen, we promoted the existing Mission Control feed: cron-response inline (0.1.29), Home landing + tab relabel (0.1.30, plus a pager launch-crash fix), and a "Needs you" strip (0.1.31). Header now reads "Home".

**Key assumptions — resolved.** The WS stream (`/api/ws`) emits `approval.request/responded`, `run.*`, `tool.*`, `message.*` — but **no** cron-finished or messaging events, and **no** REST list of pending approvals. So notifications became **approvals-only**, and "Needs you" is **cron-only** (failed/overdue); pending approvals are best-effort/live-only, and messaging-waits (`/api/pairing`) is not wired in the app. Share-to-Hermes starts a **new** session. The App-Widget freshness bet was never validated (shelved).

**Remaining:** App Widget (opt-in), Voice (deferred), and minor polish (QS-tile `runBlocking`→async, `canStart` dedup, New-FAB last-used-profile, model-sheet Settings inline-error).

## Context & Constraints (locked)
- **Architecture:** thin **native** client → existing Hermes bridge (REST at `:9119` + WS). Never wrap the desktop renderer (that's PR #52673's bet; this is the opposite bet).
- **No bridge/gateway API changes.** Build only against **documented existing endpoints** (see `Hermes API's As of 7-3-2026`) + **native Android APIs**. If a feature seems to need a new endpoint, it's out until it doesn't.
- **Primary user:** the author, dogfooding a multi-tenant consulting workflow — but every choice stays clean enough to publish (dogfood-then-publish).
- **Design spine:** *reach everything, act on a few.* Full navigation/visibility into any profile → project → session → file; a small curated action palette (continue/redirect, approve/deny, switch model, run a saved job, share-in). Deep editing stays on desktop.

### API reality check (why "no bridge changes" is not a limiter)
Everything the promo cards imply is already reachable:
- **Events (real-time push):** `/api/ws`, `/api/events`, `/api/pub` — the foreground service already consumes one of these.
- **Model sheet:** `/api/model/options`, `/api/model/info`, `POST /api/model/set`, and the per-session `/model … --session` slash.
- **Approvals / waiting:** approval events on the WS stream; `/api/pairing` for messaging-user approvals.
- **Cron / automations:** `/api/cron/jobs`, `/api/cron/jobs/{id}/runs`, `/api/cron/jobs/{id}/trigger`, `/api/cron/blueprints`.
- **Files & agent changes (later phases):** `/api/files`, `/api/fs/*`, `/api/git/review/diff`, `/api/git/file-diff`, `/api/git/status`.
- **Analytics (later phase):** `/api/analytics/usage`, `/api/analytics/models`.
- **Skills (later phase):** `/api/skills`, `/api/skills/hub/search`.
- **Voice:** native `SpeechRecognizer` preferred; `/api/audio/transcribe` is a server-side fallback.

**Webhooks are inbound** (external service → Hermes → deliver to Telegram/Discord/…). They are *not* a way to push to a phone that has no public URL, so they play **no role** in the pager. Managing them (`/api/webhooks`) is a possible later "manage" feature, not part of this.

## Recommended Direction
Ship in three increments, cheapest-and-most-in-flight first, each independently useful:

**Ship 1 — First-class Model sheet (quick win).** Replace the plain picker with provider tabs (OpenAI/Claude/Gemini/More), **starred favorites** (stored locally in DataStore — no bridge change), one-line model descriptions, an explicit **"this session vs. default"** control, and — because we just lived through it — an **inline reason when a switch fails** (e.g. "Unknown provider"). Self-contained, one-to-two days, extends work already shipped. *Endpoints: `/api/model/options`, `/api/model/info`, `/api/model/set`, `/model --session`.*

**Ship 2 — The Native Pager (the moat).** The things a wrapped web page structurally cannot do, all native, zero bridge changes:
- **App Widget** (Jetpack Glance): pending-approvals count · agents running · last cron result; tap deep-links to the session/Situation Room. Fed by the foreground service's live WS cache (no timer-polling when the service is on).
- **Quick Settings tile** (`TileService`): arm/disarm the notifier + connection in one tap.
- **Share-to-Hermes** (`ACTION_SEND` target): share a link/selection/screenshot from any app → pick profile/session → send via the existing `prompt.submit` WS RPC (image via `/api/files/upload` if needed).
- *(Deferred within this ship)* **Voice quick-capture:** native `SpeechRecognizer` → `prompt.submit`. Off by default.

**Ship 3 — Situation Room home (where the pager lands).** A home surface that opens to **what needs you** (pending approvals, failed/overdue cron, agents waiting), then **what's running**, then **recent** — omnibox secondary. This is the deep-link target for Ship 2's widget/tile/share. *Endpoints: WS events + `/api/cron/jobs{,/{id}/runs}`, `/api/pairing`, `/api/profiles/sessions`.*

Rationale: Ship 1 is a cheap, satisfying win that closes the loop on the recent model bug. Ship 2 is the strategic differentiator — "use native APIs" is you telling me the moat is OS integration, which the WebView app can't copy. Ship 3 is the home those native entry points point into, so it comes last.

## Key Assumptions to Validate
- [ ] **The WS stream emits the signals the pager needs.** Confirmed: `approval.request`. Unconfirmed: distinct, subscribable events for **cron-finished (success/fail)**, **agent running/idle**, **messaging reply**. Test: watch `/api/ws` (and compare `/api/events`, `/api/pub`) live while a cron runs and a message arrives; note exact event `type` strings. *(Same open item flagged by the push-notifications work.)*
- [ ] **A widget can stay fresh cheaply.** Bet: reuse the foreground service's live socket rather than a polling `WorkManager` job. Test: measure widget staleness/battery with the service on vs off (widget-without-service must fall back to a bounded REST poll — pick an interval).
- [ ] **Share-to-Hermes has a clear target model.** Open UX decision: does a shared item start a **new** session (which profile/cwd?) or attach to an existing one? Test: prototype the share sheet with a profile/session picker; see what feels right in daily use.

## MVP Scope
**In (Ship 1 + the widget/tile/share of Ship 2):**
- Model sheet: provider tabs, local favorites, descriptions, session-vs-default, failure reason.
- App Widget (approvals/running/last-cron) + Quick Settings tile + Share-to-Hermes target.
- Situation Room as a minimal "needs you / running / recent" list (can land with Ship 2 or 3).

**Out (this MVP):**
- Voice capture (native, but a vitamin — enable later behind a toggle).
- Full analytics dashboard, file browser, skills catalog, workspace tree (all API-available; deferred — see below).

## Not Doing (and Why)
- **Full analytics dashboard (#2)** — `/api/analytics/usage` exists, but charts are desktop-grade and a vitamin on a phone. A later "one number + sparkline per profile" painkiller is the mobile-right version, not a dashboard.
- **File browser (#5) / workspace tree (#6)** — `/api/fs/*` exists, but *browsing* a filesystem on a phone is low-value. The mobile-right version is **files-as-context** later: "what the agent changed this session" via `/api/git/review/diff`, plus the existing `@`-mention (`complete.path`) — not a Finder clone.
- **Skills catalog browsing (#4)** — `/api/skills` exists, but on mobile the win is *launching* a known job, not browsing. Revisit as a share/quick-action target, not a catalog.
- **Wrapping the desktop renderer (PR #52673)** — the explicit architectural bet we're rejecting; it forecloses every native-integration advantage above.
- **Webhook management UI** — webhooks are inbound automation, orthogonal to the phone-as-remote job; possible later "manage" feature, not now.
- **Any new gateway endpoint** — hard constraint.

## Open Questions
- Which WS stream (`/api/ws` vs `/api/events` vs `/api/pub`) is canonical for this client, and does it carry cron-finished + agent-running signals — or must those be inferred/polled from `/api/cron/*`?
- Widget refresh strategy when the foreground service is **off**: acceptable poll interval vs battery, or is the widget simply "last known + tap to refresh"?
- Share-to-Hermes: new-session vs existing-session, and default profile/cwd selection.
