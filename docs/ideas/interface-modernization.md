# Interface Modernization — Ground-Up Design System

## Problem Statement
How might we make Hermes for Android *feel* like a best-in-class AI chat app —
matching the polish of Gemini/Claude/ChatGPT — while exploiting the one thing they
can't: it's a client for **your own** multi-profile, multi-model gateway?

## Context — where we're starting from
The plumbing is sound; the skin is thin. ViewModels are cleanly separated and
tested (70 unit tests), dark mode is wired end-to-end, and session grouping is
pure/testable. But the presentation layer is stock Material3 with **zero** design
investment on top:

- **No design tokens** — `theme/Theme.kt` is `if (dark) darkColorScheme() else lightColorScheme()` with no overrides; no `Color.kt`/`Type.kt`/`Shape.kt`.
- **No icon set** — every control is a Unicode glyph (`☰` menu, `＋` attach, `➤` send, `■` stop, `▸/▾` chevrons, `📌` pin). Inconsistent baselines, no `contentDescription`, no theming.
- **No motion** — no `AnimatedVisibility`, no transitions, no haptics. Streaming is a literal `Text("…")`; collapse/expand is instant.
- **No shared components** — loading = `CircularProgressIndicator` copy-pasted across ~12 screens; error = raw `Text(state.error!!)`; `SessionRow`/headers re-declared per file.
- **Hamburger drawer over 24 screens** — no bottom nav, deep reach, many taps.
- **Hardcoded colors** — e.g. `StatusDot` uses literal `Color(0xFF2E7D32)`, blind to dark mode / dynamic color.

This is the *ideal* base for a ground-up pass: we reskin and reflow on top of
working, tested logic — we don't rewire it.

## Recommended Direction
**Phase 1 (this doc): Foundation + flagship surfaces.** Build a real design-system
layer, then rebuild the two screens users live in — **Chat** and **Sessions** — on
top of it. Everything else adopts the system opportunistically as it's touched.
**Phase 2 (later, separate doc): Mission Control** — unify chats + cron + agents
into one activity deck with profile-spatial navigation. Explicitly out of scope
here; noted so the foundation is built to reach it.

Three signature moves define the Phase 1 feel:

**1. Profile-as-color-space.** Each profile gets a seed-derived accent (stable hash
of the profile name → hue). The whole app tints to the tenant you're in. The
consumer apps have one identity; you visibly *inhabit* five — and it doubles as a
tenant-isolation safety signal (you always see which client you're in). Profile
accent is the **primary** color source; Material You wallpaper theming is an
**optional neutral base** that never overrides the accent.

**2. Hybrid chat surface.** User turns stay as compact right-aligned bubbles;
assistant turns go **full-width, document-style** — speaker-labeled, generous
whitespace, real syntax-highlighted code blocks with a copy affordance. This
visually separates "you" from "the agent" and reads as a transcript, not an SMS
thread. Streaming becomes a proper shimmer/pulse; auto-scroll stops being a
`scrollBy(100_000f)` hack.

**3. Motion + structure.** Bottom navigation (Chats · Activity · You) replaces the
hamburger for primary destinations, admin/settings nest under "You". Shared-element
transition row→chat, animated collapse/expand, haptics on send and long-press,
skeleton loaders instead of bare spinners, and real empty states with CTAs (matters
now that it's public-facing).

## Key Assumptions to Validate
- [ ] **Profiles are few and stable** (profile-color only works at ~4–8 tenants).
      *Test:* confirmed against the live gateway (default/personal/odos/semiotic/dito ≈ 5). Re-check the seed→hue mapping gives visually distinct, accessible accents for those exact names.
- [ ] **3–4 bottom-nav destinations absorb the 24 screens** without a maze.
      *Test:* map all 24 current screens to {Chats, Activity, You, +nested}; if >2 levels deep anywhere, the taxonomy is wrong.
- [ ] **Token/component layer is additive** — reskinning doesn't regress the tested ViewModels.
      *Test:* rebuild one screen (Sessions) first; all existing SessionsViewModel/grouping tests must stay green with zero VM changes.
- [ ] **Profile-accent + WCAG contrast holds in both light and dark** for every seed color.
      *Test:* run each accent through a contrast check against surface/onSurface; clamp lightness if it fails. (The `dataviz` skill's palette validator is a usable reference for the check.)
- [ ] **Full-width assistant turns don't hurt scannability** vs bubbles on a phone.
      *Test:* on-device A/B on a real conversation with code + tool calls; the whole point is legibility, so verify it beats today's 320dp bubble.

## MVP Scope
**IN (Phase 1):**
- **Token layer:** `theme/` gains real `Color`/`Type`/`Shape` + a `ProfileAccent`
  system (seed→hue, contrast-clamped, light/dark) with Material You as optional base.
- **Icon set:** replace all Unicode-glyph controls with Material Symbols +
  `contentDescription`.
- **Shared components:** `ScreenScaffold`, `SkeletonList`, `EmptyState`,
  `ErrorState`, `SessionRow`, `Composer` — de-duplicate the copy-pasted UI.
- **Motion primitives:** animated collapse/expand, streaming shimmer, send/long-press
  haptics, row→chat shared-element transition.
- **Bottom navigation** for primary destinations; drawer retired for those.
- **Chat rebuilt** on the system: hybrid bubbles/document, real code blocks, better
  composer.
- **Sessions rebuilt** on the system: profile-accented headers, swipe actions
  (archive/delete) surfaced from the long-press menu, proper pin affordance (leading
  icon, not a `📌` string prefix).

**OUT (this phase):**
- Mission-control unified timeline (Phase 2).
- Cron/messaging/usage/admin screen redesigns (adopt the system only when touched).
- Any gateway/API changes — this is purely client-side presentation.

## Not Doing (and Why)
- **Full Mission Control now** — biggest concept bet; deferred to Phase 2 so the
  foundation ships first and de-risks it. Building tokens/nav to *reach* it, not
  building it.
- **Redesigning all 24 screens** — only Chat + Sessions are flagship. The rest
  inherit the token/component layer for free and get bespoke attention later;
  reskinning everything at once maximizes regression surface for minimal visible win.
- **Full Material You as primary color** — weakens the per-profile identity signal,
  which is the whole "beat them" thesis. Kept as an opt-in neutral base only.
- **Pure document chat (no bubbles)** — loses the fast visual "me vs agent" read on a
  small screen; hybrid keeps it.
- **Custom nav transitions everywhere** — one shared-element (row→chat) earns its
  keep; over-animating navigation reads as slow.

## Resolved Decisions (2026-07-02)
- **Bottom-nav taxonomy:** 3 tabs — **Chats** (sessions + chat) · **Agent Activity**
  (cron + messaging + usage + agents; seeds Phase 2 Mission Control) · **You** (profiles
  + settings + admin + models). *Tab label "Agent Activity" is a string resource — may
  be renamed to "Automated Tasks" after seeing it in context.*
- **Profile-accent scope:** **chrome only** — app bar, bottom nav, group headers, FAB
  tint per profile; chat body stays neutral for legibility.
- **Session-row swipe:** trailing **swipe = Archive**, **full-swipe = Delete** (with
  confirm); pin/rename remain in the long-press menu.
- **Icon style:** **Material Symbols Rounded** (soft/modern, Gemini-adjacent) across the
  whole app, replacing the Unicode glyphs.

## Open Questions (deferred to build phase)
- Bottom-nav third-tab label wording ("Agent Activity" vs "Automated Tasks") — confirm
  on-device once the nav is live.
- Whether the profile accent, though chrome-only, should also tint the **FAB** vs stay a
  fixed brand color — decide when building the component.

## Deferred features (post-Phase-1)
- ✅ **User-settable per-profile accent** *(shipped post-0.1.19)* — a curated-swatch picker
  in the **You** tab writes a per-profile colour to `ProfileAccentStore` (DataStore);
  `LocalProfileAccentOverrides` threads it through `HermesTheme` and every accent call site,
  with "Auto" to revert to the hashed hue.
- ✅ **Contrast guarantee** *(resolved by design)* — turns out no colour needs rejecting: the
  adaptive `onColorFor()` picks black/white by max contrast, and the worst case for any opaque
  colour is ~4.58 (above AA-large 3.0). So the guarantee is simply "every element on a tinted
  surface uses the paired on-colour" (the 0.1.19 "Archived" regression, fixed via
  `AccentChrome.onBar`). No picker guard required — even a free wheel would be safe.

## Status
Idea refined 2026-07-02. Direction: **A now / B later**, hybrid chat, chrome-only
profile-accent (primary) with optional Material You base. All four open questions
resolved (above). Build spine: tokens → components → nav → Sessions rebuild → Chat
rebuild → motion polish, on `feature/design-system` off `develop`.
