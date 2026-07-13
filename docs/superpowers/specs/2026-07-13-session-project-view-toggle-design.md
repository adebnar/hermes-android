# Session ⇆ Project view toggle — design

**Status:** approved design, ready for implementation plan
**Branch:** `feature/project-view-toggle` → PR into `dev`
**Scope:** Browse-only, full desktop parity via the gateway's `projects.*` JSON-RPC. NO gateway changes.

## Goal

Give the Android **Chats** screen a segmented toggle between two modes, mirroring the desktop
client's session/project switch:

- **Sessions** — a flat, most-recent-first list of chats (the "session view" Android lacks today;
  the current screen is *always* grouped by workspace basename).
- **Projects** — the gateway's real, server-authoritative project tree (`projects.tree`), with
  drill-in into a project's sessions (`projects.project_sessions`). Grouping is identical to the
  desktop's: explicit user projects from `projects.db`, auto git-repo projects, discovered repos,
  each with repo → branch-lane structure.

Browse-only: no creating/editing/archiving/deleting projects from the phone in v1 (that stays on
desktop).

## Current state

- `ui/sessions/SessionsScreen.kt` renders a `LazyColumn` grouped **Profile → Workspace** via the pure
  `SessionGrouping.groupSessions(...)`; "workspace" = basename of the session `cwd`
  (`domain/Mappers.kt` drops the full path). There is **no** flat session list and **no** toggle.
- Sessions are loaded cross-profile over REST: `SessionRepository.listAllProfiles()` →
  `HermesRestApi.profileSessions()` → `GET /api/profiles/sessions?limit=500&order=recent`. The
  `SessionDto` already parses `cwd` (and `source`, `profile`, `last_active`, …).
- A live WS gateway connection already exists on this screen: `SessionsViewModel.init` calls
  `chat.connect()`. The generic request/response entry point is
  `HermesGatewayClient.call(method, params): JsonElement` (correlates by numeric JSON-RPC `id`;
  waits on `readyGate`). Existing non-chat precedent: `commands.catalog` in `ChatRepository`.
- `ProfileManager` (`@Singleton`, `active: StateFlow<String?>`, `switchTo`) is the active-profile
  source of truth; `SessionsViewModel` already exposes `activeProfile` / `switchProfile`, and a
  `ProfileSwitcher` chip row sits in the top bar.

## Gateway contract (already exists — consumed read-only)

WS JSON-RPC over `/api/ws` (no REST equivalent exists for either call).

### `projects.tree`
- **Params:** `preview_limit` (default 3), `session_limit` (default 2000). **No `profile` param.**
- **Profile scope:** single-profile — reads the `projects.db` + `state.db` of whatever profile the
  connected gateway process is bound to (`HERMES_HOME`). Not selectable per-call.
- **Returns:** `{ projects: ProjectNode[], active_id: String?, scoped_session_ids: String[] }`.

`ProjectNode` (camelCase where noted):
```
{ id, label, path, color?, icon?, isAuto: Bool, sessionCount: Int,
  lastActive: Double?, repos: Repo[], previewSessions: SessionRow[] }
```
- `id`: explicit projects → `p_<hex>`; auto/discovered → the repo-root **path**.
- `label` (not `name`); `color`/`icon` null for auto/discovered.
- On the tree (overview) call, lane `sessions` arrays are **empty** (counts preserved);
  `previewSessions` holds up to `preview_limit` newest rows.

`Repo`:
```
{ id, label, path, sessionCount: Int, groups: Lane[] }
```
`Lane` (branch lane):
```
{ id, label, path, isMain: Bool, isKanban: Bool, sessions: SessionRow[] }
```

`SessionRow` (the `_project_tree_row` projection):
```
id, parent_session_id, title, preview, started_at, ended_at, last_active,
source, archived, message_count, tool_call_count, input_tokens, output_tokens,
model, cwd, git_branch, git_repo_root
```
(`_lineage_root_id`, `is_active` also present; `is_active` always false — ignored client-side.)

### `projects.project_sessions`
- **Params:** `project_id` (**required**; must equal a node `id` from `projects.tree` — `p_<hex>` or
  the repo-root path), `session_limit` (default 5000). No `profile`, no pagination beyond the limit.
- **Returns:** `{ project: ProjectNode? }` — a single node, **hydrated** (lane `sessions` fully
  populated with `SessionRow`s), `previewSessions` empty, discovered tier excluded.

## Design

### A. View mode (pure, persisted)
```kotlin
enum class ViewMode { SESSIONS, PROJECTS }
```
- Held as `viewMode: StateFlow<ViewMode>` in `SessionsViewModel`, default `SESSIONS`.
- Persisted device-local (same mechanism as `GroupExpansionStore` / a small DataStore pref) so the
  choice survives navigation and restart.

### B. Sessions mode (flat recency)
- Reuse the already-loaded cross-profile list. Render a flat list sorted by `lastActive`
  descending (newest first), keeping the existing **Pinned** section on top and the existing search
  filter. No workspace grouping in this mode.
- The current `groupSessions` Profile→Workspace grouping is **replaced** by these two modes (it is
  not retained as a third mode — YAGNI). Profile is still surfaced per-row via the existing
  supporting-text prefix, and the profile switcher stays visible in Sessions mode.

### C. Projects mode (server tree + drill-in)
- On entering Projects mode (or pull-to-refresh), call `ProjectsRepository.tree()`.
- **Overview:** a list of project cards — accent-tinted; show `label`, `color`/`icon` (fallback to a
  default folder icon/accent when null), `sessionCount`, and a relative `lastActive`. Auto/discovered
  projects render with the neutral default styling (`isAuto = true`, no color).
- **Drill-in:** tapping a project sets `projectScope = id` and calls
  `ProjectsRepository.projectSessions(id)`. Render the project's sessions:
  - If the project has a single lane, show a flat session list.
  - If it has multiple repos/lanes, show lane sub-headers (repo label + branch label) with sessions
    under each (reuse the existing collapsible-header composable).
  - A back row at top (mirrors desktop `ProjectBackRow`) calls `exitProject()` → clears
    `projectScope` back to the overview.
- Tapping a session navigates `chat/{id}` (existing path). Because the tree is single-profile
  (see D), no `prepareOpen`/profile switch is needed before opening from Projects mode — the
  session belongs to the connected gateway's profile.

### D. Profile scoping (honest limitation)
`projects.tree` takes no `profile` param and reflects only the connected gateway's bound profile.
Therefore in **Projects** mode:
- The **profile switcher is hidden**, and a small caption reads `Projects · <profile>` using the
  app's current active profile as the best-available label.
- Switching profiles does not re-scope the project tree. Matching the desktop's per-profile behavior
  fully would require a gateway change (a `profile` param on `projects.tree`) — explicitly out of
  scope for v1 and noted as the gateway follow-up.

Sessions mode is unaffected — it stays cross-profile over REST as today.

### E. Toggle UI
- A Material3 `SingleChoiceSegmentedButtonRow` ("Sessions" | "Projects"), placed in the top-bar
  `Column` directly under the profile switcher (copy the `ui/activity/FeedTabs.kt` pattern; per-tenant
  accent). Selecting a segment updates `viewMode`.

### F. Errors & empty states
- Projects fetch failure (RPC error / not connected): show an inline retry row; do not crash the
  screen or affect Sessions mode. Reuse the screen's existing error affordance.
- Empty tree (no projects): friendly empty state ("No projects yet — projects are created on
  desktop").
- Drill-in returning `project: null`: treat as empty and offer back.

## Components / files

- `data/network/Dtos.kt` — add `ProjectTreeDto`, `ProjectNodeDto`, `RepoDto`, `LaneDto`; reuse a
  session-row DTO for `previewSessions` and lane `sessions` (carrying `cwd`/`git_branch`/`git_repo_root`).
- `data/repository/ProjectsRepository.kt` *(new)* — `suspend fun tree(previewLimit, sessionLimit): ProjectTree`,
  `suspend fun projectSessions(id, sessionLimit): ProjectNode?`; wraps `client.call(...)`, parses DTO → domain.
- `di/AppModule.kt` — `@Provides @Singleton` for `ProjectsRepository(client)`.
- `domain/Models.kt` + `domain/Mappers.kt` — `Project`, `ProjectRepo`, `ProjectLane` domain types;
  extend `Session` with full `cwd`, `gitBranch`, `gitRepoRoot` (set `cwd` from the already-parsed DTO field).
- `ui/sessions/SessionsViewModel.kt` — `ViewMode`, `viewMode` StateFlow (persisted), `projectTree`,
  `projectScope`, `projectsLoading`/`projectsError` state; `setViewMode`, `loadProjectTree`,
  `enterProject`, `exitProject`.
- `ui/sessions/SessionsScreen.kt` — segmented toggle; branch body on `viewMode`; hide `ProfileSwitcher`
  + show caption in Projects mode; wire card taps / drill-in / back / session open.
- `ui/sessions/ProjectList.kt` *(new)* — project card + project-scope (drill-in) session list composables.

## Testing

**Pure/unit (JVM):**
- DTO → domain mapping: full node, missing `color`/`icon` → null, `isAuto` projects, nested
  repos/lanes, `previewSessions` and hydrated lane `sessions`; graceful defaults for absent fields.
- `ProjectsRepository`: mocked `HermesGatewayClient.call` returns fake tree/project JSON;
  `coVerify { call("projects.tree", …) }` and `call("projects.project_sessions", match { project_id })`.
- `SessionsViewModel`: `viewMode` toggle + persistence; `enterProject`/`exitProject` scope reducer;
  Sessions-mode flat-recency sort (newest first, pinned on top).
- Follows existing `ChatRepositoryTest`, `SessionGroupingTest`, `SessionsViewModelTest` patterns.

**On-device (emulator vs mock, per-tenant accent):**
- Toggle flips Sessions ⇆ Projects; choice persists across navigation.
- Projects overview shows cards (explicit colored + auto neutral); drill into a project → its
  sessions (single-lane flat; multi-lane with sub-headers); tap a session → opens chat; back returns
  to overview.
- Profile switcher hidden + `Projects · <profile>` caption shown in Projects mode; visible again in
  Sessions mode.
- Empty state (no projects) and RPC-error retry row render correctly.

## Build / CI

`JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`;
`:app:compileDebugKotlin`, `:app:testDebugUnitTest`, `:app:assembleBeta`. Feature branch → PR into
`dev` (gitleaks gate before push).

## Out of scope (v1 — YAGNI)

- Any project **management** (create/rename/add-folder/set-primary/archive/delete) — desktop only.
- A `profile` param on `projects.tree` (would let Projects mode follow the active profile) — gateway
  follow-up.
- Retaining the old Profile→Workspace basename grouping as a separate third mode.
- Kanban/board affordances implied by lane `isKanban` — surfaced as a plain lane in v1.

## Risks / notes

- **Gateway version skew:** targets the `projects.tree` / `projects.project_sessions` contract in the
  current `~/.hermes/hermes-agent` checkout. If a connected gateway predates these RPCs, the call
  errors → Projects mode shows the error/retry row (Sessions mode unaffected). Verify against the
  running gateway before promotion.
- **Single-profile projects** is the one place this intentionally diverges from the desktop; see D.
