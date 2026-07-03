package com.hermes.client.ui.nav

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.hermes.client.ui.admin.SessionAdminScreen
import com.hermes.client.ui.chat.ChatScreen
import com.hermes.client.ui.cron.CronDetailScreen
import com.hermes.client.ui.cron.CronEditScreen
import com.hermes.client.ui.cron.CronScreen
import com.hermes.client.ui.management.ManagementScreen
import com.hermes.client.ui.messaging.MessagingScreen
import com.hermes.client.ui.messaging.MessagingSetupScreen
import com.hermes.client.ui.models.ModelsScreen
import com.hermes.client.ui.profiles.ProfilesScreen
import com.hermes.client.ui.sessions.SessionsScreen
import com.hermes.client.ui.settings.AboutScreen
import com.hermes.client.ui.settings.AppearanceScreen
import com.hermes.client.ui.settings.EnvScreen
import com.hermes.client.ui.settings.McpSettingsScreen
import com.hermes.client.ui.settings.MemorySettingsScreen
import com.hermes.client.ui.settings.SettingsScreen
import com.hermes.client.ui.setup.SetupScreen
import com.hermes.client.ui.tools.AgentsToolsScreen
import com.hermes.client.ui.usage.UsageScreen

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("sessions", "Chats", Icons.AutoMirrored.Rounded.Chat),
    Tab("activity", "Agent Activity", Icons.Rounded.Bolt),
    Tab("you", "You", Icons.Rounded.Person),
)

/**
 * Root navigation host with a three-tab bottom bar (Chats · Agent Activity · You).
 *
 * The bottom bar shows only on the three tab roots; pushed screens (chat, detail/edit, settings
 * sub-pages, admin, archived) are full-screen with a back arrow. First-launch gating: when
 * [hasConfig] is false the start destination is "setup" and the bar is hidden there.
 *
 * onUnauthorized clears the back stack and routes to "setup" so an expired token forces re-entry.
 *
 * [deepLinkRoute], when non-null, is navigated to once (keyed by value) — used to jump straight
 * to a session when the activity is launched or resumed from a tapped notification.
 */
@Composable
fun HermesNav(hasConfig: Boolean, deepLinkRoute: String? = null) {
    val nav = rememberNavController()
    val start = if (hasConfig) "sessions" else "setup"

    LaunchedEffect(deepLinkRoute) { deepLinkRoute?.let { nav.navigate(it) } }

    val backStackEntry by nav.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route

    val onUnauthorized: () -> Unit = {
        nav.navigate("setup") { popUpTo(0) { inclusive = true } }
    }
    // Pushed screens navigate "up"; their top-bar nav icon (formerly the drawer hamburger) is a
    // back arrow wired to this.
    val back: () -> Unit = { nav.popBackStack() }
    // Drill into a screen from a tab hub (Agent Activity / You).
    val push: (String) -> Unit = { dest -> nav.navigate(dest) { launchSingleTop = true } }
    // Switch top-level tab with state save/restore so each tab keeps its own back stack position.
    val switchTab: (String) -> Unit = { dest ->
        nav.navigate(dest) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    val showBottomBar = route in TABS.map { it.route }.toSet()

    Scaffold(
        // Let each destination's own Scaffold own the top/side insets; this outer one exists
        // only to host the bottom bar, so it contributes bottom padding and nothing else
        // (otherwise the status-bar inset would be applied twice and push titles down).
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    TABS.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = { switchTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = start,
            // Only reserve space for the bottom bar; top/side insets are each screen's own job.
            modifier = Modifier.padding(bottom = padding.calculateBottomPadding()),
        ) {
            composable("setup") {
                SetupScreen(
                    onSaved = {
                        nav.navigate("sessions") { popUpTo("setup") { inclusive = true } }
                    },
                )
            }
            // ---- Tab roots ----
            composable("sessions") {
                SessionsScreen(
                    onOpen = { id -> nav.navigate("chat/$id") },
                    onOpenArchived = { nav.navigate("archived") },
                    onUnauthorized = onUnauthorized,
                )
            }
            composable("activity") { com.hermes.client.ui.activity.MissionControlScreen(onNavigate = push) }
            composable("you") { YouHubScreen(onNavigate = push) }

            // ---- Pushed screens (back arrow) ----
            composable("archived") {
                com.hermes.client.ui.sessions.ArchivedSessionsScreen(
                    onOpen = { id -> nav.navigate("chat/$id") },
                    onBack = back,
                    onUnauthorized = onUnauthorized,
                )
            }
            composable("chat/{id}") { entry ->
                ChatScreen(
                    sessionId = entry.arguments?.getString("id") ?: "",
                    onMenu = back,
                    onUnauthorized = onUnauthorized,
                )
            }
            composable("models") { ModelsScreen(onMenu = back) }
            composable("profiles") { ProfilesScreen(onMenu = back) }
            composable("cron") {
                CronScreen(
                    onMenu = back,
                    onOpen = { id -> nav.navigate("cron_detail/$id") },
                    onNew = { nav.navigate("cron_edit/new") },
                )
            }
            composable("cron_detail/{id}") { entry ->
                val id = entry.arguments?.getString("id") ?: ""
                CronDetailScreen(
                    jobId = id,
                    onBack = back,
                    onEdit = { nav.navigate("cron_edit/$id") },
                )
            }
            composable("cron_edit/{id}") { entry ->
                CronEditScreen(
                    jobId = entry.arguments?.getString("id") ?: "new",
                    onDone = { nav.popBackStack() },
                )
            }
            composable("messaging") {
                MessagingScreen(
                    onMenu = back,
                    onSetup = { id -> nav.navigate("messaging_setup/$id") },
                )
            }
            composable("messaging_setup/{id}") { entry ->
                MessagingSetupScreen(
                    platformId = entry.arguments?.getString("id") ?: "",
                    onDone = { nav.popBackStack() },
                )
            }
            composable("usage") { UsageScreen(onMenu = back) }
            composable("settings") {
                SettingsScreen(
                    onMenu = back,
                    onNavigate = { dest -> nav.navigate(dest) { launchSingleTop = true } },
                )
            }
            composable("settings_appearance") { AppearanceScreen(onBack = { nav.popBackStack() }) }
            composable("settings_notifications") {
                com.hermes.client.ui.settings.NotificationsScreen(onBack = { nav.popBackStack() })
            }
            composable("settings_memory") { MemorySettingsScreen(onBack = { nav.popBackStack() }) }
            composable("settings_mcp") { McpSettingsScreen(onBack = { nav.popBackStack() }) }
            composable("settings_env") { EnvScreen(onBack = { nav.popBackStack() }) }
            composable("settings_connection") {
                com.hermes.client.ui.settings.ConnectionSettingsScreen(onBack = { nav.popBackStack() })
            }
            composable("settings_diagnostics") {
                com.hermes.client.ui.settings.DiagnosticsScreen(onBack = { nav.popBackStack() })
            }
            composable("settings_about") { AboutScreen(onBack = { nav.popBackStack() }) }
            composable("management") {
                ManagementScreen(
                    onMenu = back,
                    onNavigate = { dest -> nav.navigate(dest) { launchSingleTop = true } },
                )
            }
            composable("session_admin") {
                SessionAdminScreen(
                    onMenu = back,
                    onOpen = { id -> nav.navigate("chat/$id") },
                )
            }
            composable("agents_tools") { AgentsToolsScreen(onMenu = back) }
        }
    }
}
