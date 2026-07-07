# Competitive Review — Hermes for Android vs. the field (2026-07-07)

A review of Hermes for Android against (a) the flagship **consumer AI chat apps** (ChatGPT, Claude, Gemini, Perplexity, Copilot) and (b) **self-hosted / power-user AI clients and agent "remotes"** (Open WebUI, LibreChat, Jan, Msty, Enchanted, Chatbox, AnythingLLM; Claude Code/Cursor/Codex mobile companions; Zapier/Make/n8n). Goal: where Hermes is strong, where it lags table stakes, and where it can lead.

## What Hermes is (and the category reality)

Hermes is a **genuinely native Android client for a self-hosted, multi-tenant AI-agent gateway** — "reach everything, act on a few." That framing matters: the self-hosted AI-client category is overwhelmingly **PWA/desktop-only**. Of the major self-hosted front-ends, only Chatbox and AnythingLLM ship real native Android apps, and neither is an *agent-gateway* or *multi-tenant* product; Open WebUI/LibreChat/Jan/Msty/Big-AGI/Ollama are web/desktop; Enchanted is iOS-only. **A polished native Android app in this space is itself a durable, structural advantage.**

## Scorecard — "table stakes on mobile in 2026"

What users now expect from *any* AI app, mapped to Hermes:

| Capability | Hermes | Note |
|---|---|---|
| Streaming render, stop generation | ✅ | reducer-based streaming, interrupt |
| Markdown + code w/ copy | ✅ | `CodeWithCopy`, horizontal scroll |
| Tool-call + reasoning display | ✅ **(leads)** | `ToolCard` + collapsible `ThinkingCard`; most consumer apps hide this |
| Model switching (+ per-session) | ✅ **(leads)** | session-scope vs default; favorites |
| Slash commands / @-path completion | ✅ **(leads)** | full-screen palette + file completion |
| Approvals / clarify inline | ✅ **(leads)** | approve/deny cards + notification actions |
| Conversation search / archive / rename | ✅ | list-level search; workspace grouping |
| Persistent memory / custom instructions | ◑ | memory *settings* exist (toggles/budgets); no front-and-center "custom instructions" |
| **Regenerate / edit-a-message / branch** | ❌ | absent — regenerate + edit-resend are table stakes; Claude-style branching is a differentiator |
| **Voice dictation + live voice mode** | ❌ | the single biggest 2026 convergence (ChatGPT/Claude/Gemini/Perplexity/Copilot all have it) |
| **Image/camera/file/PDF attach** | ◑ | single gallery image only — no camera, no multi-image, no PDF/doc/vision-beyond-image |
| **Search within an open thread** | ❌ | only list-level + cross-session search |
| **Push on async task completion** | ❌ | notifications are approvals-only, and only while the foreground service lives (no FCM) |
| **Home-screen widget** | ❌ | now table stakes (quick-launch: new chat / voice / camera) |
| **Chat export / share-out** | ❌ | only the diagnostic debug log exports |
| Cross-device history sync | ✅ | gateway-backed |

## Where Hermes already leads (self-hosted / agent lens)

- **True multi-tenant isolation with a visible identity.** Per-tenant accent colour (deterministic hue or custom) + isolated credentials/history/switcher. Open WebUI is explicitly multi-*user*, not multi-*tenant* (its documented "fix" for isolation is *running separate instances*). TypingMind/BoltAI do desktop-only profiles. **Nobody does fast, isolated, colour-coded tenant switching on native mobile** — this is a real moat.
- **A friendly cron **schedule builder** with templates, live next-run, run-now, and per-row actions.** This *already beats* the automation incumbents on mobile: Zapier has **no** Zaps-management app (email-only failure alerts); Make's app is trigger-only (users beg for run history in reviews); n8n ships **no** official app. Hermes clears a bar none of them clear.
- **Activity feed ("Mission Control") + "Needs you" triage** — the "act on a few" pattern, per tenant.
- **Self-hosted, no third-party relay** — the agent-monitoring cottage industry (ntfy relays, tap-to-tmux, Omnara) all route your output through a third party; Hermes doesn't need to.

## The gaps that matter most (table stakes it's missing)

1. **Voice** — dictation is cheap (native `SpeechRecognizer`; the gateway also has `/api/audio/transcribe` as a fallback). Live voice mode is bigger but is where the whole category went.
2. **Regenerate / edit-and-resend** — the most-missed chat primitive; branching (Claude-style version arrows) is the stretch goal.
3. **Attachments** — camera capture, multiple images, and PDF/doc upload; the composer only does one gallery image today.
4. **Notification coverage** — today it's approvals-only, and only while the socket-holding foreground service survives. Users of *every* agent product report the same #1 pain: *"I keep missing when the agent finished."*
5. **Search within a thread**, **chat export**, and a **home-screen widget** — all now expected.

## Where Hermes could *lead* (the whitespace)

Ranked by how unclaimed the space is × fit with the existing architecture:

**A. Mobile-native run monitoring + completion push — the single strongest opportunity.**
"Kick off a long agent task, get pushed when it's done or needs you" is now standard in consumer apps (ChatGPT Tasks/Agent, Gemini, Copilot) and is the #1 repeated pain for *self-hosted* agent users — solved today only by sketchy third-party relays. Hermes is 80% there: it already has the foreground WS + activity feed + approval notifications. Extend notifications beyond approvals to **cron-finished, run-complete, and agent-needs-input**, and lean on the gateway for durable push where possible (the always-connected foreground service is a battery/OS-kill liability — the WS stream reportedly has no cron-finished event, so this may need a gateway assist). Done natively + self-hosted, this beats every competitor because none of the self-hosted ones do it at all.

**B. A touch-native, *tiered* approval UX.**
The coding-agent field has converged on **allow / ask / deny** tiers (Cursor/Cline/Goose), but *no one* — not even Claude's mobile app — has designed the approval interaction *for a small screen*; they all reuse the desktop modal. Evolve Hermes's existing approval cards into swipe-to-approve + **"always allow this tool for this profile"** + per-tool-type defaults. Clean first-mover territory. (Caveat to state plainly: approval gates are a UX safety-net, not a security boundary — prompt-injection bypasses are well documented; pair with gateway-side scoping, which as a self-hosted product you control.)

**C. Diff / "what did the agent change?" review as a first-class mobile surface.**
Proven by GitHub Mobile + Cursor iOS for code, but unclaimed for a *general* self-hosted agent client. The gateway already exposes `/api/git/review/diff`, `/api/git/status`, `/api/fs/*`, `/api/files` — a reviewable "changes" surface (diff view, file browser) is a bigger build but a strong differentiator.

**D. A prompt/snippet library with OS-level quick-invoke** (Android App Actions / share-sheet / a widget of pinned prompts). Confirmed unsolved industry-wide; lower urgency than A–C.

## Suggested roadmap (prioritized)

- **P0 — Notification coverage + run-completion push (A).** The highest-leverage gap; extends what's already built; addresses the category's #1 pain. (May need a small gateway-side event/endpoint — worth confirming against the bridge API before scoping.)
- **P0 — Voice dictation.** Native `SpeechRecognizer` in the composer; cheap, high-frequency, table stakes.
- **P1 — Regenerate + edit-and-resend** (branching later).
- **P1 — Richer attachments** (camera, multi-image, PDF).
- **P1 — Tiered, swipe-native approvals (B)** — evolve the existing approval cards.
- **P2 — Search-within-thread; chat export/share-out; a quick-launch widget.**
- **P2 — Agent "changes" review surface (C)** and a **prompt library + widget (D)** — bigger bets.
- **Hardening backlog** (from the inventory): message-history pagination on long threads; biometric/app-lock (you hold multi-tenant credentials!); real deep-link/App-Link scheme; usage date-range + per-model cost; bulk session actions; offline compose-and-queue.

## One-line takeaway

Hermes is already **ahead of the self-hosted field** on the things that are hard (native, multi-tenant, agent-aware, a real cron builder) and **behind the consumer field** on the things that are now expected (voice, edit/regenerate, richer attachments, completion push, widgets). The winning move is to **close the consumer table-stakes gaps** *and* **plant a flag on run-monitoring push + touch-native approvals + agent-change review** — the whitespace no competitor owns on mobile.

---

*Note: uses generic tenant names (acme/globex/etc.) throughout; real client names deliberately omitted. Comparable-app details are a mid-2026 snapshot — treat exact model/version names as fluid; the durable findings are the feature patterns.*
