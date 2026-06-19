package com.hermes.client.ui.nav

import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.launch

/**
 * Root navigation host wrapped in a navigation drawer.
 *
 * First-launch gating: when [hasConfig] is false the start destination is "setup". The drawer
 * is hidden (and its gestures disabled) on "setup" so credentials are entered before any
 * authenticated destination is reachable.
 *
 * I1: onUnauthorized clears the back stack and routes to "setup" so an expired token forces
 * re-entry of credentials.
 */
@Composable
fun HermesNav(hasConfig: Boolean) {
    val nav = rememberNavController()
    val start = if (hasConfig) "sessions" else "setup"
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val backStackEntry by nav.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route
    val drawerEnabled = route != null && route != "setup"

    val onUnauthorized: () -> Unit = {
        nav.navigate("setup") { popUpTo(0) { inclusive = true } }
    }
    val openDrawer: () -> Unit = { scope.launch { drawerState.open() } }
    val goto: (String) -> Unit = { dest ->
        scope.launch { drawerState.close() }
        nav.navigate(dest) {
            popUpTo("sessions")
            launchSingleTop = true
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerEnabled,
        drawerContent = {
            if (drawerEnabled) AppDrawer(currentRoute = route, onNavigate = goto)
        },
    ) {
        NavHost(navController = nav, startDestination = start) {
            composable("setup") {
                SetupScreen(
                    onSaved = {
                        nav.navigate("sessions") { popUpTo("setup") { inclusive = true } }
                    },
                )
            }
            composable("sessions") {
                SessionsScreen(
                    onOpen = { id -> nav.navigate("chat/$id") },
                    onMenu = openDrawer,
                    onUnauthorized = onUnauthorized,
                )
            }
            composable("chat/{id}") { entry ->
                ChatScreen(
                    sessionId = entry.arguments?.getString("id") ?: "",
                    onMenu = openDrawer,
                    onUnauthorized = onUnauthorized,
                )
            }
            composable("models") { ModelsScreen(onMenu = openDrawer) }
            composable("profiles") { ProfilesScreen(onMenu = openDrawer) }
            composable("cron") {
                CronScreen(
                    onMenu = openDrawer,
                    onOpen = { id -> nav.navigate("cron_detail/$id") },
                    onNew = { nav.navigate("cron_edit/new") },
                )
            }
            composable("cron_detail/{id}") { entry ->
                val id = entry.arguments?.getString("id") ?: ""
                CronDetailScreen(
                    jobId = id,
                    onBack = { nav.popBackStack() },
                    onEdit = { nav.navigate("cron_edit/$id") },
                )
            }
            composable("cron_edit/{id}") { entry ->
                CronEditScreen(
                    jobId = entry.arguments?.getString("id") ?: "new",
                    onDone = { nav.popBackStack() },
                )
            }
            composable("messaging") { MessagingScreen(onMenu = openDrawer) }
            composable("usage") { UsageScreen(onMenu = openDrawer) }
            composable("settings") {
                SettingsScreen(
                    onMenu = openDrawer,
                    onNavigate = { dest -> nav.navigate(dest) { launchSingleTop = true } },
                )
            }
            composable("settings_appearance") { AppearanceScreen(onBack = { nav.popBackStack() }) }
            composable("settings_memory") { MemorySettingsScreen(onBack = { nav.popBackStack() }) }
            composable("settings_mcp") { McpSettingsScreen(onBack = { nav.popBackStack() }) }
            composable("settings_env") { EnvScreen(onBack = { nav.popBackStack() }) }
            composable("settings_about") { AboutScreen(onBack = { nav.popBackStack() }) }
            composable("management") {
                ManagementScreen(
                    onMenu = openDrawer,
                    onNavigate = { dest -> nav.navigate(dest) { launchSingleTop = true } },
                )
            }
            composable("session_admin") {
                SessionAdminScreen(
                    onMenu = openDrawer,
                    onOpen = { id -> nav.navigate("chat/$id") },
                )
            }
            composable("agents_tools") { AgentsToolsScreen(onMenu = openDrawer) }
        }
    }
}
