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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionsViewModelTest {
    private val sessionRepo = mockk<SessionRepository>(relaxed = true)
    private val chatRepo = mockk<ChatRepository>(relaxed = true)
    private val profileManager = mockk<ProfileManager>(relaxed = true)
    private val pinStore = mockk<PinStore>(relaxed = true)

    @Before fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        every { profileManager.active } returns MutableStateFlow<String?>("personal")
        every { pinStore.pinned } returns MutableStateFlow<Set<String>>(emptySet())
    }

    private fun session(id: String, title: String, profile: String = "personal") = Session(
        id = id, title = title, model = null, provider = null,
        messageCount = 1, profile = profile, workspace = "No workspace", source = "hermes-dispatch",
    )

    private fun buildVm() = SessionsViewModel(sessionRepo, chatRepo, profileManager, pinStore)

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

    // T1: the list spans every profile (desktop mirror), and each session keeps its OWN profile
    // — not the active one. This is the fix for "wrong profile shown": the app no longer guesses
    // the profile from the active selection.
    @Test fun list_spans_all_profiles_each_with_its_own_profile() = runTest {
        coEvery { sessionRepo.listAllProfiles() } returns listOf(
            session("s1", "mine", profile = "personal"),
            session("s2", "client", profile = "odos"),
        )
        val vm = buildVm() // active profile is "personal"
        advanceUntilIdle()

        val byId = vm.state.value.sessions.associateBy { it.id }
        assertEquals(setOf("s1", "s2"), byId.keys)
        assertEquals("personal", byId["s1"]?.profile)
        assertEquals("odos", byId["s2"]?.profile) // not coerced to the active "personal"
    }
}
