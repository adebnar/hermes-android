package com.hermes.client.ui.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.network.HermesApiException
import com.hermes.client.data.repository.ProfileManager
import com.hermes.client.data.repository.SessionRepository
import com.hermes.client.data.repository.ToolsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MissionControlState(
    val sections: List<ActivitySection> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val unauthorized: Boolean = false,
)

/**
 * Mission Control feed for one specific profile (each page of the profile-spatial pager owns its
 * own instance, keyed by profile name). Merges that profile's conversations and cron into
 * time-grouped sections; cron is fetched defensively so a cron failure still leaves conversations.
 */
@HiltViewModel
class MissionControlViewModel @Inject constructor(
    private val sessions: SessionRepository,
    private val tools: ToolsRepository,
    private val profileManager: ProfileManager,
) : ViewModel() {
    private val _state = MutableStateFlow(MissionControlState())
    val state: StateFlow<MissionControlState> = _state.asStateFlow()

    /** Lazily-loaded cron-run responses, keyed by sessionId. */
    data class CronResponseUi(val loading: Boolean = false, val text: String? = null, val error: Boolean = false)

    private val _responses = MutableStateFlow<Map<String, CronResponseUi>>(emptyMap())
    val responses: StateFlow<Map<String, CronResponseUi>> = _responses.asStateFlow()

    private var profile: String? = null
    private var loadJob: kotlinx.coroutines.Job? = null

    /** Load (or reload) the feed for [profile]. Cancels any in-flight load so rapid calls
     *  (LaunchedEffect + ON_RESUME) can't race to a stale last-writer-wins result. */
    fun load(profile: String?) {
        this.profile = profile
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            loadInner(profile)
        }
    }

    private suspend fun loadInner(profile: String?) {
        _state.value = _state.value.copy(loading = true, error = null, unauthorized = false)
        try {
            // activityFeed keeps cron-produced sessions so a scheduled run's output is openable.
            val all = sessions.activityFeed()
            val scoped = if (profile.isNullOrBlank()) all else all.filter { it.profile == profile }
            // A cron failure (e.g. profile without cron) must not blank the whole feed.
            val crons = runCatching { tools.cronJobs(profile) }.getOrDefault(emptyList())
            val items = sessionsToActivity(scoped) + cronsToActivity(crons)
            _state.value = MissionControlState(sections = groupActivity(items, System.currentTimeMillis()))
        } catch (e: HermesApiException) {
            if (e.code == 401) _state.value = MissionControlState(unauthorized = true)
            else _state.value = MissionControlState(error = e.message ?: "Failed to load")
        } catch (e: Exception) {
            _state.value = MissionControlState(error = e.message ?: "Failed to load")
        }
    }

    fun refresh() = load(profile)

    /** Make [profile] the app-wide active profile (awaited) before opening one of its items, so the
     *  chat/cron screens act against the correct per-profile DB. No-op if already active. */
    suspend fun switchTo(profile: String?) {
        if (!profile.isNullOrBlank() && profile != profileManager.active.value) {
            profileManager.switchTo(profile)
        }
    }

    /**
     * Fetch a cron run's response on demand (one REST history call), caching by sessionId. A loaded
     * or in-flight entry is not refetched; a prior error IS retryable (call again).
     */
    fun loadResponse(sessionId: String) {
        val existing = _responses.value[sessionId]
        if (existing != null && (existing.loading || existing.text != null)) return
        _responses.value = _responses.value + (sessionId to CronResponseUi(loading = true))
        viewModelScope.launch {
            val result = runCatching { sessions.history(sessionId, profile) }
            _responses.value = _responses.value + (
                sessionId to result.fold(
                    onSuccess = { CronResponseUi(text = cronResponse(it)) },
                    onFailure = { CronResponseUi(error = true) },
                )
            )
        }
    }
}
