# Improvement Roadmap — phased (2026-07-07)

Turns the competitive review (`competitive-review-2026-07-07.md`) into a phased plan. Every item here is a **wave** — it runs the established cycle: brainstorm → spec → plan → subagent-driven development → beta → (approval) → production. Phases are shippable milestones; waves inside a phase can ship independently.

**Baseline:** production & beta at 0.1.39. Standing constraints carry into every wave: no AI attribution; gitleaks before every push; tenant isolation (fake acme/globex names); `main` only via approved PR; **no new gateway endpoints unless explicitly agreed** (flagged per-item below).

## Legend
- **Effort:** S (1 wave, ~a day) · M (1 wave, meatier) · L (multi-wave)
- **GW:** `client-only` · `gateway-assist?` (needs a bridge-API check / possible server change — a decision point)

---

## Phase 1 — Chat parity: the daily-use primitives
*Goal: the chat screen matches what users expect from any 2026 AI app. All client-only, highest-frequency value.*

1. **Voice dictation** — a mic in the composer using native `SpeechRecognizer`; `/api/audio/transcribe` as a server-side fallback if native is unavailable. *(S · client-only)* **[P0]**
2. **Regenerate + edit-and-resend** — regenerate the last assistant turn; edit a prior user message and resend. (Branching/version-arrows is a Phase-6 stretch.) *(M · client-only — reuses existing send/interrupt + history)* **[P1]**
3. **Richer attachments** — camera capture, multiple images, and PDF/doc upload in the composer (today: one gallery image). *(M · gateway-assist? — confirm the bridge accepts multi-image / non-image payloads; falls back to images-only if not)* **[P1]**
4. **Search within an open thread** — in-thread find/highlight. *(S · client-only — the full history is already loaded)* **[P2]**

**Milestone:** Hermes' chat is at consumer table-stakes for input + editing.

---

## Phase 2 — Awareness: run-monitoring & completion push  *(the flagship differentiator)*
*Goal: "kick off a task, get pushed when it's done or needs you" — the #1 unmet pain for self-hosted agent users, and Hermes is ~80% there (foreground WS + activity feed + approval notifications).*

0. **Bridge-API spike (prerequisite)** — audit the WS/REST surface: `run.*`, `tool.*`, `message.*` exist; the review found **no cron-finished event and no pending-approvals REST list**. Decide client-only scope vs. a small gateway assist. *(S · gateway-assist? — this spike sets the scope)* **[decision point]**
1. **Notification coverage beyond approvals** — surface **run-complete**, **agent-needs-input**, and (if the spike allows) **cron-finished / cron-failed** as notifications, using the existing foreground service + `NotificationMapper`. *(M–L · depends on the spike)* **[P0]**
2. **Foreground-service reliability pass** — address the "notifications die when the service is killed" gap (Android 15 FGS caps; consider a gateway-push path if the spike surfaces one). *(M · gateway-assist?)* **[P1]**

**Milestone:** you can leave the app and trust it to tell you when an agent finished or got stuck — no third-party relay, fully self-hosted.

---

## Phase 3 — Touch-native, tiered approvals  *(whitespace lead)*
*Goal: the approval interaction no competitor has designed for a small screen. Builds on Phase 2 (approvals arrive as pushes).*

1. **Tiered approvals: allow / ask / deny** — replace binary approve/deny with per-tool-type defaults; **"always allow this tool for this profile"** (stored per-tenant); swipe-to-approve cards. *(M · client-only for the UX + local policy; gateway-assist? only if defaults must persist server-side)* **[P1]**
2. **Approval context** — richer inline context on the card (what the tool will do / touch) so decisions don't require opening the thread. *(S · client-only)* **[P2]**

> Positioning note to keep honest in the UI/spec: approval gates are a **UX safety-net, not a security boundary** (prompt-injection bypasses are documented industry-wide); real scoping lives at the self-hosted gateway you control.

**Milestone:** approving/denying agent actions on a phone feels native, fast, and per-tenant safe.

---

## Phase 4 — Reach: widget, deep links, prompt library
*Goal: get Hermes off the app-drawer and onto the home screen + OS quick-actions. Deep links are the shared foundation.*

1. **Public deep-link / App-Link scheme** (`hermes://…` + optional `https://` App Links) — routes to chat/cron/etc.; foundation for widget + share + App Actions. *(S–M · client-only)* **[P2]**
2. **Home-screen widget** (Glance) — quick-launch (new chat / voice / camera) + a "Needs you" glance. *(M · client-only)* **[P2]**
3. **Prompt / snippet library** — saved prompts, reachable in-composer and via **App Actions / share-sheet** (confirmed unsolved industry-wide). *(M · client-only; local store or a config key)* **[P2/D]**

**Milestone:** one-tap into Hermes from the home screen and OS quick-actions; reusable prompts without typing.

---

## Phase 5 — Review & artifacts  *(bigger bets)*
*Goal: "what did the agent change?" and get content out of the app.*

1. **Agent-changes review** — a diff view + file browser over the existing `/api/git/review/diff`, `/api/git/status`, `/api/fs/*`, `/api/files` endpoints. Proven for code by GitHub Mobile/Cursor iOS; unclaimed for a general agent client. *(L · gateway-assist? — verify those endpoints against the bridge; likely client-only)* **[P2/C]**
2. **Chat export / share-out** — share a conversation or a single response as Markdown/text (today only the debug log exports). *(S · client-only)* **[P2]**

**Milestone:** review agent output and share it — without a desktop.

---

## Phase 6 — Chat depth (stretch)
1. **Conversation branching** — Claude-style edit-and-fork with version arrows (extends Phase 1 edit-resend). *(M–L · gateway-assist? — depends on whether the gateway models branches)* **[stretch]**
2. **Memory / custom-instructions front-and-center** — surface persistent memory + a "custom instructions" editor prominently (settings exist today but are buried). *(S–M · client-only — existing memory endpoints)* **[P1-ish]**

---

## Continuous track — Hardening & security *(interleave between phases)*
Not a blocking phase; pull items in when adjacent work touches them, and pull **biometric app-lock forward** given it guards multi-tenant credentials.

- **Biometric / PIN app-lock** on launch — you hold multiple tenants' credentials/tokens. *(S · client-only)* **[pull early]**
- **Message-history pagination** — long threads fetch the whole history in one shot today. *(M · gateway-assist? — needs a paged history param)*
- **Offline compose-and-queue** — draft/send while disconnected, flush on reconnect. *(M · client-only)*
- **Usage: date-range picker + per-model cost breakdown.** *(S · client-only)*
- **Bulk session actions** — multi-select archive/delete. *(S · client-only)*
- **API-key reveal/copy** in the Env screen (write-only today). *(S · client-only)*

---

## Sequencing rationale
- **Phase 1 first** — pure client-side, daily value, no dependencies; ships momentum while the Phase-2 API question is resolved.
- **Phase 2 next** — highest strategic value (the differentiator) but gated on a bridge-API spike; the spike runs early/in parallel so scope is known before we commit.
- **Phase 3** rides on Phase 2 (approvals-as-push).
- **Phases 4–5** are reach/review — valuable, lower urgency, larger surface.
- **Phase 6** is stretch/depth.
- **Hardening** interleaves; **biometric app-lock** should jump ahead of most feature work on security grounds.

## Open decisions (need your call as they come up)
1. **Gateway changes** — the standing rule is "no new gateway endpoints." Phase 2 (cron-finished push), Phase 5 (if any endpoint is missing), and history pagination may want a small gateway assist. Do we (a) stay strictly client-only and scope around gaps, or (b) allow targeted bridge additions for the flagship push feature? *(Recommend: run the Phase-2 spike, then decide with real information.)*
2. **Voice mode (live two-way)** — deferred beyond dictation; revisit after Phase 1 if wanted (much larger).
3. **Phase granularity** — happy to split any Phase-1 item into its own wave or bundle two; each is independently shippable.

## Execution
When you say go, we start **Phase 1 · Wave 1 (voice dictation)** through the normal cycle. Each wave ships to beta on its own, and you approve production promotions as before.
