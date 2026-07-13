# Session ⇆ Project view toggle — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a segmented Sessions ⇆ Projects toggle to the Android Chats screen, where Projects renders the gateway's real server-authoritative project tree (`projects.tree` / `projects.project_sessions`) with drill-in, browse-only.

**Architecture:** Sessions mode = a flat, most-recent-first list built from the existing cross-profile REST session data. Projects mode = two new WS JSON-RPC calls over the already-live gateway socket, parsed into domain types and rendered as project cards → drill-in session list. No gateway changes; consume existing RPCs read-only.

**Tech Stack:** Kotlin, Jetpack Compose, Material3 (`SingleChoiceSegmentedButtonRow`), Hilt, MVVM + StateFlow, kotlinx.serialization, DataStore Preferences, JUnit + MockK + kotlinx-coroutines-test.

## Global Constraints

- No AI/assistant attribution in commits, files, or PRs (no "Co-Authored-By", "Generated with", "Claude", 🤖).
- Branch `feature/project-view-toggle` off `dev`; PR into `dev`. Never push to `main`. Run `gitleaks git --no-banner --redact` before every push.
- NO gateway changes. Consume only the existing `projects.tree` and `projects.project_sessions` RPCs.
- Browse-only: no project create/edit/archive/delete on mobile.
- Material3; per-tenant accent via `LocalProfileAccent.current` (`.accent` / `.onAccent`).
- Projects mode is single-profile (the connected gateway's bound profile); `projects.tree` takes no profile param. Hide the profile switcher and show a `Projects · <profile>` caption in Projects mode.
- Build with `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`. Gates: `:app:compileDebugKotlin`, `:app:testDebugUnitTest`, `:app:assembleBeta`.
- `icon` (gateway codicon name) is intentionally NOT consumed in v1 (YAGNI) — it is ignored by the DTO (unknown key) and absent from the domain model. Projects render with a folder glyph tinted by `color`/accent.

---

## File Structure

- `app/src/main/java/com/hermes/client/data/network/Dtos.kt` *(modify)* — add project-tree DTOs; add `git_branch`/`git_repo_root` to the reused `SessionDto`.
- `app/src/main/java/com/hermes/client/domain/Models.kt` *(modify)* — add `ProjectTree`, `Project`, `ProjectRepo`, `ProjectLane`; extend `Session` with `cwd`, `gitBranch`, `gitRepoRoot`.
- `app/src/main/java/com/hermes/client/domain/Mappers.kt` *(modify)* — project DTO→domain mappers; set the new `Session` fields.
- `app/src/main/java/com/hermes/client/data/repository/ProjectsRepository.kt` *(create)* — `tree()`, `projectSessions(id)`.
- `app/src/main/java/com/hermes/client/data/repository/ViewModeStore.kt` *(create)* — persisted view-mode preference.
- `app/src/main/java/com/hermes/client/di/AppModule.kt` *(modify)* — provide `ProjectsRepository`, `ViewModeStore`.
- `app/src/main/java/com/hermes/client/ui/sessions/SessionsViewModel.kt` *(modify)* — `ViewMode`, `viewMode`, projects state + actions.
- `app/src/main/java/com/hermes/client/ui/sessions/SessionGrouping.kt` *(modify)* — pure `sessionsByRecency` helper.
- `app/src/main/java/com/hermes/client/ui/sessions/ProjectList.kt` *(create)* — project card + drill-in composables.
- `app/src/main/java/com/hermes/client/ui/sessions/SessionsScreen.kt` *(modify)* — toggle, caption, branch body.
- Tests: `ProjectMappersTest.kt`, `ProjectsRepositoryTest.kt` *(create)*; `SessionsViewModelTest.kt`, `SessionGroupingTest.kt` *(modify)*.

---

## Task 1: Project DTOs, domain types, and mappers (pure)

**Files:**
- Modify: `app/src/main/java/com/hermes/client/data/network/Dtos.kt`
- Modify: `app/src/main/java/com/hermes/client/domain/Models.kt`
- Modify: `app/src/main/java/com/hermes/client/domain/Mappers.kt`
- Test: `app/src/test/java/com/hermes/client/domain/ProjectMappersTest.kt` *(create)*

**Interfaces:**
- Produces:
  - DTOs (in `com.hermes.client.data.network`): `ProjectTreeDto`, `ProjectNodeDto`, `RepoDto`, `LaneDto`, `ProjectSessionsResultDto`; `SessionDto` gains `gitBranch`, `gitRepoRoot`.
  - Domain (in `com.hermes.client.domain`): `data class ProjectTree(val projects: List<Project>, val activeId: String?)`, `Project`, `ProjectRepo`, `ProjectLane`; `Session` gains `cwd: String?`, `gitBranch: String?`, `gitRepoRoot: String?`.
  - Mappers: `ProjectTreeDto.toDomain(): ProjectTree`, `ProjectNodeDto.toDomain(): Project`, `RepoDto.toDomain(): ProjectRepo`, `LaneDto.toDomain(): ProjectLane`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/hermes/client/domain/ProjectMappersTest.kt`:

```kotlin
package com.hermes.client.domain

import com.hermes.client.data.network.ProjectSessionsResultDto
import com.hermes.client.data.network.ProjectTreeDto
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectMappersTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val treeJson = """
    {
      "projects": [
        {
          "id": "p_ab12", "label": "Roam and Trail", "path": "/u/andrew/roam-and-trail",
          "color": "#4F8A10", "icon": "root-folder", "isAuto": false,
          "sessionCount": 3, "lastActive": 1752000000.0,
          "repos": [
            { "id": "/u/andrew/roam-and-trail", "label": "roam-and-trail", "path": "/u/andrew/roam-and-trail",
              "sessionCount": 3,
              "groups": [
                { "id": "main", "label": "main", "path": "/u/andrew/roam-and-trail",
                  "isMain": true, "isKanban": false, "sessions": [] }
              ] }
          ],
          "previewSessions": [
            { "id": "s1", "title": "Affiliate setup", "model": "gpt", "last_active": 1752000000.0,
              "message_count": 4, "cwd": "/u/andrew/roam-and-trail",
              "git_branch": "main", "git_repo_root": "/u/andrew/roam-and-trail", "source": "hermes-dispatch" }
          ]
        },
        {
          "id": "/u/andrew/inbound", "label": "inbound", "path": "/u/andrew/inbound",
          "isAuto": true, "sessionCount": 1, "lastActive": 1751990000.0,
          "repos": [],
          "previewSessions": [ { "id": "s2", "title": "Flight tracker", "message_count": 2 } ]
        }
      ],
      "active_id": "p_ab12",
      "scoped_session_ids": ["s1", "s2"]
    }
    """.trimIndent()

    @Test fun maps_full_tree_including_repos_lanes_and_previews() {
        val tree = json.decodeFromString(ProjectTreeDto.serializer(), treeJson).toDomain()

        assertEquals("p_ab12", tree.activeId)
        assertEquals(2, tree.projects.size)

        val explicit = tree.projects[0]
        assertEquals("Roam and Trail", explicit.label)
        assertEquals("#4F8A10", explicit.color)
        assertEquals(false, explicit.isAuto)
        assertEquals(3, explicit.sessionCount)
        assertEquals(1, explicit.repos.size)
        assertEquals("roam-and-trail", explicit.repos[0].label)
        assertEquals(1, explicit.repos[0].lanes.size)
        assertTrue(explicit.repos[0].lanes[0].isMain)
        // preview session mapped through Session.toDomain
        assertEquals("Affiliate setup", explicit.previewSessions.single().title)
        assertEquals("main", explicit.previewSessions.single().gitBranch)
        assertEquals("/u/andrew/roam-and-trail", explicit.previewSessions.single().cwd)
    }

    @Test fun auto_project_has_null_color_and_no_repos() {
        val tree = json.decodeFromString(ProjectTreeDto.serializer(), treeJson).toDomain()
        val auto = tree.projects[1]
        assertEquals("inbound", auto.label)
        assertNull(auto.color)
        assertTrue(auto.isAuto)
        assertTrue(auto.repos.isEmpty())
        assertEquals("Flight tracker", auto.previewSessions.single().title)
    }

    @Test fun project_sessions_result_hydrates_lane_sessions() {
        val projJson = """
        { "project": { "id": "p_ab12", "label": "Roam and Trail", "path": "/u/andrew/roam-and-trail",
          "isAuto": false, "sessionCount": 1, "lastActive": 1752000000.0,
          "repos": [ { "id": "r", "label": "roam-and-trail", "path": "/u/andrew/roam-and-trail",
            "sessionCount": 1, "groups": [ { "id": "main", "label": "main", "path": "/u",
              "isMain": true, "isKanban": false,
              "sessions": [ { "id": "s1", "title": "Affiliate setup", "model": "gpt",
                "last_active": 1752000000.0, "message_count": 4 } ] } ] } ],
          "previewSessions": [] } }
        """.trimIndent()

        val project = json.decodeFromString(ProjectSessionsResultDto.serializer(), projJson).project!!.toDomain()
        assertEquals("Affiliate setup", project.repos.single().lanes.single().sessions.single().title)
    }

    @Test fun missing_project_maps_to_null() {
        val project = json.decodeFromString(ProjectSessionsResultDto.serializer(), """{"project":null}""").project
        assertNull(project)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "com.hermes.client.domain.ProjectMappersTest"`
Expected: FAIL to COMPILE — `ProjectTreeDto`, `ProjectSessionsResultDto`, `toDomain`, `Session.gitBranch`, `Session.cwd` unresolved.

- [ ] **Step 3: Add the DTOs**

In `app/src/main/java/com/hermes/client/data/network/Dtos.kt`, add `gitBranch`/`gitRepoRoot` to `SessionDto` (append inside the existing class, after `source`):

```kotlin
    @SerialName("git_branch") val gitBranch: String? = null,
    @SerialName("git_repo_root") val gitRepoRoot: String? = null,
```

Then append these new DTOs to the file:

```kotlin
/**
 * Gateway `projects.tree` result. Single-profile (the connected gateway's bound profile).
 * `scoped_session_ids` is parsed but unused in v1 (Sessions mode shows the flat REST list
 * independently). `icon` is an unknown key here and intentionally ignored (YAGNI).
 */
@Serializable data class ProjectTreeDto(
    val projects: List<ProjectNodeDto> = emptyList(),
    @SerialName("active_id") val activeId: String? = null,
)

@Serializable data class ProjectNodeDto(
    val id: String,
    val label: String = "",
    val path: String? = null,
    val color: String? = null,
    val isAuto: Boolean = false,
    val sessionCount: Int = 0,
    val lastActive: Double? = null,
    val repos: List<RepoDto> = emptyList(),
    // On projects.tree these are up to preview_limit newest rows; on project_sessions it's empty.
    val previewSessions: List<SessionDto> = emptyList(),
)

@Serializable data class RepoDto(
    val id: String,
    val label: String = "",
    val path: String? = null,
    val sessionCount: Int = 0,
    // Gateway calls the branch lanes "groups".
    val groups: List<LaneDto> = emptyList(),
)

@Serializable data class LaneDto(
    val id: String,
    val label: String = "",
    val path: String? = null,
    val isMain: Boolean = false,
    // Lane sessions are empty on projects.tree (counts only) and hydrated on project_sessions.
    val sessions: List<SessionDto> = emptyList(),
)

/** Gateway `projects.project_sessions` result — a single hydrated node (or null). */
@Serializable data class ProjectSessionsResultDto(
    val project: ProjectNodeDto? = null,
)
```

- [ ] **Step 4: Add the domain types**

In `app/src/main/java/com/hermes/client/domain/Models.kt`, extend `Session` — add these three fields after `lastActive` (keep the trailing comma style):

```kotlin
    // Full working directory (not just the basename in [workspace]); null when the session has none.
    val cwd: String? = null,
    // Git context resolved server-side, present on project-tree session rows; null otherwise.
    val gitBranch: String? = null,
    val gitRepoRoot: String? = null,
```

Then append the project domain types to the file:

```kotlin
/** A server-authoritative project (explicit user project or an auto git-repo/discovered project). */
data class Project(
    val id: String,
    val label: String,
    val path: String?,
    // Hex string like "#RRGGBB" for explicit projects; null for auto/discovered (render with accent).
    val color: String?,
    val isAuto: Boolean,
    val sessionCount: Int,
    val lastActive: Long?,
    val repos: List<ProjectRepo>,
    // Newest sessions for the overview card; empty after drill-in (lanes carry the full set then).
    val previewSessions: List<Session>,
)

data class ProjectRepo(
    val id: String,
    val label: String,
    val path: String?,
    val sessionCount: Int,
    val lanes: List<ProjectLane>,
)

data class ProjectLane(
    val id: String,
    val label: String,
    val path: String?,
    val isMain: Boolean,
    val sessions: List<Session>,
)

data class ProjectTree(
    val projects: List<Project>,
    val activeId: String?,
)
```

- [ ] **Step 5: Add the mappers**

In `app/src/main/java/com/hermes/client/domain/Mappers.kt`, add the new imports at the top:

```kotlin
import com.hermes.client.data.network.LaneDto
import com.hermes.client.data.network.ProjectNodeDto
import com.hermes.client.data.network.ProjectTreeDto
import com.hermes.client.data.network.RepoDto
```

Update `SessionDto.toDomain()` — add the three new fields (place after `source = source,`):

```kotlin
    cwd = cwd?.ifBlank { null },
    gitBranch = gitBranch?.ifBlank { null },
    gitRepoRoot = gitRepoRoot?.ifBlank { null },
```

Append the project mappers:

```kotlin
fun ProjectTreeDto.toDomain() = ProjectTree(
    projects = projects.map { it.toDomain() },
    activeId = activeId,
)

fun ProjectNodeDto.toDomain() = Project(
    id = id,
    label = label,
    path = path,
    color = color?.ifBlank { null },
    isAuto = isAuto,
    sessionCount = sessionCount,
    lastActive = com.hermes.client.ui.util.secondsToEpochMs(lastActive),
    repos = repos.map { it.toDomain() },
    previewSessions = previewSessions.map { it.toDomain() },
)

fun RepoDto.toDomain() = ProjectRepo(
    id = id,
    label = label,
    path = path,
    sessionCount = sessionCount,
    lanes = groups.map { it.toDomain() },
)

fun LaneDto.toDomain() = ProjectLane(
    id = id,
    label = label,
    path = path,
    isMain = isMain,
    sessions = sessions.map { it.toDomain() },
)
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "com.hermes.client.domain.ProjectMappersTest"`
Expected: PASS (4 tests).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/hermes/client/data/network/Dtos.kt \
        app/src/main/java/com/hermes/client/domain/Models.kt \
        app/src/main/java/com/hermes/client/domain/Mappers.kt \
        app/src/test/java/com/hermes/client/domain/ProjectMappersTest.kt
git commit -m "feat(projects): project-tree DTOs, domain types, and mappers"
```

---

## Task 2: ProjectsRepository (WS JSON-RPC)

**Files:**
- Create: `app/src/main/java/com/hermes/client/data/repository/ProjectsRepository.kt`
- Modify: `app/src/main/java/com/hermes/client/di/AppModule.kt`
- Test: `app/src/test/java/com/hermes/client/data/repository/ProjectsRepositoryTest.kt` *(create)*

**Interfaces:**
- Consumes: `HermesGatewayClient.call(method: String, params: JsonObject): JsonElement` (Task-independent, existing); `ProjectTreeDto`, `ProjectSessionsResultDto`, `toDomain()` (Task 1).
- Produces: `class ProjectsRepository(client, json)` with `suspend fun tree(previewLimit: Int = 3): ProjectTree` and `suspend fun projectSessions(projectId: String): Project?`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/hermes/client/data/repository/ProjectsRepositoryTest.kt`:

```kotlin
package com.hermes.client.data.repository

import com.hermes.client.data.network.HermesGatewayClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProjectsRepositoryTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun tree_calls_projects_tree_and_parses_nodes() = runTest {
        val client = mockk<HermesGatewayClient>(relaxed = true)
        coEvery { client.call(any(), any()) } returns json.parseToJsonElement(
            """{ "projects": [ { "id": "p1", "label": "Alpha", "isAuto": false, "sessionCount": 2 } ],
                 "active_id": "p1" }""",
        )
        val repo = ProjectsRepository(client, json)

        val tree = repo.tree()

        assertEquals("p1", tree.activeId)
        assertEquals("Alpha", tree.projects.single().label)
        coVerify { client.call("projects.tree", any()) }
    }

    @Test fun tree_passes_preview_limit_param() = runTest {
        val client = mockk<HermesGatewayClient>(relaxed = true)
        coEvery { client.call(any(), any()) } returns json.parseToJsonElement("""{"projects":[]}""")
        val repo = ProjectsRepository(client, json)

        repo.tree(previewLimit = 5)

        coVerify { client.call("projects.tree", match { it["preview_limit"]!!.jsonPrimitive.content == "5" }) }
    }

    @Test fun projectSessions_calls_rpc_with_id_and_parses_project() = runTest {
        val client = mockk<HermesGatewayClient>(relaxed = true)
        coEvery { client.call(any(), any()) } returns json.parseToJsonElement(
            """{ "project": { "id": "p1", "label": "Alpha", "isAuto": false, "sessionCount": 1,
                 "repos": [], "previewSessions": [] } }""",
        )
        val repo = ProjectsRepository(client, json)

        val project = repo.projectSessions("p1")

        assertEquals("Alpha", project?.label)
        coVerify { client.call("projects.project_sessions", match { it["project_id"]!!.jsonPrimitive.content == "p1" }) }
    }

    @Test fun projectSessions_returns_null_when_project_absent() = runTest {
        val client = mockk<HermesGatewayClient>(relaxed = true)
        coEvery { client.call(any(), any()) } returns json.parseToJsonElement("""{"project":null}""")
        val repo = ProjectsRepository(client, json)

        assertNull(repo.projectSessions("nope"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "com.hermes.client.data.repository.ProjectsRepositoryTest"`
Expected: FAIL to COMPILE — `ProjectsRepository` unresolved.

- [ ] **Step 3: Create the repository**

Create `app/src/main/java/com/hermes/client/data/repository/ProjectsRepository.kt`:

```kotlin
package com.hermes.client.data.repository

import com.hermes.client.data.network.HermesGatewayClient
import com.hermes.client.data.network.ProjectSessionsResultDto
import com.hermes.client.data.network.ProjectTreeDto
import com.hermes.client.domain.Project
import com.hermes.client.domain.ProjectTree
import com.hermes.client.domain.toDomain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Browse-only access to the gateway's server-authoritative project tree over WS JSON-RPC.
 * Single-profile: `projects.tree` reflects the connected gateway's bound profile and takes no
 * profile param (see the design's profile-scoping note). Piggybacks on the socket the sessions
 * screen already opens via [ChatRepository.connect].
 */
class ProjectsRepository(
    private val client: HermesGatewayClient,
    private val json: Json,
) {
    /** Fetch the project overview (nodes with counts + up to [previewLimit] preview sessions). */
    suspend fun tree(previewLimit: Int = 3): ProjectTree {
        val result = client.call("projects.tree", buildJsonObject { put("preview_limit", previewLimit) })
        return json.decodeFromJsonElement(ProjectTreeDto.serializer(), result).toDomain()
    }

    /** Hydrate one project's sessions for drill-in. [projectId] must equal a tree node id. */
    suspend fun projectSessions(projectId: String): Project? {
        val result = client.call("projects.project_sessions", buildJsonObject { put("project_id", projectId) })
        return json.decodeFromJsonElement(ProjectSessionsResultDto.serializer(), result).project?.toDomain()
    }
}
```

- [ ] **Step 4: Wire DI**

In `app/src/main/java/com/hermes/client/di/AppModule.kt`, add after `provideChatRepository`:

```kotlin
    @Provides
    @Singleton
    fun provideProjectsRepository(
        client: HermesGatewayClient,
        json: Json,
    ): com.hermes.client.data.repository.ProjectsRepository =
        com.hermes.client.data.repository.ProjectsRepository(client, json)
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "com.hermes.client.data.repository.ProjectsRepositoryTest"`
Expected: PASS (4 tests).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/hermes/client/data/repository/ProjectsRepository.kt \
        app/src/main/java/com/hermes/client/di/AppModule.kt \
        app/src/test/java/com/hermes/client/data/repository/ProjectsRepositoryTest.kt
git commit -m "feat(projects): ProjectsRepository over projects.* WS RPC"
```

---

## Task 3: View-mode persistence + pure recency sort

**Files:**
- Create: `app/src/main/java/com/hermes/client/data/repository/ViewModeStore.kt`
- Modify: `app/src/main/java/com/hermes/client/di/AppModule.kt`
- Modify: `app/src/main/java/com/hermes/client/ui/sessions/SessionGrouping.kt`
- Test: `app/src/test/java/com/hermes/client/ui/sessions/SessionGroupingTest.kt` *(modify)*

**Interfaces:**
- Produces:
  - `enum class ViewMode { SESSIONS, PROJECTS }` (in `com.hermes.client.ui.sessions`, top of `SessionGrouping.kt`).
  - `class ViewModeStore(context)` with `val mode: Flow<ViewMode>` (default `SESSIONS`) and `suspend fun set(mode: ViewMode)`.
  - `fun sessionsByRecency(sessions: List<Session>): List<Session>` (newest `lastActive` first; nulls last).

- [ ] **Step 1: Write the failing test**

Append to `app/src/test/java/com/hermes/client/ui/sessions/SessionGroupingTest.kt` (inside the existing test class):

```kotlin
    private fun s(id: String, lastActive: Long?) = com.hermes.client.domain.Session(
        id = id, title = id, model = null, provider = null, messageCount = 1,
        profile = "personal", lastActive = lastActive,
    )

    @org.junit.Test fun sessionsByRecency_orders_newest_first_nulls_last() {
        val out = sessionsByRecency(listOf(s("a", 100), s("b", null), s("c", 300))).map { it.id }
        org.junit.Assert.assertEquals(listOf("c", "a", "b"), out)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "com.hermes.client.ui.sessions.SessionGroupingTest"`
Expected: FAIL to COMPILE — `sessionsByRecency` unresolved.

- [ ] **Step 3: Add `ViewMode` + `sessionsByRecency`**

At the TOP of `app/src/main/java/com/hermes/client/ui/sessions/SessionGrouping.kt` (below the package line, above `WorkspaceGroup`):

```kotlin
/** Which list the Chats screen shows: a flat recency list, or the gateway's project tree. */
enum class ViewMode { SESSIONS, PROJECTS }
```

At the BOTTOM of the same file:

```kotlin
/** Flat, most-recent-first order for Sessions mode. Sessions with no [Session.lastActive] sort last. */
fun sessionsByRecency(sessions: List<Session>): List<Session> =
    sessions.sortedByDescending { it.lastActive ?: Long.MIN_VALUE }
```

- [ ] **Step 4: Create `ViewModeStore`**

Create `app/src/main/java/com/hermes/client/data/repository/ViewModeStore.kt`:

```kotlin
package com.hermes.client.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.hermes.client.ui.sessions.ViewMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.viewModeDataStore by preferencesDataStore(name = "sessions_view_mode")

/** Device-local persisted Chats view mode. Defaults to [ViewMode.SESSIONS]. */
class ViewModeStore(private val context: Context) {
    private val key = stringPreferencesKey("view_mode")

    val mode: Flow<ViewMode> = context.viewModeDataStore.data.map { prefs ->
        when (prefs[key]) {
            ViewMode.PROJECTS.name -> ViewMode.PROJECTS
            else -> ViewMode.SESSIONS
        }
    }

    suspend fun set(mode: ViewMode) {
        context.viewModeDataStore.edit { it[key] = mode.name }
    }
}
```

- [ ] **Step 5: Wire DI**

In `app/src/main/java/com/hermes/client/di/AppModule.kt`, add after `provideGroupExpansionStore`:

```kotlin
    @Provides
    @Singleton
    fun provideViewModeStore(
        @ApplicationContext context: Context,
    ): com.hermes.client.data.repository.ViewModeStore =
        com.hermes.client.data.repository.ViewModeStore(context)
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "com.hermes.client.ui.sessions.SessionGroupingTest"`
Expected: PASS (existing tests + the new one).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/hermes/client/data/repository/ViewModeStore.kt \
        app/src/main/java/com/hermes/client/di/AppModule.kt \
        app/src/main/java/com/hermes/client/ui/sessions/SessionGrouping.kt \
        app/src/test/java/com/hermes/client/ui/sessions/SessionGroupingTest.kt
git commit -m "feat(projects): ViewMode + persistence + recency sort"
```

---

## Task 4: ViewModel — view mode + projects state and actions

**Files:**
- Modify: `app/src/main/java/com/hermes/client/ui/sessions/SessionsViewModel.kt`
- Test: `app/src/test/java/com/hermes/client/ui/sessions/SessionsViewModelTest.kt`

**Interfaces:**
- Consumes: `ProjectsRepository.tree()`, `ProjectsRepository.projectSessions(id)` (Task 2); `ViewModeStore.mode`, `ViewModeStore.set` (Task 3); `ViewMode` (Task 3); `Project` (Task 1).
- Produces (on `SessionsViewModel`): `val viewMode: StateFlow<ViewMode>`; `val projectsState: StateFlow<ProjectsUiState>`; `fun setViewMode(mode: ViewMode)`; `fun loadProjectTree()`; `fun enterProject(project: Project)`; `fun exitProject()`. New constructor params appended: `projects: ProjectsRepository, viewModeStore: ViewModeStore`.
- Produces: `data class ProjectsUiState(tree, loading, error, scope, scopeLoading)`.

- [ ] **Step 1: Write the failing test**

Edit `app/src/test/java/com/hermes/client/ui/sessions/SessionsViewModelTest.kt`.

Add two mocked deps (after the `groupExpansion` field, line ~32):

```kotlin
    private val projects = mockk<com.hermes.client.data.repository.ProjectsRepository>(relaxed = true)
    private val viewModeStore = mockk<com.hermes.client.data.repository.ViewModeStore>(relaxed = true)
```

In `setUp()` add a default stub (after the `groupExpansion.collapsed` stub):

```kotlin
        every { viewModeStore.mode } returns MutableStateFlow(ViewMode.SESSIONS)
```

Update `buildVm()` to pass the new deps:

```kotlin
    private fun buildVm() = SessionsViewModel(sessionRepo, chatRepo, profileManager, pinStore, groupExpansion, projects, viewModeStore)
```

Add these tests to the class:

```kotlin
    @Test fun setViewMode_persists_and_loads_tree_on_first_projects_entry() = runTest {
        coEvery { sessionRepo.listAllProfiles() } returns emptyList()
        coEvery { projects.tree() } returns com.hermes.client.domain.ProjectTree(
            projects = listOf(
                com.hermes.client.domain.Project("p1", "Alpha", null, null, false, 2, null, emptyList(), emptyList()),
            ),
            activeId = "p1",
        )
        val vm = buildVm()
        advanceUntilIdle()

        vm.setViewMode(ViewMode.PROJECTS)
        advanceUntilIdle()

        io.mockk.coVerify { viewModeStore.set(ViewMode.PROJECTS) }
        io.mockk.coVerify { projects.tree() }
        assertEquals(listOf("p1"), vm.projectsState.value.tree.map { it.id })
    }

    @Test fun loadProjectTree_sets_error_on_failure() = runTest {
        coEvery { sessionRepo.listAllProfiles() } returns emptyList()
        coEvery { projects.tree() } throws RuntimeException("rpc down")
        val vm = buildVm()
        advanceUntilIdle()

        vm.loadProjectTree()
        advanceUntilIdle()

        assertFalse(vm.projectsState.value.loading)
        assertTrue(vm.projectsState.value.error != null)
    }

    @Test fun enterProject_hydrates_scope_then_exit_clears_it() = runTest {
        coEvery { sessionRepo.listAllProfiles() } returns emptyList()
        val overview = com.hermes.client.domain.Project("p1", "Alpha", null, null, false, 2, null, emptyList(), emptyList())
        val hydrated = overview.copy(
            repos = listOf(
                com.hermes.client.domain.ProjectRepo("r", "alpha", null, 1, listOf(
                    com.hermes.client.domain.ProjectLane("main", "main", null, true, listOf(session("s1", "Hi"))),
                )),
            ),
        )
        coEvery { projects.projectSessions("p1") } returns hydrated
        val vm = buildVm()
        advanceUntilIdle()

        vm.enterProject(overview)
        advanceUntilIdle()
        assertEquals("p1", vm.projectsState.value.scope?.id)
        assertEquals("s1", vm.projectsState.value.scope?.repos?.single()?.lanes?.single()?.sessions?.single()?.id)

        vm.exitProject()
        assertNull(vm.projectsState.value.scope)
    }
```

Add the import needed for `assertNull`:

```kotlin
import org.junit.Assert.assertNull
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "com.hermes.client.ui.sessions.SessionsViewModelTest"`
Expected: FAIL to COMPILE — new constructor params / `viewMode` / `projectsState` / `setViewMode` / `enterProject` unresolved.

- [ ] **Step 3: Extend the ViewModel**

In `app/src/main/java/com/hermes/client/ui/sessions/SessionsViewModel.kt`:

Add imports:

```kotlin
import com.hermes.client.data.repository.ProjectsRepository
import com.hermes.client.data.repository.ViewModeStore
import com.hermes.client.domain.Project
```

Add the projects UI-state type below `SessionsUiState`:

```kotlin
/** Projects-mode state: [tree] is the overview; [scope] is the drilled-in hydrated project (null = overview). */
data class ProjectsUiState(
    val tree: List<Project> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val scope: Project? = null,
    val scopeLoading: Boolean = false,
)
```

Append the two constructor params (after `groupExpansion`):

```kotlin
    private val projects: ProjectsRepository,
    private val viewModeStore: ViewModeStore,
```

Add the state + actions inside the class (e.g. after `toggleGroup`):

```kotlin
    /** Persisted view mode (Sessions flat list vs the gateway project tree). */
    val viewMode: StateFlow<ViewMode> =
        viewModeStore.mode.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ViewMode.SESSIONS)

    private val _projects = MutableStateFlow(ProjectsUiState())
    val projectsState: StateFlow<ProjectsUiState> = _projects.asStateFlow()

    /** Switch view mode; on the first entry into Projects (empty tree) fetch it. */
    fun setViewMode(mode: ViewMode) {
        viewModelScope.launch { viewModeStore.set(mode) }
        if (mode == ViewMode.PROJECTS && _projects.value.tree.isEmpty() && !_projects.value.loading) {
            loadProjectTree()
        }
    }

    private var projectTreeJob: Job? = null

    /** Fetch the project overview (also the retry entry point). Latest-wins like [refresh]. */
    fun loadProjectTree() {
        projectTreeJob?.cancel()
        projectTreeJob = viewModelScope.launch {
            _projects.value = _projects.value.copy(loading = true, error = null)
            try {
                val tree = projects.tree()
                _projects.value = _projects.value.copy(loading = false, tree = tree.projects)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _projects.value = _projects.value.copy(loading = false, error = e.message ?: "Failed to load projects")
            }
        }
    }

    /** Drill into a project: hydrate its sessions. Falls back to the overview node on failure. */
    fun enterProject(project: Project) {
        viewModelScope.launch {
            _projects.value = _projects.value.copy(scope = project, scopeLoading = true)
            val hydrated = runCatching { projects.projectSessions(project.id) }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                .getOrNull() ?: project
            _projects.value = _projects.value.copy(scope = hydrated, scopeLoading = false)
        }
    }

    /** Return to the project overview. */
    fun exitProject() {
        _projects.value = _projects.value.copy(scope = null)
    }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "com.hermes.client.ui.sessions.SessionsViewModelTest"`
Expected: PASS (existing + 3 new tests).

- [ ] **Step 5: Full unit-test sweep + compile**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:compileDebugKotlin :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL; all unit tests green.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/hermes/client/ui/sessions/SessionsViewModel.kt \
        app/src/test/java/com/hermes/client/ui/sessions/SessionsViewModelTest.kt
git commit -m "feat(projects): view-mode + project tree/drill-in state in SessionsViewModel"
```

---

## Task 5: UI — segmented toggle, flat Sessions mode, Projects overview + drill-in

**Files:**
- Create: `app/src/main/java/com/hermes/client/ui/sessions/ProjectList.kt`
- Modify: `app/src/main/java/com/hermes/client/ui/sessions/SessionsScreen.kt`

**Interfaces:**
- Consumes: `vm.viewMode`, `vm.projectsState`, `vm.setViewMode`, `vm.loadProjectTree`, `vm.enterProject`, `vm.exitProject` (Task 4); `sessionsByRecency` (Task 3); `Project`, `ProjectLane` (Task 1); existing `SessionRow`, `SectionHeader`, `LocalProfileAccent`, `EmptyState`, `ErrorState`, `LoadingState`, `ProfileSwitcher`.
- Produces: composables `ProjectOverview`, `ProjectScopeView`, `ProjectCard` in `ProjectList.kt`.

- [ ] **Step 1: Create the Projects composables**

Create `app/src/main/java/com/hermes/client/ui/sessions/ProjectList.kt`:

```kotlin
package com.hermes.client.ui.sessions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.hermes.client.domain.Project
import com.hermes.client.domain.Session
import com.hermes.client.ui.theme.LocalProfileAccent

/** Parse the gateway's "#RRGGBB" project color; fall back to the tenant accent when null/invalid. */
@Composable
private fun projectTint(color: String?): Color {
    val accent = LocalProfileAccent.current.accent
    return color?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() } ?: accent
}

/** Projects overview: one tappable card per project. */
@Composable
fun ProjectOverview(projects: List<Project>, onOpenProject: (Project) -> Unit) {
    LazyColumn(Modifier.fillMaxWidth()) {
        items(projects, key = { it.id }) { p -> ProjectCard(p, onClick = { onOpenProject(p) }) }
    }
}

@Composable
fun ProjectCard(project: Project, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(project.label) },
        supportingContent = {
            val repos = if (project.isAuto) "auto" else "${project.repos.size} repo${if (project.repos.size == 1) "" else "s"}"
            Text("${project.sessionCount} session${if (project.sessionCount == 1) "" else "s"} · $repos")
        },
        leadingContent = {
            Icon(Icons.Rounded.Folder, contentDescription = null, tint = projectTint(project.color), modifier = Modifier.size(24.dp))
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/**
 * Drill-in for one hydrated project: a back row, then its sessions. A single-lane project renders a
 * flat list; multi-lane projects show repo/branch sub-headers.
 */
@Composable
fun ProjectScopeView(project: Project, onBack: () -> Unit, onOpenSession: (Session) -> Unit) {
    val lanes = project.repos.flatMap { repo -> repo.lanes.map { repo to it } }
    val multi = lanes.size > 1
    LazyColumn(Modifier.fillMaxWidth()) {
        item(key = "back") {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onBack).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start,
            ) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "Back", tint = LocalProfileAccent.current.accent, modifier = Modifier.padding(end = 8.dp))
                Text(project.label, style = MaterialTheme.typography.titleSmall, color = LocalProfileAccent.current.accent)
            }
        }
        lanes.forEach { (repo, lane) ->
            if (multi) {
                item(key = "lane-${repo.id}-${lane.id}") {
                    Text(
                        "${repo.label} · ${lane.label}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                    )
                }
            }
            items(lane.sessions, key = { "sess-${it.id}" }) { s ->
                ListItem(
                    headlineContent = { Text(s.title) },
                    supportingContent = { Text(s.model ?: "") },
                    modifier = Modifier.clickable { onOpenSession(s) },
                )
            }
        }
        if (lanes.isEmpty()) {
            item(key = "empty-scope") {
                Text(
                    "No sessions in this project.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}
```

- [ ] **Step 2: Add the toggle + branch the screen body**

In `app/src/main/java/com/hermes/client/ui/sessions/SessionsScreen.kt`:

Add imports:

```kotlin
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
```

Collect the new state (after the existing `collectAsStateWithLifecycle` calls, ~line 75):

```kotlin
    val viewMode by vm.viewMode.collectAsStateWithLifecycle()
    val projectsState by vm.projectsState.collectAsStateWithLifecycle()
```

In the `topBar` `Column`, replace the `if (profiles.size > 1) { ProfileSwitcher(...) }` block with a mode-aware version, and add the segmented toggle below it:

```kotlin
                // Sessions mode spans all profiles (REST); Projects mode is single-profile (the
                // gateway's bound profile — projects.tree takes no profile param), so the switcher
                // would be misleading there. Show a caption instead.
                if (viewMode == ViewMode.SESSIONS) {
                    if (profiles.size > 1) {
                        com.hermes.client.ui.components.ProfileSwitcher(
                            names = profiles.map { it.name },
                            active = activeProfile,
                            onSelect = vm::switchProfile,
                        )
                    }
                } else {
                    Text(
                        "Projects · ${activeProfile ?: "default"}",
                        style = MaterialTheme.typography.labelMedium,
                        color = com.hermes.client.ui.components.AccentChrome.onBar,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
                val accent = LocalProfileAccent.current
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    val tabs = listOf(ViewMode.SESSIONS to "Sessions", ViewMode.PROJECTS to "Projects")
                    tabs.forEachIndexed { i, (mode, label) ->
                        SegmentedButton(
                            selected = viewMode == mode,
                            onClick = { vm.setViewMode(mode) },
                            shape = SegmentedButtonDefaults.itemShape(i, tabs.size),
                            colors = SegmentedButtonDefaults.colors(
                                activeContainerColor = accent.accent,
                                activeContentColor = accent.onAccent,
                            ),
                        ) { Text(label) }
                    }
                }
```

Branch the body. The search field + current `Box { when { … } }` handle Sessions mode; wrap them so they only show in Sessions mode, and render Projects mode otherwise. Replace the `Column(Modifier.padding(padding).fillMaxSize()) { … }` body with:

```kotlin
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (viewMode == ViewMode.PROJECTS) {
                Box(Modifier.fillMaxSize()) {
                    when {
                        projectsState.loading && projectsState.tree.isEmpty() ->
                            com.hermes.client.ui.components.LoadingState()
                        projectsState.error != null ->
                            com.hermes.client.ui.components.ErrorState(
                                message = projectsState.error!!,
                                onRetry = { vm.loadProjectTree() },
                            )
                        projectsState.scope != null ->
                            ProjectScopeView(
                                project = projectsState.scope!!,
                                onBack = { vm.exitProject() },
                                onOpenSession = { s -> onOpen(s.id) },
                            )
                        projectsState.tree.isEmpty() ->
                            com.hermes.client.ui.components.EmptyState(
                                title = "No projects",
                                subtitle = "Projects are created on the desktop app.",
                                actionLabel = "Reload",
                                onAction = { vm.loadProjectTree() },
                            )
                        else -> ProjectOverview(projectsState.tree, onOpenProject = { vm.enterProject(it) })
                    }
                }
            } else {
                // ── Sessions mode (flat recency) ─────────────────────────────────────────────
                OutlinedTextField(
                    value = query,
                    onValueChange = vm::onQueryChange,
                    placeholder = { Text("Search sessions…") },
                    singleLine = true,
                    trailingIcon = {
                        if (query.isNotBlank()) {
                            IconButton(onClick = { vm.onQueryChange("") }) {
                                Icon(Icons.Rounded.Close, contentDescription = "Clear search")
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { vm.searchMessages() }),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                )
                Box(Modifier.fillMaxSize()) {
                    when {
                        state.loading -> com.hermes.client.ui.components.LoadingState()
                        state.error != null -> com.hermes.client.ui.components.ErrorState(
                            message = state.error!!,
                            onRetry = { vm.refresh() },
                        )
                        state.sessions.isEmpty() && query.isBlank() && messageResults.isEmpty() ->
                            com.hermes.client.ui.components.EmptyState(
                                title = "No sessions yet",
                                subtitle = "Start a conversation with the New button.",
                                actionLabel = "New session",
                                onAction = { scope.launch { vm.createSession()?.let { onOpen(it) } } },
                            )
                        else -> {
                            val q = query.trim()
                            val matches = if (q.isEmpty()) state.sessions
                            else state.sessions.filter {
                                it.title.contains(q, ignoreCase = true) ||
                                    it.workspace.contains(q, ignoreCase = true)
                            }
                            val isPinned = { s: Session ->
                                com.hermes.client.data.repository.PinStore.token(s.profile, s.id) in pinnedTokens
                            }
                            val pinned = matches.filter(isPinned)
                            val recent = sessionsByRecency(matches.filterNot(isPinned))

                            LazyColumn {
                                if (messageResults.isNotEmpty()) {
                                    item(key = "h-msg") { SectionHeader("Message matches", messageResults.size) }
                                    items(messageResults) { r ->
                                        ListItem(
                                            headlineContent = {
                                                Text(r.snippet?.take(140)?.replace("\n", " ") ?: r.sessionId)
                                            },
                                            supportingContent = { Text(r.model ?: r.role ?: "") },
                                            modifier = Modifier.clickable { onOpen(r.sessionId) },
                                        )
                                        HorizontalDivider()
                                    }
                                }
                                if (q.isNotEmpty() && matches.isEmpty() && messageResults.isEmpty()) {
                                    item(key = "no-title-match") {
                                        Text(
                                            "No titles match \"$q\". Press search on the keyboard to search message text.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(16.dp),
                                        )
                                    }
                                }
                                if (pinned.isNotEmpty()) {
                                    item(key = "h-pinned") { SectionHeader("Pinned", pinned.size, note = "Device only") }
                                    items(pinned, key = { "p-${it.id}" }) { s ->
                                        SessionRow(
                                            session = s, isPinned = true, showProfile = true,
                                            onOpen = { scope.launch { vm.prepareOpen(s); onOpen(s.id) } },
                                            onTogglePin = { vm.togglePin(s) },
                                            onRename = { vm.rename(s, it) },
                                            onArchive = { vm.archive(s) },
                                            onDelete = { vm.delete(s) },
                                            modifier = Modifier.animateItem(),
                                        )
                                    }
                                }
                                if (recent.isNotEmpty()) {
                                    item(key = "h-recent") { SectionHeader("Recent", recent.size) }
                                    items(recent, key = { it.id }) { s ->
                                        SessionRow(
                                            session = s, isPinned = false, showProfile = true,
                                            onOpen = { scope.launch { vm.prepareOpen(s); onOpen(s.id) } },
                                            onTogglePin = { vm.togglePin(s) },
                                            onRename = { vm.rename(s, it) },
                                            onArchive = { vm.archive(s) },
                                            onDelete = { vm.delete(s) },
                                            modifier = Modifier.animateItem(),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
```

Note: `showProfile = true` in flat mode (there are no profile headers now, so the tenant prefix stays useful). The old `groupSessions`/`CollapsibleHeader` grouping is removed from the screen; leave the `CollapsibleHeader` composable in the file only if still referenced — otherwise delete it (and the now-unused `collapsedGroups`, `groupSessions` import) to keep the file clean.

- [ ] **Step 3: Compile**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. Fix any unused-symbol / import errors (remove `groupSessions`, `CollapsibleHeader`, `collapsedGroups` if the compiler flags them as unused and the project treats warnings as errors; otherwise leave them).

- [ ] **Step 4: Unit tests still green**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL; all tests pass.

- [ ] **Step 5: Assemble the beta variant**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleBeta`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/hermes/client/ui/sessions/ProjectList.kt \
        app/src/main/java/com/hermes/client/ui/sessions/SessionsScreen.kt
git commit -m "feat(projects): Sessions/Projects toggle, flat sessions, project drill-in UI"
```

---

## Task 6: On-device verification

**Files:** none (manual verification against a real/mock gateway on the USB-connected device).

- [ ] **Step 1: Install the beta build**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:installBeta`
Expected: installs `com.hermes.client.beta`.

- [ ] **Step 2: Verify Sessions mode**
  - Chats opens in **Sessions** mode (default). List is flat, newest first; Pinned section on top; search filters by title/workspace; the profile switcher is visible; tapping a session opens the chat.

- [ ] **Step 3: Verify the toggle + Projects overview**
  - Tap **Projects**: the profile switcher is replaced by a `Projects · <profile>` caption; project cards render (explicit projects tinted by their color, auto projects with the accent-tinted folder + "auto"); session/repo counts show. Empty gateway → "No projects" state with Reload.

- [ ] **Step 4: Verify drill-in + open**
  - Tap a project → its sessions appear (single-lane = flat; multi-repo/branch = sub-headers). Tap a session → chat opens. Back row returns to the overview.

- [ ] **Step 5: Verify persistence + accent + errors**
  - Switch to Projects, navigate away and back (and cold-restart) → still on Projects. Per-tenant accent tints the segmented control, cards, and back row. Kill the gateway and Reload → error/retry row shows in Projects mode while Sessions mode is unaffected.

- [ ] **Step 6: Record results** in the task ledger / PR description (what was exercised, any gaps). No commit.

---

## Notes for the executor

- The pre-flight scan found no plan-vs-spec conflicts. One deliberate refinement of the spec: `icon` is not carried into the domain model (YAGNI) — projects use a folder glyph tinted by `color`/accent. This is intentional, not an omission.
- Single-profile Projects is by design (the gateway RPC has no profile param). Opening a project-view session relies on the connected gateway's profile matching the active profile; this is the documented limitation, not a bug to fix here.
- Do not add `all`-pending, project management, or a `profile` param — all explicitly out of scope.
