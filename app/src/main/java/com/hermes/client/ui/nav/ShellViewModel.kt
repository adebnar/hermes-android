package com.hermes.client.ui.nav

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.network.ProfileDto
import com.hermes.client.data.repository.ProfileAccentStore
import com.hermes.client.data.repository.ProfileManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ShellViewModel @Inject constructor(
    private val profileManager: ProfileManager,
    private val accentStore: ProfileAccentStore,
) : ViewModel() {
    val profiles: StateFlow<List<ProfileDto>> = profileManager.list
    val active: StateFlow<String?> = profileManager.active

    init { viewModelScope.launch { profileManager.refresh() } }

    fun switchProfile(name: String) = viewModelScope.launch { profileManager.switchTo(name) }

    /** Set a custom accent colour for [profile] (persisted; overrides the auto-hashed hue). */
    fun setAccent(profile: String, argb: Int) = viewModelScope.launch { accentStore.setColor(profile, argb) }

    /** Clear [profile]'s custom colour, reverting to the auto hue. */
    fun clearAccent(profile: String) = viewModelScope.launch { accentStore.clear(profile) }
}
