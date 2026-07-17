package com.hermes.client.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.network.ConnectionState
import com.hermes.client.data.network.HermesApiException
import com.hermes.client.data.network.ProfileDto
import com.hermes.client.data.repository.ChatRepository
import com.hermes.client.data.repository.ModelRepository
import com.hermes.client.data.repository.ProfileManager
import com.hermes.client.data.repository.ProfileRepository
import com.hermes.client.data.repository.SessionRepository
import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Role
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chat: ChatRepository,
    private val sessions: SessionRepository,
    private val modelRepo: ModelRepository,
    private val profileRepo: ProfileRepository,
    private val profileManager: ProfileManager,
    private val favoritesStore: com.hermes.client.data.repository.ModelFavoritesStore,
    private val pendingShareStore: com.hermes.client.share.PendingShareStore,
) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState.empty())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    val connectionState: StateFlow<ConnectionState> = chat.connectionState

    // I1: expose 401 unauthorized so the nav layer can route back to Setup
    private val _unauthorized = MutableStateFlow(false)
    val unauthorized: StateFlow<Boolean> = _unauthorized.asStateFlow()

    // The model this session is confirmed to be using. Null until a switch succeeds (the gateway
    // doesn't report the session's current model up-front), so the picker shows "Model" until the
    // user changes it, then the chosen model as confirmation the switch took.
    private val _currentModel = MutableStateFlow<String?>(null)
    val currentModel: StateFlow<String?> = _currentModel.asStateFlow()

    // Provider list for the model sheet (grouped by real slug); loaded alongside options.
    private val _providers = MutableStateFlow<List<com.hermes.client.data.network.ModelProviderDto>>(emptyList())
    val providers: kotlinx.coroutines.flow.StateFlow<List<com.hermes.client.data.network.ModelProviderDto>> = _providers.asStateFlow()

    // Provider of the confirmed session model (set together with _currentModel on a successful switch).
    private val _currentProvider = MutableStateFlow<String?>(null)
    val currentProvider: kotlinx.coroutines.flow.StateFlow<String?> = _currentProvider.asStateFlow()

    // Text handed off from a share (Share-to-Hermes). ChatScreen pre-fills the composer with it once.
    private val _initialDraft = MutableStateFlow<String?>(null)
    val initialDraft: StateFlow<String?> = _initialDraft.asStateFlow()
    fun clearInitialDraft() { _initialDraft.value = null }

    val favorites: kotlinx.coroutines.flow.StateFlow<Set<String>> =
        favoritesStore.favorites.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), emptySet())

    data class ModelSheetUi(
        val query: String = "",
        val scope: com.hermes.client.ui.models.ModelScope = com.hermes.client.ui.models.ModelScope.SESSION,
        val pending: Boolean = false,
        val error: String? = null,
    )
    private val _modelSheet = MutableStateFlow(ModelSheetUi())
    val modelSheet: kotlinx.coroutines.flow.StateFlow<ModelSheetUi> = _modelSheet.asStateFlow()

    private val _profiles = MutableStateFlow<List<ProfileDto>>(emptyList())
    val profiles: StateFlow<List<ProfileDto>> = _profiles.asStateFlow()

    /** Active profile name — shown in the chat top bar so the user knows which tenant they're in. */
    val activeProfile: StateFlow<String?> = profileManager.active

    /** Slash-command catalog (name to description) for the composer palette. */
    private val _commands = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val commands: StateFlow<List<Pair<String, String>>> = _commands.asStateFlow()

    /** "@" mention completions for the current @-word in the composer. */
    private val _pathItems = MutableStateFlow<List<com.hermes.client.data.repository.PathItem>>(emptyList())
    val pathItems: StateFlow<List<com.hermes.client.data.repository.PathItem>> = _pathItems.asStateFlow()

    private var sessionId: String = ""
    private var collectJob: Job? = null
    private var connJob: Job? = null

    fun open(id: String) {
        sessionId = id
        connJob?.cancel()
        // A share created this session and stashed its text; surface it as the initial composer draft.
        val ps = pendingShareStore.take(id)
        ps?.text?.let { _initialDraft.value = it }
        com.hermes.client.data.diagnostics.DebugLog.log("session", "open($id)")
        viewModelScope.launch {
            try {
                val history = sessions.history(id, profileManager.active.value)
                com.hermes.client.data.diagnostics.DebugLog.log("session", "history($id) → ${history.size} messages")
                _state.value = ChatUiState(messages = history)
            } catch (e: HermesApiException) {
                com.hermes.client.data.diagnostics.DebugLog.log("error", "history($id) failed: ${e.code} ${e.message}")
                if (e.code == 401) { _unauthorized.value = true; return@launch }
                _state.value = ChatUiState(messages = emptyList())
            } catch (e: Exception) {
                // History load failed (network/parse) — start with an empty thread rather than crash.
                com.hermes.client.data.diagnostics.DebugLog.log("error", "history($id) failed: ${e.message}")
                _state.value = ChatUiState(messages = emptyList())
            }
            // resume() returns the live socket handle for this session; switch to it so
            // submit/interrupt and event filtering use the id the gateway actually knows.
            // Pass the active profile: the gateway resolves resume against a per-profile DB,
            // so a session in a non-default profile is "session not found" without it.
            val handle = runCatching { chat.resume(id, profileManager.active.value) }.getOrNull()
            handle?.let { sessionId = it }
            com.hermes.client.data.diagnostics.DebugLog.log("session", "resume($id) → handle=${handle ?: "none"}")
            // A share may have handed off an image; stage it so it shows as a chip and is
            // flushed to the gateway on the next send (rather than attaching immediately).
            ps?.let { share ->
                val imgB64 = share.imageBase64
                val imgMime = share.imageMime
                if (imgB64 != null && imgMime != null) {
                    // java.util.Base64 (not android.util.Base64): the latter is stubbed to a
                    // no-op returning null in the JVM unit-test environment (no Robolectric),
                    // which would silently drop every shared image under test. java.util.Base64
                    // is a real JDK class (available since API 26, our minSdk) so it decodes
                    // correctly both on-device and under test.
                    runCatching { java.util.Base64.getDecoder().decode(imgB64) }
                        .onSuccess { bytes -> stageAttachment(bytes, imgMime) }
                        .onFailure { e ->
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            appendError("Attach failed: ${e.message}")
                        }
                }
            }
            // Load model options, profiles, and the slash-command catalog; failures are non-fatal
            launch { runCatching { _providers.value = modelRepo.providers() } }
            launch { runCatching { _profiles.value = profileRepo.list() } }
            launch { runCatching { _commands.value = chat.commandsCatalog() } }
        }
        collectJob?.cancel()
        collectJob = viewModelScope.launch {
            chat.events.filter { it.sessionId == null || it.sessionId == sessionId }
                // Defense in depth: a single malformed event must never crash the chat.
                // reduce() is pure, so on a bad event keep the prior state and drop it.
                .onEach { event -> runCatching { _state.value.reduce(event) }.onSuccess { _state.value = it } }
                .collect {}
        }
        // C2 + I3: watch connection transitions
        connJob = viewModelScope.launch {
            var prev: ConnectionState? = null
            chat.connectionState.collect { cur ->
                // I3: entering Reconnecting or Error while generating → mark interrupted
                if ((cur is ConnectionState.Reconnecting || cur is ConnectionState.Error)
                    && _state.value.isGenerating
                ) {
                    _state.value = _state.value.markInterrupted()
                }
                // C2: reconnect cycle completed (Reconnecting → Connected) → re-attach agent stream
                // Guard: prev must be Reconnecting (not null) to skip the very first Connected transition
                if (cur is ConnectionState.Connected && prev is ConnectionState.Reconnecting) {
                    launch {
                        runCatching { chat.resume(sessionId, profileManager.active.value) }
                            .getOrNull()?.let { sessionId = it }
                    }
                }
                prev = cur
            }
        }
    }

    fun stageAttachment(bytes: ByteArray, mimeType: String) {
        // Generate the id outside update{}: staging is called from background (IO) threads, and the
        // update lambda can re-run under CAS contention — a shared counter would race/collide. UUID
        // is collision-free across threads.
        val id = "att-${java.util.UUID.randomUUID()}"
        _state.update { it.withAttachment(PendingAttachment(id, bytes, mimeType)) }
    }
    fun removeAttachment(id: String) { _state.update { it.withoutAttachment(id) } }

    fun send(text: String) {
        val atts = _state.value.pendingAttachments
        if (text.isBlank() && atts.isEmpty()) return
        val isSlash = text.trimStart().startsWith("/")
        val shown = text.ifBlank { "📎 ${atts.size} image${if (atts.size > 1) "s" else ""}" }
        _state.value = _state.value.withUserMessage(shown).copy(pendingAttachments = emptyList())
        viewModelScope.launch {
            try {
                atts.forEach { a ->
                    // java.util.Base64 (minSdk 26): consistent with the share-decode path and,
                    // unlike android.util.Base64, not stubbed to null under JVM unit tests.
                    val b64 = java.util.Base64.getEncoder().encodeToString(a.bytes)
                    runCatching { chat.attachImageBytes(sessionId, b64, a.mimeType) }
                        .onFailure { appendError("Attach failed: ${it.message}") }
                }
                // A leading "/" is a slash command — execute it (the gateway strips the slash)
                // rather than prompting the model.
                if (isSlash) chat.slashExec(sessionId, text.trim())
                else chat.submit(sessionId, text)
            } catch (e: Exception) {
                // A gateway error (e.g. "session not found") must surface, not crash the app.
                appendError(e.message ?: "Failed to send message")
            }
        }
    }

    /** Re-ask: re-submit the last user prompt (appends a new answer; the gateway can't replace). */
    fun regenerate() {
        if (_state.value.isGenerating) return
        val prompt = lastUserMessageText(_state.value.messages) ?: return
        send(prompt)
    }

    fun stop() { viewModelScope.launch { runCatching { chat.interrupt(sessionId) } } }

    private fun appendSystem(text: String) {
        _state.value = _state.value.copy(
            messages = _state.value.messages + ChatMessage(
                id = "s-${_state.value.messages.size}", role = Role.SYSTEM, text = text,
            ),
        )
    }

    /** User tapped "Retry" on the offline banner — force an immediate reconnect. */
    fun reconnect() { runCatching { chat.reconnect() } }

    /** Fetch "@" completions for [word] (empty word clears them). */
    fun completePath(word: String) = viewModelScope.launch {
        if (!word.startsWith("@")) { _pathItems.value = emptyList(); return@launch }
        runCatching { chat.completePath(sessionId, word) }
            .onSuccess { _pathItems.value = it }
            .onFailure { _pathItems.value = emptyList() }
    }

    fun clearPathItems() { _pathItems.value = emptyList() }

    fun respondApproval(choice: ApprovalChoice) {
        _state.value = _state.value.copy(pendingApproval = null)
        viewModelScope.launch {
            runCatching { chat.respondApproval(sessionId, choice) }
                .onFailure {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    // The sheet is already gone; surface the failure so a lost approve/deny is visible.
                    appendError("Couldn't send your approval — check the connection and try again.")
                }
        }
    }

    fun clarify(answer: String) {
        val requestId = _state.value.pendingClarify?.requestId ?: ""
        _state.value = _state.value.copy(pendingClarify = null)
        viewModelScope.launch { runCatching { chat.respondClarify(sessionId, requestId, answer) } }
    }

    /** Appends a non-fatal error as a system message and stops the generating spinner. */
    private fun appendError(text: String) {
        _state.value = _state.value.copy(
            messages = _state.value.messages + ChatMessage(
                id = "e-${_state.value.messages.size}",
                role = Role.SYSTEM,
                text = text,
                isError = true,
            ),
            isGenerating = false,
        )
    }

    fun onSheetQuery(q: String) { _modelSheet.value = _modelSheet.value.copy(query = q) }
    fun onSheetScope(s: com.hermes.client.ui.models.ModelScope) {
        _modelSheet.value = _modelSheet.value.copy(scope = s, error = null)
    }
    fun toggleFavorite(provider: String, model: String) =
        viewModelScope.launch { favoritesStore.toggle(provider, model) }

    /**
     * Apply a model chosen in the sheet. SESSION → the /model --session slash (overrides just this
     * chat); DEFAULT → the global default via REST. On failure the gateway message is surfaced in
     * the sheet (kept open); on success the sheet is dismissed by the caller via [onDone].
     */
    fun onSelectFromSheet(provider: String, model: String, onDone: () -> Unit) {
        _modelSheet.value = _modelSheet.value.copy(pending = true, error = null)
        viewModelScope.launch {
            when (_modelSheet.value.scope) {
                com.hermes.client.ui.models.ModelScope.SESSION ->
                    runCatching { chat.slashExec(sessionId, "/model $model --provider $provider --session") }
                        .onSuccess {
                            _currentModel.value = model
                            _currentProvider.value = provider
                            _modelSheet.value = ModelSheetUi()  // reset + clear pending/error
                            onDone()
                        }
                        .onFailure { e ->
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            _modelSheet.value = _modelSheet.value.copy(
                                pending = false,
                                error = e.message ?: "Couldn't switch model.",
                            )
                        }
                com.hermes.client.ui.models.ModelScope.DEFAULT ->
                    runCatching { modelRepo.set(provider, model) }
                        .onSuccess {
                            _modelSheet.value = _modelSheet.value.copy(pending = false, error = null)
                            appendSystem("Default set to $model")
                            onDone()
                        }
                        .onFailure { e ->
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            _modelSheet.value = _modelSheet.value.copy(
                                pending = false,
                                error = e.message ?: "Couldn't set default model.",
                            )
                        }
            }
        }
    }

    fun selectProfile(name: String) {
        viewModelScope.launch { runCatching { profileRepo.setActive(name) } }
    }
}
