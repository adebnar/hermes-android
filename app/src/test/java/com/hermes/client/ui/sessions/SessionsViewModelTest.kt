package com.hermes.client.ui.sessions

import com.hermes.client.data.repository.ChatRepository
import com.hermes.client.data.repository.PinStore
import com.hermes.client.data.repository.ProfileManager
import com.hermes.client.data.repository.SessionRepository
import com.hermes.client.domain.Session
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionsViewModelTest {
    private val sessionRepo = mockk<SessionRepository>(relaxed = true)
    private val chatRepo = mockk<ChatRepository>(relaxed = true)
    private val profileManager = mockk<ProfileManager>(relaxed = true)
    private val pinStore = mockk<PinStore>(relaxed = true)
    private val groupExpansion = mockk<com.hermes.client.data.repository.GroupExpansionStore>(relaxed = true)
    private val projects = mockk<com.hermes.client.data.repository.ProjectsRepository>(relaxed = true)
    private val viewModeStore = mockk<com.hermes.client.data.repository.ViewModeStore>(relaxed = true)

    @Before fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        every { chatRepo.events } returns kotlinx.coroutines.flow.MutableSharedFlow()
        every { profileManager.active } returns MutableStateFlow<String?>("personal")
        every { pinStore.pinned } returns MutableStateFlow<Set<String>>(emptySet())
        every { groupExpansion.collapsed } returns MutableStateFlow<Set<String>>(emptySet())
        every { viewModeStore.mode } returns MutableStateFlow(ViewMode.SESSIONS)
    }

    private fun session(id: String, title: String, profile: String = "personal") = Session(
        id = id, title = title, model = null, provider = null,
        messageCount = 1, profile = profile, workspace = "No workspace", source = "hermes-dispatch",
    )

    private fun buildVm() = SessionsViewModel(sessionRepo, chatRepo, profileManager, pinStore, groupExpansion, projects, viewModeStore)

    // Regression: a session created or updated while the user was in a chat must appear once
    // the list is re-fetched. The Sessions screen calls refresh() on ON_RESUME (the "sessions"
    // ViewModel is not recreated when navigating back, so init runs only once). This proves
    // refresh() re-queries the repository and surfaces the newly-added session — the mechanism
    // the resume hook relies on. Without that hook, the user's just-run session never shows.
    // Typing a query is an instant client-side concern; only the explicit Search action hits
    // the gateway. searchMessages must populate messageResults from the repo, and clearing the
    // query must clear results.
    @Test fun searchMessages_populates_results_and_clear_resets() = runTest {
        coEvery { sessionRepo.listAllProfiles() } returns emptyList()
        coEvery { sessionRepo.search(any(), any()) } returns listOf(
            com.hermes.client.data.network.SearchResultDto(sessionId = "s9", snippet = "found it"),
        )
        val vm = buildVm()
        advanceUntilIdle()

        vm.onQueryChange("invoice")
        vm.searchMessages()
        advanceUntilIdle()
        assertEquals(listOf("s9"), vm.messageResults.value.map { it.sessionId })

        vm.onQueryChange("")
        advanceUntilIdle()
        assertTrue("clearing the query clears message results", vm.messageResults.value.isEmpty())
    }

    @Test fun refresh_resurfaces_newly_added_session() = runTest {
        coEvery { sessionRepo.listAllProfiles() } returns listOf(session("s1", "old"))
        val vm = buildVm()
        advanceUntilIdle()
        assertEquals(listOf("s1"), vm.state.value.sessions.map { it.id })

        // A new session now exists on the gateway, as if a prompt created one while in a chat.
        coEvery { sessionRepo.listAllProfiles() } returns listOf(session("s2", "new"), session("s1", "old"))
        vm.refresh()
        advanceUntilIdle()

        val ids = vm.state.value.sessions.map { it.id }
        assertTrue("refresh must surface the newly-added session", ids.contains("s2"))
        assertEquals(2, ids.size)
    }

    // Regression: after a new chat's first message, the gateway auto-generates a title and pushes a
    // `session.title` WS event. The list must re-fetch on that event so the AI title replaces
    // "Untitled" — mirroring the desktop. Without this the new chat stays "Untitled" forever.
    @Test fun session_title_event_refreshes_the_list() = runTest {
        val events = kotlinx.coroutines.flow.MutableSharedFlow<com.hermes.client.data.network.ServerEvent>(extraBufferCapacity = 8)
        every { chatRepo.events } returns events
        var fetches = 0
        coEvery { sessionRepo.listAllProfiles() } coAnswers { fetches++; emptyList() }
        buildVm()
        advanceUntilIdle()
        val before = fetches

        events.emit(
            com.hermes.client.data.network.ServerEvent(
                type = "session.title",
                sessionId = "sk1",
                payload = buildJsonObject { put("title", "Fix the login bug") },
            ),
        )
        advanceUntilIdle()

        assertTrue("a session.title event must trigger a list refresh", fetches > before)
    }

    // The list is scoped to the active profile (one tenant at a time, like the desktop): a session
    // from another profile is filtered out, and each shown session keeps its own true profile.
    @Test fun list_is_scoped_to_active_profile() = runTest {
        coEvery { sessionRepo.listAllProfiles() } returns listOf(
            session("s1", "mine", profile = "personal"),
            session("s2", "client", profile = "odos"),
        )
        val vm = buildVm() // active profile is "personal"
        advanceUntilIdle()

        assertEquals(listOf("s1"), vm.state.value.sessions.map { it.id })
        assertEquals("personal", vm.state.value.sessions.single().profile)
    }

    // T5: in the cross-profile list, a session is pinned by its OWN profile token — so a pin made
    // in "odos" still reads as pinned even though the active profile is "personal". Keying by the
    // active profile (the old behavior) would make cross-profile pins vanish.
    @Test fun isPinned_keys_off_session_profile_not_active_profile() = runTest {
        coEvery { sessionRepo.listAllProfiles() } returns emptyList()
        val vm = buildVm() // active profile is "personal"
        advanceUntilIdle()

        val odosSession = session("s2", "client", profile = "odos")
        assertTrue("odos pin matches the odos session", vm.isPinned(odosSession, setOf("odos/s2")))
        assertFalse("a personal-scoped token must not match", vm.isPinned(odosSession, setOf("personal/s2")))
    }

    // T5: pinning uses the session's own profile token.
    @Test fun togglePin_uses_session_profile_token() = runTest {
        coEvery { sessionRepo.listAllProfiles() } returns emptyList()
        val vm = buildVm()
        advanceUntilIdle()

        vm.togglePin(session("s2", "client", profile = "odos"))
        advanceUntilIdle()
        io.mockk.coVerify { pinStore.toggle("odos/s2") }
    }

    // Archiving must carry the session's profile, or the gateway PATCH 404s against the wrong
    // per-profile DB and the session never leaves the list (looks like "refresh doesn't work").
    @Test fun archive_passes_session_profile() = runTest {
        coEvery { sessionRepo.listAllProfiles() } returns emptyList()
        val vm = buildVm()
        advanceUntilIdle()

        vm.archive(session("s1", "mine", profile = "personal"))
        advanceUntilIdle()
        io.mockk.coVerify { sessionRepo.archive("s1", archived = true, "personal") }
    }

    // T3: opening a session from another profile must switch the active profile first, so the
    // chat resumes against the correct per-profile DB.
    @Test fun prepareOpen_switches_to_session_profile_when_different() = runTest {
        coEvery { sessionRepo.listAllProfiles() } returns emptyList()
        val vm = buildVm() // active is "personal"
        advanceUntilIdle()

        vm.prepareOpen(session("s2", "client", profile = "odos"))
        advanceUntilIdle()
        io.mockk.coVerify { profileManager.switchTo("odos") }
    }

    // T3: no switch when the session is already in the active profile.
    @Test fun prepareOpen_is_noop_for_active_profile_session() = runTest {
        coEvery { sessionRepo.listAllProfiles() } returns emptyList()
        val vm = buildVm() // active is "personal"
        advanceUntilIdle()

        vm.prepareOpen(session("s1", "mine", profile = "personal"))
        advanceUntilIdle()
        io.mockk.coVerify(exactly = 0) { profileManager.switchTo(any()) }
    }

    // T2: toggling a group delegates to the persisted store (collapse state survives navigation).
    @Test fun toggleGroup_persists_via_store() = runTest {
        coEvery { sessionRepo.listAllProfiles() } returns emptyList()
        val vm = buildVm()
        advanceUntilIdle()

        vm.toggleGroup("p:odos")
        advanceUntilIdle()
        io.mockk.coVerify { groupExpansion.toggle("p:odos") }
    }

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

    // Regression: exitProject must cancel an in-flight enterProject so a stale projectSessions
    // result does NOT resurrect the scope. The old code had no job tracking, so rapid exit
    // after enter would let the delayed fetch complete and set scope back to the hydrated
    // project (looking like the user can't leave the detail view). This test verifies the race
    // is fixed: exitProject cancels the pending fetch, scope stays null.
    @Test fun exitProject_cancels_in_flight_enterProject_so_stale_result_does_not_resurrect_scope() = runTest {
        coEvery { sessionRepo.listAllProfiles() } returns emptyList()
        val overview = com.hermes.client.domain.Project("p1", "Alpha", null, null, false, 2, null, emptyList(), emptyList())
        val hydrated = overview.copy(
            repos = listOf(
                com.hermes.client.domain.ProjectRepo("r", "alpha", null, 1, listOf(
                    com.hermes.client.domain.ProjectLane("main", "main", null, true, listOf(session("s1", "Hi"))),
                )),
            ),
        )
        // Mock projectSessions with a delay so it's still in flight when exitProject runs
        coEvery { projects.projectSessions("p1") } coAnswers {
            kotlinx.coroutines.delay(1000)
            hydrated
        }
        val vm = buildVm()
        advanceUntilIdle()

        vm.enterProject(overview)
        vm.exitProject()
        advanceUntilIdle()

        assertNull(vm.projectsState.value.scope)
        assertFalse(vm.projectsState.value.scopeLoading)
    }
}
