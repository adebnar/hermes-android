package com.hermes.client.ui.chat

import app.cash.turbine.test
import com.hermes.client.data.network.ConnectionState
import com.hermes.client.data.network.ProfileDto
import com.hermes.client.data.network.ServerEvent
import com.hermes.client.data.repository.ChatRepository
import com.hermes.client.data.repository.ModelFavoritesStore
import com.hermes.client.data.repository.ModelRepository
import com.hermes.client.data.repository.ProfileRepository
import com.hermes.client.data.repository.SessionRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    private val events = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 64)
    private val connectionStateFlow = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    private val chatRepo = mockk<ChatRepository>(relaxed = true)
    private val sessionRepo = mockk<SessionRepository>(relaxed = true)
    private val modelRepo = mockk<ModelRepository>(relaxed = true)
    private val profileRepo = mockk<ProfileRepository>(relaxed = true)
    private val profileManager = mockk<com.hermes.client.data.repository.ProfileManager>(relaxed = true)
    private val favoritesStore = mockk<ModelFavoritesStore>(relaxed = true)
    private val pendingShareStore = com.hermes.client.share.PendingShareStore()

    @Before fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        every { chatRepo.events } returns events
        every { chatRepo.connectionState } returns connectionStateFlow
        // resume returns null here so the ViewModel keeps the opened id stable for these tests
        // (production switches to the live handle resume returns).
        coEvery { chatRepo.resume(any(), any()) } returns null
        every { profileManager.active } returns MutableStateFlow<String?>(null)
        coEvery { sessionRepo.history(any(), any()) } returns emptyList()
        coEvery { modelRepo.options() } returns emptyList()
        coEvery { modelRepo.providers() } returns emptyList()
        coEvery { profileRepo.list() } returns emptyList()
        every { favoritesStore.favorites } returns MutableStateFlow(emptySet())
    }

    private fun buildVm() = ChatViewModel(chatRepo, sessionRepo, modelRepo, profileRepo, profileManager, favoritesStore, pendingShareStore)

    @Test fun streamed_delta_appears_in_state() = runTest {
        val vm = buildVm()
        vm.open("s1")
        advanceUntilIdle()
        vm.state.test {
            awaitItem() // initial empty (or current) state
            events.emit(ServerEvent("message.start", "s1", buildJsonObject { put("session_id", "s1") }))
            events.emit(ServerEvent("message.delta", "s1", buildJsonObject { put("session_id", "s1"); put("text", "Hi") }))
            advanceUntilIdle()
            val latest = expectMostRecentItem()
            assertEquals("Hi", latest.messages.last().text)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * C2: when connectionState transitions Reconnecting → Connected (not the first Connected),
     * chat.resume() must be called a second time to re-attach the agent stream.
     */
    @Test fun reconnect_triggers_second_resume() = runTest {
        val vm = buildVm()
        vm.open("s1")
        advanceUntilIdle()
        // open() already called resume once; now simulate a reconnect cycle
        connectionStateFlow.value = ConnectionState.Reconnecting
        advanceUntilIdle()
        connectionStateFlow.value = ConnectionState.Connected
        advanceUntilIdle()

        // resume must have been called exactly twice: once in open(), once on reconnect
        coVerify(exactly = 2) { chatRepo.resume("s1", null) }
    }

    /**
     * Profile bug: session-scoped WebSocket RPCs must carry the active profile, or the gateway
     * resolves session.resume against the wrong profile's DB and returns "session not found"
     * (4007) — which then makes the next prompt.submit fail too. open() must pass the active
     * profile to resume so a session that lives in a non-default profile can be reattached.
     */
    @Test fun open_resumes_with_active_profile() = runTest {
        every { profileManager.active } returns MutableStateFlow<String?>("personal")
        val vm = buildVm()
        vm.open("s1")
        advanceUntilIdle()
        coVerify { chatRepo.resume("s1", "personal") }
    }

    /**
     * A pending image share must be staged as a local attachment chip (not attached to the
     * gateway immediately) — it's flushed on the next send() using whatever the live
     * post-resume sessionId is at that time.
     */
    @Test fun open_stages_pending_share_image_as_attachment() = runTest {
        coEvery { chatRepo.resume("s1", null) } returns "s1-live"
        pendingShareStore.put(
            "s1",
            // Valid base64 (decodes to "abc") — real decode logic runs in unit tests.
            com.hermes.client.share.PendingShare(imageBase64 = "YWJj", imageMime = "image/png"),
        )
        val vm = buildVm()
        vm.open("s1")
        advanceUntilIdle()

        coVerify(exactly = 0) { chatRepo.attachImageBytes(any(), any(), any()) }
        assertEquals(1, vm.state.value.pendingAttachments.size)
        assertEquals("image/png", vm.state.value.pendingAttachments.first().mimeType)
    }

    /** A text-only pending share (no image) must not trigger an attach call at all. */
    @Test fun open_with_text_only_pending_share_does_not_attach_image() = runTest {
        pendingShareStore.put("s1", com.hermes.client.share.PendingShare(text = "hello"))
        val vm = buildVm()
        vm.open("s1")
        advanceUntilIdle()

        coVerify(exactly = 0) { chatRepo.attachImageBytes(any(), any(), any()) }
        assertEquals("hello", vm.initialDraft.value)
    }

    /**
     * I3: when connectionState enters Reconnecting while generation is in progress,
     * the in-flight assistant message must be marked interrupted and isGenerating cleared.
     */
    @Test fun reconnecting_while_generating_marks_interrupted() = runTest {
        val vm = buildVm()
        vm.open("s1")
        advanceUntilIdle()

        // Start a generation via message.start event
        events.emit(ServerEvent("message.start", "s1", buildJsonObject { put("message_id", "m1") }))
        advanceUntilIdle()
        assertTrue("should be generating after message.start", vm.state.value.isGenerating)

        // Simulate connection drop while generating
        connectionStateFlow.value = ConnectionState.Reconnecting
        advanceUntilIdle()

        val s = vm.state.value
        assertFalse("isGenerating should be cleared after Reconnecting", s.isGenerating)
        val lastMsg = s.messages.lastOrNull()
        assertTrue("last message should be marked interrupted", lastMsg?.interrupted == true)
    }

    /**
     * C2 edge case: the very first Connected (startup) must NOT trigger a second resume.
     */
    @Test fun initial_connected_does_not_double_resume() = runTest {
        val vm = buildVm()
        // Start with Connecting, then transition to Connected (first connect)
        connectionStateFlow.value = ConnectionState.Connecting
        vm.open("s1")
        advanceUntilIdle()
        connectionStateFlow.value = ConnectionState.Connected
        advanceUntilIdle()

        // Only one resume from open(); the Connected transition had prev==Connecting (not Reconnecting)
        coVerify(exactly = 1) { chatRepo.resume("s1", null) }
    }

    // Changing the model inside a chat (SESSION scope, the default) must switch THIS session's
    // model (a `/model … --session` slash), not the global default — otherwise a session pinned
    // to an unavailable model keeps failing with "model is not available in session" no matter
    // how often the picker is used.
    @Test fun onSelectFromSheet_session_success_switches_via_slash() = runTest {
        val vm = buildVm()
        vm.open("s1")
        advanceUntilIdle()

        var onDoneCalled = false
        vm.onSheetScope(com.hermes.client.ui.models.ModelScope.SESSION)
        vm.onSelectFromSheet("anthropic", "opus") { onDoneCalled = true }
        advanceUntilIdle()

        coVerify { chatRepo.slashExec("s1", "/model opus --provider anthropic --session") }
        assertEquals("opus", vm.currentModel.value)
        assertTrue("success must clear any sheet error", vm.modelSheet.value.error == null)
        assertTrue("onDone must be invoked so the caller dismisses the sheet", onDoneCalled)
    }

    // A worker failure ("slash worker closed pipe") throws — it must surface in the sheet's error
    // (not the chat transcript), and the sheet must stay open (onDone not invoked) so the user can
    // retry or pick a different model.
    @Test fun onSelectFromSheet_session_failure_surfaces_sheet_error() = runTest {
        coEvery { chatRepo.slashExec("s1", any()) } throws RuntimeException("slash worker closed pipe")
        val vm = buildVm()
        vm.open("s1"); advanceUntilIdle()

        var onDoneCalled = false
        vm.onSheetScope(com.hermes.client.ui.models.ModelScope.SESSION)
        vm.onSelectFromSheet("anthropic", "opus") { onDoneCalled = true }
        advanceUntilIdle()

        assertTrue("a failed switch must surface a sheet error", vm.modelSheet.value.error != null)
        assertFalse("the sheet must stay open on failure", onDoneCalled)
    }

    // DEFAULT scope sets the global default model via REST, not the session slash.
    @Test fun onSelectFromSheet_default_success_sets_default_model() = runTest {
        val vm = buildVm()
        vm.open("s1"); advanceUntilIdle()

        var onDoneCalled = false
        vm.onSheetScope(com.hermes.client.ui.models.ModelScope.DEFAULT)
        vm.onSelectFromSheet("anthropic", "opus") { onDoneCalled = true }
        advanceUntilIdle()

        coVerify { modelRepo.set("anthropic", "opus") }
        assertTrue("success must clear any sheet error", vm.modelSheet.value.error == null)
        assertTrue("onDone must be invoked so the caller dismisses the sheet", onDoneCalled)
    }

    @Test fun onSelectFromSheet_default_failure_surfaces_sheet_error() = runTest {
        coEvery { modelRepo.set(any(), any()) } throws RuntimeException("could not set default")
        val vm = buildVm()
        vm.open("s1"); advanceUntilIdle()

        var onDoneCalled = false
        vm.onSheetScope(com.hermes.client.ui.models.ModelScope.DEFAULT)
        vm.onSelectFromSheet("anthropic", "opus") { onDoneCalled = true }
        advanceUntilIdle()

        assertTrue("a failed default switch must surface a sheet error", vm.modelSheet.value.error != null)
        assertFalse("the sheet must stay open on failure", onDoneCalled)
    }

    @Test fun selectProfile_calls_profileRepo_setActive() = runTest {
        val vm = buildVm()
        vm.open("s1")
        advanceUntilIdle()

        vm.selectProfile("personal")
        advanceUntilIdle()

        coVerify { profileRepo.setActive("personal") }
    }
}
