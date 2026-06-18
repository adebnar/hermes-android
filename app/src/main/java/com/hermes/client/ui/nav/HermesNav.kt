package com.hermes.client.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.hermes.client.ui.chat.ChatScreen
import com.hermes.client.ui.sessions.SessionsScreen
import com.hermes.client.ui.setup.SetupScreen

/**
 * Root navigation host.
 *
 * First-launch gating: when [hasConfig] is false the start destination is "setup" so the user
 * must supply a Gateway URL and token before reaching "sessions". This is a hard requirement
 * because [SessionsViewModel.init] calls [ChatRepository.connect], which throws
 * IllegalStateException("no gateway configured") if no credentials have been saved.
 */
@Composable
fun HermesNav(hasConfig: Boolean) {
    val nav = rememberNavController()
    val start = if (hasConfig) "sessions" else "setup"
    NavHost(navController = nav, startDestination = start) {
        composable("setup") {
            SetupScreen(
                onSaved = {
                    nav.navigate("sessions") {
                        popUpTo("setup") { inclusive = true }
                    }
                },
            )
        }
        composable("sessions") {
            SessionsScreen(onOpen = { id -> nav.navigate("chat/$id") })
        }
        composable("chat/{id}") { entry ->
            ChatScreen(sessionId = entry.arguments?.getString("id") ?: "")
        }
    }
}
