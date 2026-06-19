package com.hermes.client.data.repository

import com.hermes.client.data.network.ProfileDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-wide source of truth for the active profile and the profile list.
 *
 * A singleton so the navigation drawer (which switches profiles) and the Sessions screen
 * (which must reload when the tenant changes) share one state. [changed] is bumped on every
 * successful switch so observers can react without polling.
 */
@Singleton
class ProfileManager @Inject constructor(private val profiles: ProfileRepository) {
    private val _list = MutableStateFlow<List<ProfileDto>>(emptyList())
    val list: StateFlow<List<ProfileDto>> = _list.asStateFlow()

    private val _active = MutableStateFlow<String?>(null)
    val active: StateFlow<String?> = _active.asStateFlow()

    private val _changed = MutableStateFlow(0)
    val changed: StateFlow<Int> = _changed.asStateFlow()

    suspend fun refresh() {
        runCatching { _list.value = profiles.list() }
        runCatching { _active.value = profiles.active() }
    }

    /** Switches the gateway's active profile. No-op if already active. */
    suspend fun switchTo(name: String) {
        if (name == _active.value) return
        runCatching { profiles.setActive(name) }.onSuccess {
            _active.value = name
            _changed.value = _changed.value + 1
        }
    }
}
