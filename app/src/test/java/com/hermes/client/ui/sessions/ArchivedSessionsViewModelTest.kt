package com.hermes.client.ui.sessions

import com.hermes.client.data.repository.ProfileManager
import com.hermes.client.data.repository.SessionRepository
import com.hermes.client.domain.Session
import io.mockk.coEvery
import io.mockk.coVerify
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
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ArchivedSessionsViewModelTest {
    private val sessionRepo = mockk<SessionRepository>(relaxed = true)
    private val profileManager = mockk<ProfileManager>(relaxed = true)

    @Before fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        every { profileManager.active } returns MutableStateFlow<String?>("personal")
    }

    private fun session(id: String) = Session(
        id = id, title = id, model = null, provider = null,
        messageCount = 1, profile = "personal", workspace = "No workspace", source = null,
    )

    private fun buildVm() = ArchivedSessionsViewModel(sessionRepo, profileManager)

    @Test fun loads_archived_sessions_on_init() = runTest {
        coEvery { sessionRepo.archivedAllProfiles() } returns listOf(session("a1"), session("a2"))
        val vm = buildVm()
        advanceUntilIdle()
        assertEquals(listOf("a1", "a2"), vm.state.value.sessions.map { it.id })
    }

    @Test fun unarchive_restores_then_reloads() = runTest {
        coEvery { sessionRepo.archivedAllProfiles() } returns listOf(session("a1"))
        val vm = buildVm()
        advanceUntilIdle()
        // After restoring, the session is no longer archived, so the reloaded list is empty.
        coEvery { sessionRepo.archivedAllProfiles() } returns emptyList()
        vm.unarchive("a1")
        advanceUntilIdle()
        coVerify { sessionRepo.archive("a1", archived = false) }
        assertEquals(emptyList<String>(), vm.state.value.sessions.map { it.id })
    }

    @Test fun delete_removes_then_reloads() = runTest {
        coEvery { sessionRepo.archivedAllProfiles() } returns listOf(session("a1"))
        val vm = buildVm()
        advanceUntilIdle()
        coEvery { sessionRepo.archivedAllProfiles() } returns emptyList()
        vm.delete("a1")
        advanceUntilIdle()
        coVerify { sessionRepo.delete("a1") }
        assertEquals(emptyList<String>(), vm.state.value.sessions.map { it.id })
    }
}
