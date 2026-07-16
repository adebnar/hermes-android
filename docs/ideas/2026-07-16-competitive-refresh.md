# Competitive Refresh — what's NEXT for Hermes for Android (2026-07-16)

A fresh survey of 2025–2026 mobile LLM apps (Android **and** iOS), read against everything
Hermes has already shipped or spec'd. The prior review (`competitive-review-2026-07-07.md`) and
its phased plan (`improvement-roadmap-2026-07-07.md`) closed the *consumer table-stakes* gaps —
voice dictation, regenerate/edit-resend, richer attachments, in-thread search, completion push,
tiered approvals (allow/ask/deny + per-profile "always allow" + once/session/always scopes), the
cron schedule builder, the activity feed, the session↔project toggle. **None of that is
re-proposed here.** This doc is only the layer *beyond* that plan.

Standing constraints assumed throughout: no new gateway endpoints unless clearly worth it (flagged
per item), strict tenant isolation (generic acme/globex names), client-only preferred.

Field surveyed: ChatGPT, Claude, Gemini, Copilot, Perplexity, Grok, Le Chat, Meta AI, Poe, DeepSeek
(consumer) · Cursor iOS, Codex mobile, Enchanted, Reins, Chatbox, LMSA, Msty, Open WebUI, LibreChat,
Pal Chat (power-user / self-hosted / agent clients).

---

## 1. Executive summary — highest-leverage new directions

1. **Live progress "ongoing run" surface (Android 16 Live Updates / `ProgressStyle`).** Cursor iOS
   streams agent progress to the lock screen via Live Activities; Android 16 now has the equivalent.
   Hermes pushes on *completion* but shows nothing *while a run is in flight* — a persistent,
   glanceable "acme agent running · step 3/5" chip is the natural next beat after completion push.
2. **Self-hosted onboarding that isn't a manual URL+token paste.** Setup today is hand-typed
   `http://100.x.x.x:9119` + token. LMSA auto-discovers LM Studio/Ollama on the LAN; Cursor/Codex
   pair by **QR scan** from the desktop. A LAN/Tailscale discovery + QR-pair flow is a self-hosted-only
   win no consumer app needs.
3. **Per-session generation controls + system-prompt override.** Reins and Chatbox let power users
   set temperature / context size / max-tokens / a per-chat system prompt. Hermes has a per-session
   *model* picker but no knobs and no per-session instruction override — a cheap power-user gap.
4. **Agent/persona presets on the phone.** Grok "Your Agents" (up to 4 custom), Gemini "Gems",
   LMSA "personas" — one-tap reusable bundles of {model + system prompt + tool policy}. For Hermes
   this is *per-tenant* and ties directly into its approval-policy and model machinery.
5. **A cross-tenant "Approvals & Needs-you inbox."** Hermes' multi-tenant isolation is its moat, but
   today triage lives inside each profile. A single aggregated queue of pending approvals + blocked
   runs *across all tenants* (color-coded) is something no consumer app can build — they're single-account.
6. **Proactive backend-health awareness.** Every consumer app assumes the backend is up; a self-hosted
   client is the one product that must warn *"acme gateway unreachable for 6 min."* Hermes has a
   diagnostics screen — promote it to a proactive, per-tenant reachability signal.
7. **Act-on-result quick actions from the notification/feed.** Cursor lets you review and **merge the
   PR** straight from the phone. Hermes' analog: re-run / send-follow-up / approve / open — directly
   from a completion notification or the activity card, without opening the thread.
8. **Record-to-task capture.** ChatGPT "Record Mode" turns a meeting/voice note into structured tasks.
   Voice *dictation* is done; feeding a longer recording to an agent to produce a run is the agentic
   extension.

---

## 2. Idea inventory (grouped, ranked)

Effort: **S** ≈ a day · **M** ≈ meatier wave · **L** ≈ multi-wave.
GW: **client-only** vs **gateway-assist?** (needs a bridge-API check / possible server change).

### A. Agent-run awareness (the strongest cluster — extends the flagship)

**A1. Live in-flight run progress (Android 16 Live Updates / `ProgressStyle`).**
- *Pattern / who:* Cursor iOS uses Apple **Live Activities** to stream agent status ("running… /
  needs input / ready for review") to the lock screen + Dynamic Island. Android 16's
  `Notification.ProgressStyle` + promoted "Live Updates" give a status-bar chip, notification-shade
  card, and Always-On-Display presence for exactly this start→end journey shape.
- *Fit:* Very high. Hermes already runs a foreground WS service and already knows run state; it just
  renders nothing *during* a run. A long agent task is precisely the "user-initiated start-to-end
  journey" Live Updates were designed for. This is distinct from the shipped *completion*
  notification — it's the minutes in between.
- *Effort:* **M** · *GW:* **client-only** if the WS already emits step/tool progress events (the
  Phase-2 spike likely already answered this); **gateway-assist?** only if no progress event exists.
- *Priority:* **P0** — highest-leverage genuinely-new idea; small build on top of existing infra, and
  it's the single most visible thing modern agent-remotes do that Hermes doesn't.

**A2. Cross-tenant "Approvals & Needs-you inbox."**
- *Pattern / who:* No consumer app does this (they're single-account); it's a pure exploitation of
  Hermes' multi-tenant design. Closest analog is Codex/Cursor's per-task approval queue, but
  single-workspace.
- *Fit:* Very high and differentiating. One aggregated, color-coded queue: every pending approval and
  every blocked/needs-input run across acme + globex + …, sorted by wait time, each row tappable to
  the source thread.
- *Effort:* **M** · *GW:* **client-only** *if* pending approvals/blocked runs are derivable from the
  per-profile state the app already polls; **gateway-assist?** if a pending-approvals list must be
  fetched (the prior spike flagged there's no pending-approvals REST list — reuse that finding).
- *Priority:* **P1** — differentiating, but partly gated on the same data question Phase 2 raised.

**A3. Act-on-result quick actions from notification / activity card.**
- *Pattern / who:* Cursor iOS — "leave follow-up instructions, or **merge the PR** directly from the
  app" the moment an agent finishes. ChatGPT/Gemini scheduled-task cards offer re-run.
- *Fit:* High. On a completion notification or an activity-feed row, expose **Re-run**, **Send
  follow-up** (quick-reply text), **Approve/Deny** (if blocked), **Open**. Turns awareness into action
  without a context switch. Overlaps cron-followups (shipped) but generalizes it to *any* run.
- *Effort:* **S–M** · *GW:* **client-only** (reuses send + approval wire).
- *Priority:* **P1** — closes the loop the completion-push work opened.

**A4. Proactive backend-health signal.**
- *Pattern / who:* Uniquely self-hosted — no consumer app worries the backend is down. LMSA's
  connection-centric UX is the nearest cousin.
- *Fit:* High and defensible. A quiet per-tenant reachability indicator + an optional "gateway
  unreachable for N min" notification. The diagnostics package already has the plumbing; this promotes
  it from a screen you visit to a signal that finds you.
- *Effort:* **S** · *GW:* **client-only** (health ping against an existing endpoint).
- *Priority:* **P1** — cheap, and it targets the failure mode only a self-hosted user has.

### B. Chat ergonomics & power-user controls

**B1. Per-session generation controls + system-prompt override.**
- *Pattern / who:* **Reins** ("adjust temperature, seed, context size, max tokens for each
  conversation, per-chat system prompt"), **Chatbox** (Top-P, two-decimal temperature, per-conversation
  system prompt), Open WebUI (parameter tuning).
- *Fit:* High for Hermes' power-user base. Model-per-session exists; add an optional advanced sheet:
  temperature / top-p / max-tokens / context window / a session system-prompt override.
- *Effort:* **S** (UI) · *GW:* **gateway-assist?** — verify the send/run payload accepts these fields;
  gracefully hide any the gateway ignores.
- *Priority:* **P1** — small, and squarely serves the audience that self-hosts.

**B2. Split / compare two models on one prompt.**
- *Pattern / who:* **Msty** "split chats"; Poe multi-bot; Open WebUI multi-model.
- *Fit:* Medium. Fan one prompt to two models/sessions side-by-side. Screen-real-estate-hard on a
  phone; more a novelty than daily value.
- *Effort:* **M** · *GW:* **client-only** (two parallel sessions).
- *Priority:* **P2** — nice, low urgency.

**B3. Natural-language cron ("every Friday, summarize acme's open runs").**
- *Pattern / who:* Gemini **Scheduled Actions** — "just tell it what and when." Hermes has a
  structured builder; NL entry is a faster on-ramp that compiles to the same schedule.
- *Fit:* Medium. A text box that parses to the existing builder's fields (keep the builder as the
  editable result).
- *Effort:* **S–M** · *GW:* **client-only** (parse locally or let an agent turn text→cron).
- *Priority:* **P2** — enhancement to a shipped feature.

### C. Multi-tenant power-tools (differentiating)

**C1. Per-tenant agent/persona presets.**
- *Pattern / who:* **Grok "Your Agents"** (up to 4 named custom agents w/ instructions), **Gemini
  Gems**, **LMSA personas**. All single-account; Hermes' version is *per-tenant*.
- *Fit:* Very high. A saved preset = {default model + system-prompt override + approval-tier defaults}
  scoped to a profile. Reuses B1 (params) + the tiered-approval policy store + the planned prompt
  library — bundles them into a one-tap "start a run as <persona>."
- *Effort:* **M** · *GW:* **client-only** (local per-profile store).
- *Priority:* **P1** — high differentiation, reuses several existing pieces.

**C2. Per-tenant approval-policy dashboard.**
- *Pattern / who:* Extends the shipped per-profile "always allow" list; no competitor has a
  multi-tenant view of it. Codex offers per-task vs cross-task MCP approval choices (single workspace).
- *Fit:* High. One screen to review/revoke each tenant's standing allowlist ("globex auto-allows
  `web.fetch`") — the audit surface a multi-tenant operator wants.
- *Effort:* **S–M** · *GW:* **client-only** if the allowlist is client-stored; **gateway-assist?**
  where `always` writes the gateway `config.yaml` (the tiered-approvals spec notes it does) — reads may
  need a config fetch.
- *Priority:* **P2** — governance polish on top of tiered approvals.

### D. Onboarding & connection (self-hosted-native)

**D1. LAN/Tailscale auto-discovery + QR-pair setup.**
- *Pattern / who:* **LMSA** scans the network to auto-find LM Studio/Ollama; **Cursor/Codex** pair the
  phone to a host by **scanning a QR** shown on desktop.
- *Fit:* Very high, self-hosted-only. Replace/augment the hand-typed URL+token with: (a) NSD/mDNS
  discovery of a gateway on the LAN, (b) a QR shown by the dashboard that encodes URL+token, scanned in
  setup. Kills the most error-prone step in the whole app.
- *Effort:* **M** · *GW:* **client-only** for mDNS + QR *scan*; **gateway-assist?** (small) if the
  dashboard must render the pairing QR — worth it.
- *Priority:* **P1** — first impression of a self-hosted client, and directly on-niche.

### E. Model / context management

**E1. Reachability-aware model picker.**
- *Pattern / who:* Reins/LMSA surface which endpoints/models are actually reachable; Hermes lists
  models but doesn't flag a provider that's down.
- *Fit:* Medium. Show per-provider health in the model sheet (gateway already knows provider status).
- *Effort:* **S** · *GW:* **gateway-assist?** (needs a provider-status read, may already exist).
- *Priority:* **P2**.

### F. On-device / offline

**F1. Offline compose-and-queue** — already in the hardening backlog; reaffirmed by LMSA's
local-first framing. Keep as-is (**M**, client-only, **P2**). *No new proposal.*

*Deliberately NOT pursuing on-device inference* (LLMFarm/Private LLM/LM Studio local models) — see
Anti-recommendations.

### G. Personalization / memory

**G1. Memory management UI (per tenant).**
- *Pattern / who:* **ChatGPT** memory summary page — view, delete individual memories, "delete and
  turn off." **Claude** persistent memory across chats.
- *Fit:* Medium-high, but the prior roadmap already has "memory / custom-instructions front-and-center"
  (Phase 6). The *new* nuance worth folding in: a **per-tenant** memory viewer/editor with
  individual-entry delete — multi-tenant makes memory-bleed between orgs a real concern, so a
  tenant-scoped memory audit is the differentiated framing.
- *Effort:* **S–M** · *GW:* **gateway-assist?** (existing memory endpoints).
- *Priority:* **P1** — reframes a planned item around the isolation moat.

### H. Sharing / export

**H1. Chat export / share-out** — already planned (Phase 5). *No new proposal beyond it.* One small
add: **"share a run summary"** (result + which tools ran) rather than raw transcript, matching how
Cursor shares a run's artifacts. (**S**, client-only, **P2**.)

### I. Home-screen / OS integration

**I1. Home-screen widget + deep links** — already planned (Phase 4); Claude Android shipping widgets
confirms it's table stakes. *No new proposal.*

**I2. Record-to-task capture.**
- *Pattern / who:* **ChatGPT "Record Mode"** — capture a meeting/voice note, transcribe, and convert
  into structured tasks/plans.
- *Fit:* Medium, agentic. Beyond the shipped push-to-talk dictation: record a longer clip, hand the
  transcript to an agent, get a run/task back. Fits "act on a few."
- *Effort:* **M** · *GW:* **client-only** if transcription uses native or the existing
  `/api/audio/transcribe`; the agent run is already supported.
- *Priority:* **P2** — compelling but larger; validate demand first.

### J. Accessibility

**J1. TTS read-aloud of responses.**
- *Pattern / who:* **LMSA**, Open WebUI, Enchanted all offer TTS output. Hermes has voice *in*
  (dictation) but not voice *out*.
- *Fit:* Medium. Native Android `TextToSpeech` to read an assistant turn — genuinely useful for long
  agent reports while away from the screen, and an accessibility win.
- *Effort:* **S** · *GW:* **client-only** (native TTS).
- *Priority:* **P2** — cheap, rounds out the voice story without the cost of live voice mode.

---

## 3. Explicitly Hermes-differentiating bets

These exploit self-hosted / multi-tenant / agentic architecture — a generic consumer app **cannot**
copy them without becoming Hermes.

1. **Cross-tenant Approvals & Needs-you inbox (A2)** — a single triage queue spanning every isolated
   org. Impossible for single-account apps by construction. This is the multi-tenant moat made into a
   daily-use surface.
2. **Live in-flight run progress across tenants (A1)** — a color-coded "who's running what right now"
   Live Update. Consumer apps show one account's one task; Hermes can show acme + globex agents at once
   on the AOD.
3. **Per-tenant agent/persona presets (C1) + approval-policy dashboard (C2)** — persona and governance
   bundles that are *scoped to a tenant*. Grok/Gemini personas are global to one user; Hermes' are the
   isolation boundary.
4. **Backend-health awareness (A4)** — the one thing only a self-hosted client owes its user. No
   consumer app will ever build "your gateway is down," because they *are* the gateway.
5. **Self-hosted-native onboarding: LAN discovery + QR pair (D1)** — pairing to *your own* box on your
   own network/Tailscale. A consumer SaaS has nothing to discover.
6. **Per-tenant memory audit (G1)** — memory-bleed between orgs is a multi-tenant-only risk; a
   tenant-scoped, individually-deletable memory viewer is a trust feature consumer apps don't need.

The through-line: consumer apps optimize *one* account's *one* conversation; Hermes' unclaimed
territory is **operating many isolated agent fleets from a phone** — inboxes, live status, personas,
policy, and health, all per-tenant.

---

## 4. Anti-recommendations (do NOT chase)

1. **Live two-way voice/camera mode (Gemini Live, ChatGPT Voice, Grok Voice).** Real-time bidirectional
   audio/video needs streaming multimodal infra end-to-end (gateway + model). Enormous build, and a
   self-hosted *agent-ops* tool isn't where "talk to it like a person" pays off. Dictation + TTS
   (shipped + J1) cover the realistic need.
2. **On-device / local inference (LLMFarm, Private LLM, LM Studio mobile).** Hermes' entire premise is a
   *remote* gateway that holds the models, credentials, and tools. Bundling a local model contradicts
   the architecture and fragments where "the agent" lives.
3. **Image/video generation & "Imagine"/Sora-style creative surfaces (Grok, Gemini, ChatGPT).** Not the
   niche; it's an operator's console, not a content studio.
4. **A full mobile code editor / heavy multi-file diff authoring (Cursor's own reviews call >80-line
   diffs "problematic" on a phone).** Keep any agent-changes review **read-mostly** (the planned diff
   viewer is right-sized); don't try to become an IDE.
5. **Connector / knowledge-file management UI (ChatGPT Projects' 40-file uploads, Claude Drive
   connector).** Data sources, RAG, and connectors belong to the gateway/MCP layer Hermes already
   talks to. Rebuilding a file/connector manager in the app duplicates server responsibility and blows
   the "no new gateway endpoints" budget.
6. **CarPlay / Android Auto (ChatGPT CarPlay).** An approval-gated, human-in-the-loop agent tool is a
   poor fit for a driving context; low ROI, safety-awkward.
7. **Social / discovery feeds, public prompt marketplaces (Poe-style).** Off-mission for a private,
   self-hosted operator tool.

---

## 5. Quick wins next (pick up immediately)

1. **Per-session generation controls + system-prompt override (B1)** — S, client-only (pending a
   one-line payload check). Direct hit on the self-hosting power-user.
2. **Backend-health signal (A4)** — S, client-only. Cheap, and only Hermes can offer it.
3. **Act-on-result quick actions on completion notifications (A3)** — S–M, client-only. Completes the
   loop the shipped completion-push feature opened.
4. **TTS read-aloud (J1)** — S, client-only native `TextToSpeech`. Rounds out voice cheaply; accessibility win.
5. **LAN/Tailscale auto-discovery + QR-pair setup (D1)** — start with mDNS discovery (client-only) now;
   add the dashboard QR (small gateway assist) as a fast-follow. Fixes the most error-prone step in the app.

---

*Snapshot note: competitor model/version names are a mid-2026 moment and will drift; the durable
findings are the feature **patterns**, not the version numbers. Tenant names are generic throughout.*
