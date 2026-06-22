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

    private fun session(id: String, title: String) = Session(
        id = id, title = title, model = null, provider = null,
        messageCount = 1, profile = "personal", workspace = "No workspace", source = "hermes-dispatch",
    )

    private fun buildVm() = SessionsViewModel(sessionRepo, chatRepo, profileManager, pinStore)

    // Regression: a session created or updated while the user was in a chat must appear once
    // the list is re-fetched. The Sessions screen calls refresh() on ON_RESUME (the "sessions"
    // ViewModel is not recreated when navigating back, so init runs only once). This proves
    // refresh() re-queries the repository and surfaces the newly-added session — the mechanism
    // the resume hook relies on. Without that hook, the user's just-run session never shows.
    @Test fun refresh_resurfaces_newly_added_session() = runTest {
        coEvery { sessionRepo.list(any()) } returns listOf(session("s1", "old"))
        val vm = buildVm()
        advanceUntilIdle()
        assertEquals(listOf("s1"), vm.state.value.sessions.map { it.id })

        // A new session now exists on the gateway, as if a prompt created one while in a chat.
        coEvery { sessionRepo.list(any()) } returns listOf(session("s2", "new"), session("s1", "old"))
        vm.refresh()
        advanceUntilIdle()

        val ids = vm.state.value.sessions.map { it.id }
        assertTrue("refresh must surface the newly-added session", ids.contains("s2"))
        assertEquals(2, ids.size)
    }
}
