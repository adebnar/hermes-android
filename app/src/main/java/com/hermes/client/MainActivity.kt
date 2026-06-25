package com.hermes.client

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.hermes.client.data.auth.CredentialStore
import com.hermes.client.data.diagnostics.CrashReporter
import com.hermes.client.data.repository.SettingsStore
import com.hermes.client.data.repository.ThemeMode
import com.hermes.client.ui.diagnostics.CrashReportScreen
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
        val crashReport = CrashReporter.read(this)
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
                        // If the previous run crashed, show the saved trace first so it can be
                        // shared, then continue into the app once dismissed.
                        var report by remember { mutableStateOf(crashReport) }
                        val current = report
                        if (current != null) {
                            CrashReportScreen(
                                report = current,
                                onShare = { shareCrash(current) },
                                onDismiss = { CrashReporter.clear(this@MainActivity); report = null },
                            )
                        } else {
                            HermesNav(hasConfig = hasConfig)
                        }
                    }
                }
            }
        }
    }

    /** Share the saved crash trace via the system share sheet. */
    private fun shareCrash(report: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Hermes Beta crash report")
            putExtra(Intent.EXTRA_TEXT, report)
        }
        startActivity(Intent.createChooser(intent, "Share crash report"))
    }
}
