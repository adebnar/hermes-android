# Activity Home + Cron Response-at-a-Glance

## Problem Statement
**How might we** make opening the app land you on *what's happening and what needs you* (never a stale resumed chat), and let you see *what a scheduled job actually answered* without wading through its full transcript — using only existing endpoints + native APIs?

## Status — ✅ shipped (production 0.1.31)

- **Piece 1 — Cron response inline:** ✅ **0.1.29.** Tap a cron run in the feed → its final assistant response expands inline (Product-level, last-tool-result fallback), with "View full chat".
- **Piece 2 — Home:** ✅
  - **2a — Home landing + tab relabel** (0.1.30): the app opens to the **Home** tab (Home · Chats · You); notification taps still deep-link. Also fixed a latent MissionControl pager launch-crash exposed by landing there.
  - **2b — "Needs you" strip + header rename** (0.1.31): a red section pins **failed/overdue cron** to the top of Home (deduped from the feed); the header reads "Home".

**Honesty held:** "Needs you" is **cron-only** — messaging-waits (`/api/pairing`) isn't wired in the app and pending approvals are WS-only/transient (no REST list), so both were deliberately excluded. Auto-refresh rides the existing `ON_RESUME` reload. This idea doc is fully delivered.

## Context (grounded in the code)
- **Launch today:** `HermesNav` sets `start = if (hasConfig) "sessions" else "setup"` — a cold launch opens the **Sessions list**. A notification tap already deep-links to `chat/<id>` via the `extra_route` rail.
- **Agent Activity today:** the `activity` tab is **Mission Control** — already a "unified activity feed merging conversations + cron for the active profile, time-grouped." Cron runs are stored as real `source="cron"` **sessions**; tapping one opens the *full* transcript. `ActivityItem.status` already carries cron `last_status`; `CronJobDto` carries `lastRunAt`/`nextRunAt`.
- **Product vs Technical toggle** already exists (`SettingsStore.toolCallTechnical`): Product mode hides tool payloads.
- **Data reality (verified this session):** the WS stream emits `approval.request/responded`, `run.*`, `tool.*`, `message.*` — but there is **no REST list of pending approvals**, and cron-finished/messaging events are **not** on the app's WS. Cron state and messaging pairing ARE available over REST (`/api/cron/*`, `/api/pairing`).

## Recommended Direction

Two complementary, independently shippable pieces on the **same surface** — the existing Mission Control feed. Ship the small one first.

**Piece 1 (ship first) — Cron response inline in Agent Activity.**
A cron item in the feed expands **inline** to show the run's **final assistant message** (the "response"), Product-mode-filtered so tool payloads are hidden. Tap-through to the full transcript is preserved. The response is the last `role = assistant` `ChatMessage` in the cron-produced session, fetched **lazily on expand** (one history call for the tapped item — no upfront N calls). For a run with only tool output and no final assistant text, fall back to the **last tool result summary**. Small, clean, no data gaps.

**Piece 2 — Home = Mission Control promoted, with a "Needs you" strip, set as the launch destination.**
Flip the start destination from `sessions` to the activity home so a cold launch lands on *what's happening* — never a resumed stale chat (this is the real fix for "it remembers the session"). The bottom-nav tab is **relabeled "Home"** (concept: the Situation Room) rather than "Agent Activity". A compact **"Needs you"** strip sits on top of the existing time-grouped feed and **auto-refreshes on resume** via a cheap REST poll of cron + pairing. Notification taps still deep-link to their session unchanged. "New chat" scopes to the **last-used profile**.

**The honest constraint on "Needs you":** it leads with what's **reliable over REST** — **failed / overdue cron** (from `last_status` + `next_run`, already in the feed's data) and **waiting messaging** (`/api/pairing`). *Pending agent approvals* — the thing you'd most want — are WS-only and transient, so they appear **only as a bonus when the foreground service is live** (from its in-memory cache), never as a promised cold-launch count. We do **not** build "Needs you = approvals count"; it would be empty and misleading most of the time.

Rationale: Piece 1 is a painkiller with zero data gaps — it ships this week and makes the cron feature actually useful on a phone. Piece 2 reuses the shipped Mission Control feed rather than duplicating it as a new screen, and is honest about the one weak input (approvals) instead of building the whole thing around it.

## Key Assumptions to Validate
- [ ] **A cron run has a clean "final answer."** Bet: the last `assistant` message in the `source="cron"` session is the response. Test: expand several real cron runs (single-step and multi-step); confirm the last assistant text reads as "the answer." Fallback: for a tool-only run with no final assistant text, show the **last tool result summary**.
- [ ] **Failed/overdue cron is derivable without new endpoints.** Bet: `CronJobDto.lastStatus` + `nextRunAt` (already mapped) are enough to flag "failed" and "overdue." Test: force a failing cron + a missed schedule; confirm both surface in "Needs you."
- [ ] **Landing on the home doesn't fight the notification deep-link.** Bet: start on home, then `deepLinkRoute` navigates on top as today. Test: cold-launch from an approval notification → lands on the session, not the home.
- [ ] **Last-used profile is safe as the new-chat default.** Bet: reopening the last tenant is what you want. Risk: cross-tenant mis-send. Test: dogfood; if it ever feels risky, fall back to a pinned default or a picker (both already considered).

## MVP Scope
**In:**
- **Piece 1:** cron feed items expand to the final assistant message (Product-mode-filtered), lazy-fetched on expand; full-transcript tap preserved.
- **Piece 2:** start destination → activity home (bottom-nav tab relabeled **"Home"**); a "Needs you" strip = failed/overdue cron (+ messaging-pairing waits) that **auto-refreshes on resume**; "New chat" uses last-used profile; notification deep-link unchanged.

**Out (this MVP):**
- A separate net-new "Situation Room" screen (we promote Mission Control instead).
- A guaranteed cold-launch pending-approvals count (WS-only; bonus-when-service-on only).
- Any new gateway endpoint.
- A profile picker / pinned-default-profile launch flow (revisit only if last-used feels unsafe in daily use).

## Not Doing (and Why)
- **Duplicate Situation Room screen** — Mission Control already is the unified feed; a second screen would fork the code and the data. Promote, don't duplicate.
- **"Needs you" built around approvals** — no REST list + transient + service-dependent; it would be blank on cold launch. Cron/messaging lead; approvals are a live-only bonus.
- **New-chat-on-launch (the original ask)** — superseded: landing on the home already removes the stale-session-resume problem, and gives orientation before you start typing. A one-tap "New" is right there.
- **Eager-loading every cron response in the feed** — N history calls on every open. Lazy-on-expand instead.
- **A new Product/Technical control for cron** — reuse the existing toggle; the cron response is just an assistant message with payloads hidden.

## Resolved Decisions
- **Launch tab is relabeled "Home"** (concept: the Situation Room), not kept as "Agent Activity" — it becomes the start destination with the "Needs you" strip on top.
- **"Needs you" auto-refreshes on resume** via a cheap REST poll of cron + pairing (not pull/tab-enter only).
- **Tool-only cron runs** (no final assistant text) show the **last tool result summary** as the "response".
