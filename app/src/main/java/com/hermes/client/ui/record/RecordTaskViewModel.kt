package com.hermes.client.ui.record

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.audio.AudioRecorder
import com.hermes.client.data.audio.audioDataUrl
import com.hermes.client.data.network.HermesRestApi
import com.hermes.client.data.repository.ChatRepository
import com.hermes.client.data.repository.ProfileManager
import com.hermes.client.share.PendingShare
import com.hermes.client.share.PendingShareStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class RecordPhase { IDLE, RECORDING, TRANSCRIBING }

data class RecordUi(val phase: RecordPhase = RecordPhase.IDLE, val error: String? = null)

/**
 * Orchestrates the record -> transcribe -> new prefilled chat flow: capture a voice note,
 * send it to the gateway's transcribe endpoint, create a fresh session for the active profile,
 * stash the transcript as a [PendingShare] keyed by the new session id, and signal navigation
 * to it via [navigateTo].
 *
 * The primary constructor takes function-typed collaborators (rather than the concrete
 * [HermesRestApi]/[ChatRepository] types) so this class is unit-testable with fakes; the
 * secondary `@Inject` constructor adapts the real dependencies to those function shapes.
 */
@HiltViewModel
class RecordTaskViewModel(
    private val recorder: AudioRecorder,
    private val transcribe: suspend (dataUrl: String, mime: String) -> String,
    private val createSession: suspend (profile: String?) -> String,
    private val activeProfile: StateFlow<String?>,
    private val refreshProfiles: suspend () -> Unit,
    private val pendingShareStore: PendingShareStore,
    // Where the blocking MediaRecorder start()/stop() calls run. Defaults to the real IO
    // dispatcher in production; tests override it with their TestDispatcher so
    // advanceUntilIdle() can deterministically drive the recorder calls (Dispatchers.IO is a
    // real thread pool the test scheduler has no visibility into).
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    @Inject constructor(
        recorder: AudioRecorder,
        api: HermesRestApi,
        chat: ChatRepository,
        profileManager: ProfileManager,
        pendingShareStore: PendingShareStore,
    ) : this(
        recorder = recorder,
        transcribe = { url, mime -> api.transcribe(url, mime) },
        createSession = { profile -> chat.connect(); chat.createSession(profile) },
        activeProfile = profileManager.active,
        refreshProfiles = { profileManager.refresh() },
        pendingShareStore = pendingShareStore,
    )

    private val _ui = MutableStateFlow(RecordUi())
    val ui: StateFlow<RecordUi> = _ui.asStateFlow()

    private val _navigateTo = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val navigateTo: SharedFlow<String> = _navigateTo.asSharedFlow()

    // Tracks the most recently launched pipeline coroutine so cancel() can abort it before its
    // (async, IO-dispatched) work has a chance to write a stale phase over IDLE.
    private var activeJob: Job? = null

    // Tracks the in-flight recorder.start() coroutine so stopAndTranscribe() can wait for it to
    // finish before calling recorder.stop() - otherwise a stop() that lands during the start
    // window could run before start() ever executed, stranding the recorder state.
    private var startJob: Job? = null

    fun startRecording() {
        if (_ui.value.phase != RecordPhase.IDLE) return
        // Set RECORDING synchronously (not after the IO hop) so a stop tap that races the start
        // call sees the correct phase immediately instead of IDLE.
        _ui.value = RecordUi(RecordPhase.RECORDING)
        startJob = viewModelScope.launch {
            try {
                withContext(ioDispatcher) { recorder.start() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.value = RecordUi(RecordPhase.IDLE, error = "Couldn't start recording")
            }
        }
        activeJob = startJob
    }

    fun stopAndTranscribe() {
        // Guards both a double stop-tap (second call sees TRANSCRIBING/IDLE, not RECORDING) and
        // a stop with nothing recording. TRANSCRIBING is set synchronously below so a
        // back-to-back second call is rejected here even before the first call's coroutine runs.
        if (_ui.value.phase != RecordPhase.RECORDING) return
        _ui.value = RecordUi(RecordPhase.TRANSCRIBING)
        activeJob = viewModelScope.launch {
            try {
                // Never let stop() precede a still-running start() - wait for it first.
                startJob?.join()
                // Recorder stop + base64 encoding are both blocking/CPU work; keep them off Main.
                val encoded = withContext(ioDispatcher) {
                    val clip = recorder.stop()
                    clip?.let { audioDataUrl(it.bytes, it.mime) to it.mime }
                }
                if (encoded == null) {
                    _ui.value = RecordUi(RecordPhase.IDLE, error = "Nothing recorded")
                    return@launch
                }
                val (dataUrl, mime) = encoded
                val text = transcribe(dataUrl, mime).trim()
                if (text.isBlank()) {
                    _ui.value = RecordUi(RecordPhase.IDLE, error = "Couldn't transcribe that")
                    return@launch
                }
                refreshProfiles()
                val id = createSession(activeProfile.value)
                pendingShareStore.put(id, PendingShare(text = text))
                _navigateTo.emit(id)
                _ui.value = RecordUi(RecordPhase.IDLE)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.value = RecordUi(RecordPhase.IDLE, error = "Transcription failed")
            }
        }
    }

    fun cancel() {
        activeJob?.cancel()
        recorder.cancel()
        _ui.value = RecordUi(RecordPhase.IDLE)
    }

    fun dismissError() {
        if (_ui.value.error != null) _ui.value = _ui.value.copy(error = null)
    }
}
