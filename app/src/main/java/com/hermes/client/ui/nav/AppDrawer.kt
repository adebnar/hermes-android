package com.hermes.client.ui.nav

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Top-level navigation destinations reachable from the drawer. */
private val DESTINATIONS = listOf(
    "sessions" to "Sessions",
    "models" to "Models",
    "profiles" to "Profiles",
    "management" to "Management",
)

@Composable
fun AppDrawer(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    vm: ShellViewModel = hiltViewModel(),
) {
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val active by vm.active.collectAsStateWithLifecycle()

    ModalDrawerSheet {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(
                "Hermes",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
            )
            Text(
                "Active profile: ${active ?: "—"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, bottom = 12.dp),
            )

            Text(
                "PROFILES",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
            )
            profiles.forEach { p ->
                NavigationDrawerItem(
                    label = { Text(if (p.name == active) "${p.name}  ✓" else p.name) },
                    selected = p.name == active,
                    onClick = { vm.switchProfile(p.name) },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            DESTINATIONS.forEach { (route, label) ->
                NavigationDrawerItem(
                    label = { Text(label) },
                    selected = currentRoute == route,
                    onClick = { onNavigate(route) },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
