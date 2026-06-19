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
import com.hermes.client.ui.chat.ChatScreen
import com.hermes.client.ui.management.ManagementScreen
import com.hermes.client.ui.models.ModelsScreen
import com.hermes.client.ui.profiles.ProfilesScreen
import com.hermes.client.ui.sessions.SessionsScreen
import com.hermes.client.ui.setup.SetupScreen
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
                    onUnauthorized = onUnauthorized,
                )
            }
            composable("models") { ModelsScreen(onMenu = openDrawer) }
            composable("profiles") { ProfilesScreen(onMenu = openDrawer) }
            composable("management") { ManagementScreen(onMenu = openDrawer) }
        }
    }
}
