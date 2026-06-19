package com.hermes.client

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import com.hermes.client.data.auth.CredentialStore
import com.hermes.client.data.repository.SettingsStore
import com.hermes.client.data.repository.ThemeMode
import com.hermes.client.ui.nav.HermesNav
import com.hermes.client.ui.theme.HermesTheme
import com.hermes.client.ui.theme.LocalToolCallTechnical
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var credentialStore: CredentialStore
    @Inject lateinit var settingsStore: SettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val hasConfig = credentialStore.load() != null
        setContent {
            val mode by settingsStore.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
            val technical by settingsStore.toolCallTechnical.collectAsState(initial = true)
            val dark = when (mode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            HermesTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalToolCallTechnical provides technical) {
                    Surface {
                        HermesNav(hasConfig = hasConfig)
                    }
                }
            }
        }
    }
}
