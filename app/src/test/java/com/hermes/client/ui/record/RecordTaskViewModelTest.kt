package com.hermes.client.ui.record

import com.hermes.client.data.audio.AudioRecorder
import com.hermes.client.data.audio.Recording
import com.hermes.client.share.PendingShare
import com.hermes.client.share.PendingShareStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecordTaskViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private class FakeRecorder(var result: Recording?) : AudioRecorder {
        var started = false; var cancelled = false
        override fun start() { started = true }
        override fun stop() = result
        override fun cancel() { cancelled = true }
    }

    private fun vm(
        recorder: AudioRecorder,
        transcribe: suspend (String, String) -> String = { _, _ -> "hi" },
        createSession: suspend (String?) -> String = { "sess-1" },
        store: PendingShareStore = PendingShareStore(),
        refresh: suspend () -> Unit = {},
    ) = RecordTaskViewModel(
        recorder = recorder,
        transcribe = transcribe,
        createSession = createSession,
        activeProfile = MutableStateFlow<String?>("personal"),
        refreshProfiles = refresh,
        pendingShareStore = store,
        // Route the recorder's blocking-call dispatch through the same TestDispatcher as this
        // test's scheduler, so advanceUntilIdle() deterministically drives it (Dispatchers.IO,
        // the production default, is a real thread pool the test scheduler can't see).
        ioDispatcher = dispatcher,
    )

    @Test fun happy_path_transcribes_creates_session_and_stashes_prefill() = runTest {
        val store = PendingShareStore()
        val nav = mutableListOf<String>()
        val model = vm(FakeRecorder(Recording(byteArrayOf(1,2,3), "audio/mp4")),
            transcribe = { _, _ -> "  book the flight  " }, createSession = { "sess-1" }, store = store)
        val job = CoroutineScope(dispatcher).launch { model.navigateTo.collect { nav.add(it) } }
        model.startRecording(); advanceUntilIdle()
        assertEquals(RecordPhase.RECORDING, model.ui.value.phase)
        model.stopAndTranscribe(); advanceUntilIdle()
        assertEquals(listOf("sess-1"), nav)
        assertEquals("book the flight", store.take("sess-1")?.text)
        assertEquals(RecordPhase.IDLE, model.ui.value.phase)
        assertNull(model.ui.value.error)
        job.cancel()
    }

    @Test fun nothing_recorded_sets_error_and_skips_transcribe() = runTest {
        var called = false
        val model = vm(FakeRecorder(null), transcribe = { _, _ -> called = true; "x" })
        model.startRecording(); model.stopAndTranscribe(); advanceUntilIdle()
        assertEquals(false, called)
        assertEquals(RecordPhase.IDLE, model.ui.value.phase)
        org.junit.Assert.assertNotNull(model.ui.value.error)
    }

    @Test fun blank_transcript_sets_error_and_creates_no_session() = runTest {
        var created = false
        val model = vm(FakeRecorder(Recording(byteArrayOf(1), "audio/mp4")),
            transcribe = { _, _ -> "   " }, createSession = { created = true; "s" })
        model.startRecording(); model.stopAndTranscribe(); advanceUntilIdle()
        assertEquals(false, created)
        org.junit.Assert.assertNotNull(model.ui.value.error)
    }

    @Test fun transcribe_failure_sets_error() = runTest {
        val model = vm(FakeRecorder(Recording(byteArrayOf(1), "audio/mp4")),
            transcribe = { _, _ -> throw RuntimeException("boom") })
        model.startRecording(); model.stopAndTranscribe(); advanceUntilIdle()
        org.junit.Assert.assertNotNull(model.ui.value.error)
        assertEquals(RecordPhase.IDLE, model.ui.value.phase)
    }

    @Test fun cancel_stops_recorder_and_returns_idle() = runTest {
        val rec = FakeRecorder(Recording(byteArrayOf(1), "audio/mp4"))
        val model = vm(rec)
        model.startRecording(); model.cancel(); advanceUntilIdle()
        assertEquals(true, rec.cancelled)
        assertEquals(RecordPhase.IDLE, model.ui.value.phase)
    }

    @Test fun double_stop_does_not_clobber_transcribing() = runTest {
        val nav = mutableListOf<String>()
        var sessionCount = 0
        val model = vm(
            FakeRecorder(Recording(byteArrayOf(1, 2, 3), "audio/mp4")),
            transcribe = { _, _ -> "book the flight" },
            createSession = { sessionCount++; "sess-1" },
        )
        val job = CoroutineScope(dispatcher).launch { model.navigateTo.collect { nav.add(it) } }
        model.startRecording()
        // Two stop taps back-to-back, before the scheduler runs either coroutine body: the
        // second call must be rejected by the synchronous TRANSCRIBING guard, not race the first.
        model.stopAndTranscribe()
        model.stopAndTranscribe()
        advanceUntilIdle()
        assertEquals(1, sessionCount)
        assertEquals(listOf("sess-1"), nav)
        assertEquals(RecordPhase.IDLE, model.ui.value.phase)
        assertNull(model.ui.value.error)
        job.cancel()
    }
}
