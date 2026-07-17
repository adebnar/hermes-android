package com.hermes.client

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
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
import com.hermes.client.data.repository.ProfileManager
import com.hermes.client.data.repository.SettingsStore
import com.hermes.client.data.repository.ThemeMode
import com.hermes.client.ui.diagnostics.CrashReportScreen
import com.hermes.client.ui.nav.HermesNav
import com.hermes.client.ui.nav.deepLinkRouteFor
import com.hermes.client.ui.theme.HermesTheme
import com.hermes.client.ui.theme.LocalToolCallTechnical
import dagger.hilt.android.AndroidEntryPoint
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var credentialStore: CredentialStore
    @Inject lateinit var settingsStore: SettingsStore
    @Inject lateinit var profileManager: ProfileManager
    @Inject lateinit var profileAccentStore: com.hermes.client.data.repository.ProfileAccentStore
    @Inject lateinit var notificationSettings: com.hermes.client.data.repository.NotificationSettings
    @Inject lateinit var chat: com.hermes.client.data.repository.ChatRepository
    @Inject lateinit var pendingShare: com.hermes.client.share.PendingShareStore

    /**
     * Route requested by a tapped notification (see `HermesNotifier.openIntent`'s
     * `extra_route`). Read on create and on every `onNewIntent` so a tap while the app is
     * already running still navigates; consumed by `HermesNav`'s `deepLinkRoute` param.
     */
    private var pendingRoute = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingRoute.value = intent?.getStringExtra("extra_route")
            ?: intent?.data?.let { deepLinkRouteFor(it.toString()) }
        intent?.removeExtra("extra_route")
        intent?.data = null
        handleShare(intent)
        val hasConfig = credentialStore.load() != null
        val crashReport = CrashReporter.read(this)
        // Resume the notification service if the user previously enabled it.
        // lifecycleScope (not a bare MainScope) so the coroutine is cancelled with the activity
        // instead of leaking on every recreation.
        lifecycleScope.launch {
            if (notificationSettings.prefs.first().enabled) {
                // On API 33+ the service posts notifications and needs POST_NOTIFICATIONS at
                // runtime; if it isn't granted (e.g. revoked since last enable), don't auto-start
                // — the Settings screen re-requests the permission the next time it's enabled.
                val canStart = Build.VERSION.SDK_INT < 33 ||
                    ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) == PackageManager.PERMISSION_GRANTED
                if (canStart) {
                    com.hermes.client.notifications.GatewayConnectionService.start(this@MainActivity)
                }
            }
        }
        setContent {
            val mode by settingsStore.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
            val technical by settingsStore.toolCallTechnical.collectAsState(initial = true)
            val activeProfile by profileManager.active.collectAsState()
            val accentOverrides by profileAccentStore.overrides.collectAsState(initial = emptyMap())
            val dark = when (mode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // Provide the user's per-profile colour overrides above the theme so HermesTheme and
            // every accent call site (Mission Control pages, You-tab avatars) can honour them.
            CompositionLocalProvider(
                com.hermes.client.ui.theme.LocalProfileAccentOverrides provides accentOverrides,
            ) {
                HermesTheme(darkTheme = dark, profile = activeProfile) {
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
                                val deepLinkRoute by pendingRoute
                                HermesNav(
                                    hasConfig = hasConfig,
                                    deepLinkRoute = deepLinkRoute,
                                    onDeepLinkConsumed = { pendingRoute.value = null },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingRoute.value = intent.getStringExtra("extra_route")
            ?: intent.data?.let { deepLinkRouteFor(it.toString()) }
        intent.removeExtra("extra_route")
        intent.data = null
        handleShare(intent)
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

    /**
     * Handle an incoming ACTION_SEND share (text or a single image): open a new chat with the text
     * pre-filled and/or the image attached. Reuses the notification deep-link rail.
     */
    private fun handleShare(intent: Intent?) {
        val text = com.hermes.client.share.sharedText(
            intent?.action, intent?.type,
            intent?.getStringExtra(Intent.EXTRA_SUBJECT), intent?.getStringExtra(Intent.EXTRA_TEXT),
        )
        val isImage = com.hermes.client.share.isImageShare(intent?.action, intent?.type)
        val imageUri: android.net.Uri? = if (isImage) {
            if (Build.VERSION.SDK_INT >= 33) {
                intent?.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
            } else {
                @Suppress("DEPRECATION") intent?.getParcelableExtra(Intent.EXTRA_STREAM)
            }
        } else null

        if (text == null && imageUri == null) return

        // For an image share the caption (if any) is EXTRA_TEXT; a text share's caption is `text`.
        val caption = if (isImage) {
            intent?.getStringExtra(Intent.EXTRA_TEXT)?.trim()?.takeIf { it.isNotEmpty() }
        } else text

        // Consume the extras so a config-change recreation doesn't re-fire the share.
        intent?.removeExtra(Intent.EXTRA_TEXT)
        intent?.removeExtra(Intent.EXTRA_SUBJECT)
        intent?.removeExtra(Intent.EXTRA_STREAM)

        if (credentialStore.load() == null) return

        lifecycleScope.launch {
            var b64: String? = null
            var mime: String? = null
            if (imageUri != null) {
                val read = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        val bytes = contentResolver.openInputStream(imageUri)?.use { it.readBytes() }
                            ?: return@runCatching null
                        val m = contentResolver.getType(imageUri) ?: "image/*"
                        android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP) to m
                    }.getOrNull()
                }
                if (read == null) {
                    android.widget.Toast.makeText(
                        this@MainActivity, "Couldn't read the image", android.widget.Toast.LENGTH_SHORT,
                    ).show()
                    if (caption == null) return@launch  // nothing left to share
                } else {
                    b64 = read.first; mime = read.second
                }
            }
            // connect() first — a cold-start share has no open socket yet, and createSession()
            // would otherwise fail after the ready-gate timeout. connect() is idempotent.
            chat.connect()
            runCatching {
                // Load the active profile before creating: on a cold-start share nothing has called
                // refresh() yet (that normally happens when SessionsViewModel inits), so active would
                // be null and the new session would orphan to the gateway's default profile. refresh()
                // hits the gateway, so keep it inside runCatching — an offline cold-start share must
                // surface the error toast, not crash.
                profileManager.refresh()
                chat.createSession(profileManager.active.value)
            }
                .onSuccess { id ->
                    pendingShare.put(
                        id,
                        com.hermes.client.share.PendingShare(text = caption, imageBase64 = b64, imageMime = mime),
                    )
                    pendingRoute.value = "chat/$id"
                }
                .onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    android.widget.Toast.makeText(
                        this@MainActivity, "Couldn't start a chat", android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
        }
    }
}
