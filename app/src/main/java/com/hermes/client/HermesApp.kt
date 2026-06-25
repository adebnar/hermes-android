package com.hermes.client

import android.app.Application
import com.hermes.client.data.diagnostics.CrashReporter
import com.hermes.client.data.diagnostics.DebugLog
import com.hermes.client.data.repository.SettingsStore
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject

@HiltAndroidApp
class HermesApp : Application() {
    @Inject lateinit var settingsStore: SettingsStore

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // Capture uncaught exceptions to a file so the next launch can surface the trace (no adb).
        CrashReporter.install(this)
        // Restore the diagnostic-logging toggle at launch so capture is active before the
        // Diagnostics screen is ever opened (e.g. to catch a failure on the first session open).
        settingsStore.debugLogging
            .distinctUntilChanged()
            .onEach { DebugLog.setEnabled(it) }
            .launchIn(appScope)
    }
}
