# Record-to-Task (v2) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Steps use checkbox syntax.

**Goal:** Record a spoken task → transcribe it via the gateway → open a new chat prefilled with the transcript.

**Architecture:** A framework `MediaRecorder` (behind an `AudioRecorder` interface) captures `audio/mp4`; a pure `audioDataUrl` encodes it; `HermesRestApi.transcribe` POSTs to the existing `/api/audio/transcribe`; `RecordTaskViewModel` orchestrates record→transcribe→create-session→prefill; a `RecordTaskSheet` on the home session list drives it. Prefill reuses the existing `PendingShareStore` share rail.

**Tech Stack:** Kotlin, Compose/Material3, Hilt, OkHttp (hand-rolled), kotlinx.serialization, `MediaRecorder`, `java.util.Base64`.

## Global Constraints
- Client-only; no gateway change. Gateway must have an STT backend — the app surfaces its absence as an error, never crashes.
- Kotlin/Compose/Material3/Hilt. Per-tenant accent (`LocalProfileAccent`) for chrome/active affordances; semantic `colorScheme.error` for error states — never the accent for errors.
- No AI/assistant attribution in commits, files, or PRs.
- Build env: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`. Gates per task: `:app:compileDebugKotlin`, `:app:testDebugUnitTest`, `:app:assembleBeta`.
- Branch `feature/record-to-task` (off `dev`). gitleaks before push; PR into `dev`.
- `runCatching` blocks must rethrow `kotlinx.coroutines.CancellationException` (repo convention — see `SessionsViewModel.createSession`).

---

### Task 1: `HermesRestApi.transcribe` + MockWebServer test

**Files:**
- Modify: `app/src/main/java/com/hermes/client/data/network/HermesRestApi.kt`
- Test: `app/src/test/java/com/hermes/client/data/network/HermesRestApiTranscribeTest.kt`

**Interfaces:**
- Produces: `suspend fun HermesRestApi.transcribe(dataUrl: String, mimeType: String): String` — returns the trimmed transcript (may be `""`); throws `HermesApiException(code, …)` on a non-2xx response.

- [ ] **Step 1: Write the failing test.** Create `HermesRestApiTranscribeTest.kt` (mirror `HermesRestApiTest.kt`'s `MockWebServerRule` + `api(server)` helper):
```kotlin
package com.hermes.client.data.network

import com.hermes.client.data.auth.GatewayConfig
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.junit4.MockWebServerRule
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class HermesRestApiTranscribeTest {
    @get:Rule val serverRule = MockWebServerRule()
    private val json = Json { ignoreUnknownKeys = true }

    private fun api(server: MockWebServer) = HermesRestApi(OkHttpClient(), json) {
        GatewayConfig(baseUrl = server.url("/").toString().trimEnd('/'), token = "secret")
    }

    @Test fun transcribe_returns_trimmed_transcript_and_posts_data_url() = runTest {
        serverRule.server.enqueue(MockResponse.Builder().code(200).body(
            """{"ok":true,"transcript":"  book the flight  ","provider":"local"}"""
        ).build())

        val text = api(serverRule.server).transcribe("data:audio/mp4;base64,AAA", "audio/mp4")
        assertEquals("book the flight", text)

        val recorded = serverRule.server.takeRequest()
        assertEquals("/api/audio/transcribe", recorded.target)
        assertEquals("secret", recorded.headers["X-Hermes-Session-Token"])
        val sent = recorded.body?.utf8().orEmpty()
        assertTrue(sent.contains("\"data_url\":\"data:audio/mp4;base64,AAA\""))
        assertTrue(sent.contains("\"mime_type\":\"audio/mp4\""))
    }

    @Test fun transcribe_blank_transcript_returns_empty() = runTest {
        serverRule.server.enqueue(MockResponse.Builder().code(200).body("""{"ok":true}""").build())
        assertEquals("", api(serverRule.server).transcribe("data:audio/mp4;base64,AAA", "audio/mp4"))
    }

    @Test fun transcribe_error_throws() = runTest {
        serverRule.server.enqueue(MockResponse.Builder().code(400).body("""{"detail":"no stt"}""").build())
        try {
            api(serverRule.server).transcribe("data:audio/mp4;base64,AAA", "audio/mp4")
            org.junit.Assert.fail("expected HermesApiException")
        } catch (e: HermesApiException) {
            assertEquals(400, e.code)
        }
    }
}
```
(If `recorded.body?.utf8()` differs from the repo's recorded-body accessor, match however `HermesRestApiTest` reads a POST body; the assertions are the contract. If no POST-body test exists there, `recorded.body?.utf8()` is correct for `mockwebserver3`.)

- [ ] **Step 2: Run → FAIL** (unresolved `transcribe`):
`./gradlew :app:testDebugUnitTest --tests "com.hermes.client.data.network.HermesRestApiTranscribeTest"`

- [ ] **Step 3: Implement.** In `HermesRestApi.kt`, add (next to `revealEnv`):
```kotlin
    /**
     * Transcribe a recorded voice note. [dataUrl] is a base64 data URL (data:<mime>;base64,<b64>)
     * the gateway's POST /api/audio/transcribe accepts; returns the trimmed transcript ("" if the
     * STT backend returned nothing). Throws HermesApiException on a non-2xx (e.g. no STT configured).
     */
    suspend fun transcribe(dataUrl: String, mimeType: String): String = withContext(Dispatchers.IO) {
        val obj = buildJsonObject { put("data_url", dataUrl); put("mime_type", mimeType) }
        val payload = json.encodeToString(JsonObject.serializer(), obj)
            .toRequestBody("application/json".toMediaType())
        okHttp.newCall(builder("/api/audio/transcribe").post(payload).build()).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw HermesApiException(resp.code, "transcription failed")
            json.decodeFromString<JsonObject>(body)["transcript"]?.jsonPrimitive?.content?.trim() ?: ""
        }
    }
```
(If `encodeToString(JsonObject.serializer(), obj)` isn't the form used nearby, match `revealEnv`'s exact encode call. `JsonObject.serializer()` + `jsonPrimitive` are already imported.)

- [ ] **Step 4: Run → PASS** (all 3 tests).

- [ ] **Step 5: Commit**
```bash
git add app/src/main/java/com/hermes/client/data/network/HermesRestApi.kt \
        app/src/test/java/com/hermes/client/data/network/HermesRestApiTranscribeTest.kt
git commit -m "feat: add transcribe() REST call for /api/audio/transcribe"
```

---

### Task 2: `audioDataUrl` pure encoder + RECORD_AUDIO manifest permission

**Files:**
- Create: `app/src/main/java/com/hermes/client/data/audio/AudioDataUrl.kt`
- Test: `app/src/test/java/com/hermes/client/data/audio/AudioDataUrlTest.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Produces: `fun audioDataUrl(bytes: ByteArray, mime: String): String`.

- [ ] **Step 1: Write the failing test.** Create `AudioDataUrlTest.kt`:
```kotlin
package com.hermes.client.data.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioDataUrlTest {
    @Test fun builds_base64_data_url_with_mime_prefix() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val url = audioDataUrl(bytes, "audio/mp4")
        assertTrue(url.startsWith("data:audio/mp4;base64,"))
        val b64 = url.removePrefix("data:audio/mp4;base64,")
        assertEquals(bytes.toList(), java.util.Base64.getDecoder().decode(b64).toList())
    }

    @Test fun empty_bytes_still_valid() {
        assertEquals("data:audio/mp4;base64,", audioDataUrl(ByteArray(0), "audio/mp4"))
    }
}
```

- [ ] **Step 2: Run → FAIL:** `./gradlew :app:testDebugUnitTest --tests "com.hermes.client.data.audio.AudioDataUrlTest"`

- [ ] **Step 3: Implement.** Create `AudioDataUrl.kt`:
```kotlin
package com.hermes.client.data.audio

/** Build a base64 data URL the gateway's transcribe endpoint accepts: data:<mime>;base64,<b64>. */
fun audioDataUrl(bytes: ByteArray, mime: String): String =
    "data:$mime;base64," + java.util.Base64.getEncoder().encodeToString(bytes)
```

- [ ] **Step 4: Run → PASS.**

- [ ] **Step 5: Add the permission.** In `AndroidManifest.xml`, add alongside the existing `<uses-permission>` lines:
```xml
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
```

- [ ] **Step 6: Compile check** (manifest merges): `./gradlew :app:compileDebugKotlin`

- [ ] **Step 7: Commit**
```bash
git add app/src/main/java/com/hermes/client/data/audio/AudioDataUrl.kt \
        app/src/test/java/com/hermes/client/data/audio/AudioDataUrlTest.kt \
        app/src/main/AndroidManifest.xml
git commit -m "feat: audio data-url encoder + RECORD_AUDIO permission"
```

---

### Task 3: `AudioRecorder` interface + `MediaAudioRecorder` + Hilt provider

**Files:**
- Create: `app/src/main/java/com/hermes/client/data/audio/AudioRecorder.kt`
- Modify: `app/src/main/java/com/hermes/client/di/AppModule.kt`

**Interfaces:**
- Produces: `interface AudioRecorder { fun start(); fun stop(): Recording?; fun cancel() }` and `data class Recording(val bytes: ByteArray, val mime: String)`.
- Consumes: nothing from prior tasks.

No unit test — `MediaRecorder` is device-bound (covered on-device). This task's gate is compile + the ViewModel test in Task 4 exercising a fake `AudioRecorder`.

- [ ] **Step 1: Implement.** Create `AudioRecorder.kt`:
```kotlin
package com.hermes.client.data.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/** A captured voice note. */
data class Recording(val bytes: ByteArray, val mime: String) {
    override fun equals(other: Any?) =
        other is Recording && mime == other.mime && bytes.contentEquals(other.bytes)
    override fun hashCode() = 31 * bytes.contentHashCode() + mime.hashCode()
}

/** Records a single voice note. Interface so RecordTaskViewModel is testable with a fake. */
interface AudioRecorder {
    fun start()
    fun stop(): Recording?
    fun cancel()
}

/** MediaRecorder-backed recorder writing audio/mp4 (AAC) to an app-cache temp file. */
class MediaAudioRecorder(private val context: Context) : AudioRecorder {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    override fun start() {
        if (recorder != null) return
        val file = File.createTempFile("rec_", ".m4a", context.cacheDir)
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context)
                  else @Suppress("DEPRECATION") MediaRecorder()
        rec.setAudioSource(MediaRecorder.AudioSource.MIC)
        rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        rec.setAudioEncodingBitRate(96_000)
        rec.setAudioSamplingRate(44_100)
        rec.setOutputFile(file.absolutePath)
        rec.prepare()
        rec.start()
        recorder = rec
        outputFile = file
    }

    override fun stop(): Recording? {
        val rec = recorder ?: return null
        val file = outputFile
        recorder = null
        outputFile = null
        val stopped = runCatching { rec.stop() }.isSuccess
        runCatching { rec.release() }
        if (!stopped || file == null || !file.exists() || file.length() == 0L) {
            file?.delete()
            return null
        }
        val bytes = file.readBytes()
        file.delete()
        return Recording(bytes, "audio/mp4")
    }

    override fun cancel() {
        val rec = recorder ?: return
        val file = outputFile
        recorder = null
        outputFile = null
        runCatching { rec.stop() }
        runCatching { rec.release() }
        file?.delete()
    }
}
```

- [ ] **Step 2: Provide via Hilt.** In `AppModule.kt`, add a provider (match the module's existing `@Provides`/`@Singleton` + `@ApplicationContext` style):
```kotlin
    @Provides
    @Singleton
    fun provideAudioRecorder(
        @dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context,
    ): com.hermes.client.data.audio.AudioRecorder =
        com.hermes.client.data.audio.MediaAudioRecorder(context)
```
(If `AppModule` already imports `@ApplicationContext`/`Context`/`Singleton`, use the short names to match the file.)

- [ ] **Step 3: Compile:** `./gradlew :app:compileDebugKotlin` → SUCCESSFUL.

- [ ] **Step 4: Commit**
```bash
git add app/src/main/java/com/hermes/client/data/audio/AudioRecorder.kt \
        app/src/main/java/com/hermes/client/di/AppModule.kt
git commit -m "feat: MediaRecorder-backed AudioRecorder + Hilt provider"
```

---

### Task 4: `RecordTaskViewModel` + orchestration test

**Files:**
- Create: `app/src/main/java/com/hermes/client/ui/record/RecordTaskViewModel.kt`
- Test: `app/src/test/java/com/hermes/client/ui/record/RecordTaskViewModelTest.kt`

**Interfaces:**
- Consumes: `AudioRecorder`/`Recording` (Task 3), `HermesRestApi.transcribe` (Task 1), `audioDataUrl` (Task 2), `ChatRepository.createSession(profile: String?): String`, `ProfileManager.active: StateFlow<String?>` + `refresh()`, `PendingShareStore.put(id, PendingShare(text=…))`.
- Produces: `RecordTaskViewModel` with `ui: StateFlow<RecordUi>`, `navigateTo: SharedFlow<String>`, and `startRecording()`, `stopAndTranscribe()`, `cancel()`, `dismissError()`.

- [ ] **Step 1: Write the failing test.** Create `RecordTaskViewModelTest.kt`. Fakes implement the real interfaces; `HermesRestApi` and `ChatRepository` are open enough to fake via their public methods — if a class is `final`, wrap the two calls the VM needs behind small interfaces is OUT OF SCOPE; instead construct real instances with fakes is OUT OF SCOPE. Use these fakes (the VM must take its collaborators as constructor params typed to allow these fakes — see Step 3; if `HermesRestApi`/`ChatRepository` are final classes, the VM takes function-typed params `transcribe: suspend (String,String)->String` and `createSession: suspend (String?)->String` instead, and the real wiring passes `api::transcribe` / `chat::createSession`):
```kotlin
package com.hermes.client.ui.record

import com.hermes.client.data.audio.AudioRecorder
import com.hermes.client.data.audio.Recording
import com.hermes.client.share.PendingShare
import com.hermes.client.share.PendingShareStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
    )

    @Test fun happy_path_transcribes_creates_session_and_stashes_prefill() = runTest {
        val store = PendingShareStore()
        val nav = mutableListOf<String>()
        val model = vm(FakeRecorder(Recording(byteArrayOf(1,2,3), "audio/mp4")),
            transcribe = { _, _ -> "  book the flight  " }, createSession = { "sess-1" }, store = store)
        val job = kotlinx.coroutines.CoroutineScope(dispatcher).launch { model.navigateTo.collect { nav.add(it) } }
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
}
```
(Import `kotlinx.coroutines.launch`/`CoroutineScope` as needed. If the repo has an existing `MainDispatcherRule`, use it instead of the manual `setMain`/`resetMain` — match the sibling ViewModel tests.)

- [ ] **Step 2: Run → FAIL:** `./gradlew :app:testDebugUnitTest --tests "com.hermes.client.ui.record.RecordTaskViewModelTest"`

- [ ] **Step 3: Implement.** Create `RecordTaskViewModel.kt`. Use function-typed collaborators for `transcribe`/`createSession` so the VM is unit-testable and Hilt wiring binds the real methods:
```kotlin
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class RecordPhase { IDLE, RECORDING, TRANSCRIBING }

data class RecordUi(val phase: RecordPhase = RecordPhase.IDLE, val error: String? = null)

class RecordTaskViewModel(
    private val recorder: AudioRecorder,
    private val transcribe: suspend (dataUrl: String, mime: String) -> String,
    private val createSession: suspend (profile: String?) -> String,
    private val activeProfile: StateFlow<String?>,
    private val refreshProfiles: suspend () -> Unit,
    private val pendingShareStore: PendingShareStore,
) : ViewModel() {

    private val _ui = MutableStateFlow(RecordUi())
    val ui: StateFlow<RecordUi> = _ui.asStateFlow()

    private val _navigateTo = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val navigateTo: SharedFlow<String> = _navigateTo.asSharedFlow()

    fun startRecording() {
        if (_ui.value.phase != RecordPhase.IDLE) return
        runCatching { recorder.start() }
            .onSuccess { _ui.value = RecordUi(RecordPhase.RECORDING) }
            .onFailure { _ui.value = RecordUi(RecordPhase.IDLE, error = "Couldn't start recording") }
    }

    fun stopAndTranscribe() {
        if (_ui.value.phase != RecordPhase.RECORDING) return
        val clip = recorder.stop()
        if (clip == null) {
            _ui.value = RecordUi(RecordPhase.IDLE, error = "Nothing recorded")
            return
        }
        _ui.value = RecordUi(RecordPhase.TRANSCRIBING)
        viewModelScope.launch {
            try {
                val text = transcribe(audioDataUrl(clip.bytes, clip.mime), clip.mime)
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
        recorder.cancel()
        _ui.value = RecordUi(RecordPhase.IDLE)
    }

    fun dismissError() { if (_ui.value.phase == RecordPhase.IDLE) _ui.value = RecordUi(RecordPhase.IDLE) }
}

/** Hilt factory binding the function-typed collaborators to the real repositories. */
@HiltViewModel
class RecordTaskViewModelHilt @Inject constructor(
    recorder: AudioRecorder,
    api: HermesRestApi,
    chat: ChatRepository,
    profileManager: ProfileManager,
    pendingShareStore: PendingShareStore,
) : ViewModel() {
    val delegate = RecordTaskViewModel(
        recorder = recorder,
        transcribe = { url, mime -> api.transcribe(url, mime) },
        createSession = { profile -> chat.connect(); chat.createSession(profile) },
        activeProfile = profileManager.active,
        refreshProfiles = { profileManager.refresh() },
        pendingShareStore = pendingShareStore,
    )
}
```
NOTE for the implementer: the `RecordTaskViewModelHilt` wrapper above is a starting point — if the codebase's `hiltViewModel()` call sites expect a single VM, prefer making `RecordTaskViewModel` itself the `@HiltViewModel` with an `@Inject constructor` that takes the real `HermesRestApi`/`ChatRepository`/`ProfileManager` and adapts them internally to the function types (keep a **second, non-Hilt** constructor — or a test-only secondary constructor — that takes the function-typed params for the unit test). Choose whichever matches the repo's other ViewModels; the unit test in Step 1 only requires that a `RecordTaskViewModel` can be built from the fakes/function-types shown. Confirm `ChatRepository.connect()` exists (it's used by `SessionsViewModel`/`MainActivity`); if `createSession` already connects, drop the `chat.connect()`.

- [ ] **Step 4: Run → PASS** (all VM tests).
- [ ] **Step 5: Full gates:** `:app:compileDebugKotlin`, `:app:testDebugUnitTest` (0 failures), `:app:assembleBeta` — all SUCCESSFUL.
- [ ] **Step 6: Commit**
```bash
git add app/src/main/java/com/hermes/client/ui/record/RecordTaskViewModel.kt \
        app/src/test/java/com/hermes/client/ui/record/RecordTaskViewModelTest.kt
git commit -m "feat: RecordTaskViewModel orchestrating record→transcribe→new prefilled chat"
```

---

### Task 5: `RecordTaskSheet` + SessionsScreen mic action + RECORD_AUDIO request

**Files:**
- Create: `app/src/main/java/com/hermes/client/ui/record/RecordTaskSheet.kt`
- Modify: `app/src/main/java/com/hermes/client/ui/sessions/SessionsScreen.kt`

**Interfaces:**
- Consumes: `RecordTaskViewModel` (`ui`, `navigateTo`, `startRecording`, `stopAndTranscribe`, `cancel`, `dismissError`), `RecordPhase`, `RecordUi`; `SessionsScreen`'s existing `onOpen: (String) -> Unit`.

Compose glue — no unit test; gate is compile + assembleBeta + on-device (best-effort).

- [ ] **Step 1: Create `RecordTaskSheet.kt`.** A `ModalBottomSheet` rendering `RecordUi`. Use `LocalProfileAccent` for the record/active button (match how other sheets read the accent — grep `LocalProfileAccent` in the repo); `MaterialTheme.colorScheme.error` for the error text. Contract:
```kotlin
@androidx.compose.material3.ExperimentalMaterial3Api
@androidx.compose.runtime.Composable
fun RecordTaskSheet(
    ui: RecordUi,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
)
```
Body: a `ModalBottomSheet(onDismissRequest = onDismiss)` with a centered column:
- `ui.error != null` → the error text (`color = MaterialTheme.colorScheme.error`) + a "Try again" button (`onRetry`) + a "Close" text button (`onDismiss`).
- else by `ui.phase`:
  - `RECORDING` → a large filled circular record indicator (accent), a caption "Recording…", a filled **Stop** button (`onStop`) and a text **Cancel** (`onCancel`).
  - `TRANSCRIBING` → a `CircularProgressIndicator` + "Transcribing…".
  - `IDLE` → a caption "Getting ready…" (transient; the host starts recording on open). 
Keep it small and dependency-free (Material3 only). Provide `contentDescription`s.

- [ ] **Step 2: Wire SessionsScreen.** In `SessionsScreen.kt`:
  1. Get the VM: `val recordVm: RecordTaskViewModel = hiltViewModel()` (match the file's other `hiltViewModel()` usage; if using the Hilt-wrapper pattern from Task 4, expose `.delegate`).
  2. State + permission launcher near the top of the composable:
```kotlin
    var showRecord by rememberSaveable { mutableStateOf(false) }
    val recordUi by recordVm.ui.collectAsStateWithLifecycle()
    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) { showRecord = true; recordVm.startRecording() }
        else android.widget.Toast.makeText(context,
            "Microphone needed to record a task", android.widget.Toast.LENGTH_SHORT).show()
    }
    fun onMicTap() {
        val ctx = context
        if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            showRecord = true; recordVm.startRecording()
        } else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }
    LaunchedEffect(Unit) { recordVm.navigateTo.collect { showRecord = false; onOpen(it) } }
```
   (Use the file's existing `context`/`LocalContext` handle; add imports: `androidx.activity.compose.rememberLauncherForActivityResult`, `androidx.activity.result.contract.ActivityResultContracts`, `android.Manifest`, `androidx.lifecycle.compose.collectAsStateWithLifecycle`, `androidx.hilt.navigation.compose.hiltViewModel` — match repo conventions.)
  3. Add a **mic** `IconButton` to the `TopAppBar` `actions` (the block at ~line 98, beside the archived action):
```kotlin
                        IconButton(onClick = { onMicTap() }) {
                            Icon(Icons.Rounded.Mic, contentDescription = "Record a task")
                        }
```
   (Import `androidx.compose.material.icons.rounded.Mic` + `Icon` if not already present.)
  4. Host the sheet (near the FAB / end of the Scaffold content):
```kotlin
    if (showRecord) {
        RecordTaskSheet(
            ui = recordUi,
            onStop = { recordVm.stopAndTranscribe() },
            onCancel = { recordVm.cancel(); showRecord = false },
            onRetry = { recordVm.dismissError(); recordVm.startRecording() },
            onDismiss = {
                if (recordUi.phase == RecordPhase.RECORDING) recordVm.cancel()
                recordVm.dismissError(); showRecord = false
            },
        )
    }
```

- [ ] **Step 3: Gates:** `:app:compileDebugKotlin`, `:app:testDebugUnitTest` (0 failures), `:app:assembleBeta` — all SUCCESSFUL.

- [ ] **Step 4: On-device (best-effort).** `:app:installBeta`. Home → tap the mic action → grant RECORD_AUDIO → sheet shows "Recording…" → speak → **Stop** → "Transcribing…" → a new chat opens with the transcript in the composer. If the gateway has no STT, confirm the sheet shows an error and no chat is created (no crash). Record pass/fail + the STT caveat in the PR.

- [ ] **Step 5: Commit**
```bash
git add app/src/main/java/com/hermes/client/ui/record/RecordTaskSheet.kt \
        app/src/main/java/com/hermes/client/ui/sessions/SessionsScreen.kt
git commit -m "feat: record-a-task mic action + sheet on the home session list"
```

---

## Notes for the executor
- Prefill (not auto-send): the transcript lands in the new chat's composer via `PendingShareStore` — do NOT auto-submit.
- Per-tenant accent for the record/active affordances; `colorScheme.error` for errors — never the accent for errors.
- Do NOT touch the composer's existing `RecognizerIntent` dictation mic (`ChatScreen.kt`) — this is a separate home-screen entry point.
- The gateway STT backend is a runtime prerequisite, not a client concern — surface its absence as the transcribe error path; never crash.
- If `HermesRestApi`/`ChatRepository`/`ProfileManager` differ from the assumed signatures, the ground truth is the code — adapt the wiring, keep the VM's function-typed seam so the unit test holds.
