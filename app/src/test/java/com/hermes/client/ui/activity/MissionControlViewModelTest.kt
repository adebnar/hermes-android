package com.hermes.client.ui.activity

import com.hermes.client.data.repository.ProfileManager
import com.hermes.client.data.repository.SessionRepository
import com.hermes.client.data.repository.ToolsRepository
import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Role
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MissionControlViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val sessions = mockk<SessionRepository>()
    private val tools = mockk<ToolsRepository>(relaxed = true)
    private val profileManager = mockk<ProfileManager>(relaxed = true)

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun vm() = MissionControlViewModel(sessions, tools, profileManager)

    @Test fun loadResponse_sets_text_on_success() = runTest(dispatcher) {
        coEvery { sessions.history("s1", any()) } returns listOf(ChatMessage("m", Role.ASSISTANT, "the answer"))
        val vm = vm()
        vm.loadResponse("s1")
        advanceUntilIdle()
        assertEquals("the answer", vm.responses.value["s1"]?.text)
    }

    @Test fun loadResponse_sets_error_on_failure() = runTest(dispatcher) {
        coEvery { sessions.history("s1", any()) } throws RuntimeException("boom")
        val vm = vm()
        vm.loadResponse("s1")
        advanceUntilIdle()
        assertTrue(vm.responses.value["s1"]?.error == true)
    }

    @Test fun loadResponse_caches_and_does_not_refetch() = runTest(dispatcher) {
        coEvery { sessions.history("s1", any()) } returns listOf(ChatMessage("m", Role.ASSISTANT, "x"))
        val vm = vm()
        vm.loadResponse("s1"); advanceUntilIdle()
        vm.loadResponse("s1"); advanceUntilIdle()
        coVerify(exactly = 1) { sessions.history("s1", any()) }
    }

    @Test fun runCron_success_triggers_and_reloads() = runTest(dispatcher) {
        coEvery { tools.triggerCron("j1", any()) } returns Unit
        coEvery { sessions.activityFeed() } returns emptyList()
        coEvery { tools.cronJobs(any()) } returns emptyList()
        val vm = vm()
        val ok = vm.runCron("j1")
        advanceUntilIdle()
        assertTrue(ok)
        coVerify { tools.triggerCron("j1", any()) }
        coVerify(atLeast = 1) { sessions.activityFeed() }   // reload happened
    }

    @Test fun runCron_failure_returns_false() = runTest(dispatcher) {
        coEvery { tools.triggerCron("j1", any()) } throws RuntimeException("boom")
        val vm = vm()
        assertFalse(vm.runCron("j1"))
    }
}
