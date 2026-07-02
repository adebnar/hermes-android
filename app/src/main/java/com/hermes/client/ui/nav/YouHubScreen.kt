package com.hermes.client.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.client.ui.components.HermesTopBar
import com.hermes.client.ui.components.HubRow
import com.hermes.client.ui.theme.ACCENT_SWATCHES
import com.hermes.client.ui.theme.rememberProfileAccent

/**
 * "You" tab — profile identity + everything about the account/app: the profile quick-switch
 * (relocated here from the retired drawer), plus Profiles, Models, Management, and Settings.
 */
@Composable
fun YouHubScreen(
    onNavigate: (String) -> Unit,
    vm: ShellViewModel = hiltViewModel(),
) {
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val active by vm.active.collectAsStateWithLifecycle()
    var showColorPicker by remember { mutableStateOf(false) }
    val currentOverride = active?.let { com.hermes.client.ui.theme.LocalProfileAccentOverrides.current[it] }

    Scaffold(topBar = { HermesTopBar(title = "You", subtitle = active?.let { "Active profile: $it" }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            Text(
                "PROFILES",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
            )
            ProfileAvatarRow(
                profiles = profiles.map { it.name },
                active = active,
                onSwitch = { vm.switchProfile(it) },
            )
            active?.let { a ->
                HubRow(
                    Icons.Rounded.Palette,
                    "Accent colour",
                    if (currentOverride != null) "Custom colour for \"$a\"" else "Auto colour for \"$a\"",
                ) { showColorPicker = true }
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            HubRow(Icons.Rounded.People, "Profiles", "Manage tenant profiles") { onNavigate("profiles") }
            HorizontalDivider()
            HubRow(Icons.Rounded.AutoAwesome, "Models", "Model catalog & defaults") { onNavigate("models") }
            HorizontalDivider()
            HubRow(Icons.Rounded.AdminPanelSettings, "Management", "Admin & session tools") { onNavigate("management") }
            HorizontalDivider()
            HubRow(Icons.Rounded.Settings, "Settings", "App & connection settings") { onNavigate("settings") }
        }
    }

    if (showColorPicker) {
        active?.let { a ->
            AccentColorDialog(
                profile = a,
                selected = currentOverride,
                onPick = { argb -> vm.setAccent(a, argb); showColorPicker = false },
                onAuto = { vm.clearAccent(a); showColorPicker = false },
                onDismiss = { showColorPicker = false },
            )
        }
    }
}

/** Curated-swatch colour picker for a profile's accent, with an Auto (clear) option. Swatches are
 *  contrast-safe by construction, so any pick keeps chrome text legible. */
@Composable
private fun AccentColorDialog(
    profile: String,
    selected: Int?,
    onPick: (Int) -> Unit,
    onAuto: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Accent colour · $profile") },
        text = {
            Column {
                Text(
                    "Pick a colour for this profile, or Auto for the automatic hue.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(16.dp))
                ACCENT_SWATCHES.chunked(6).forEach { rowColors ->
                    Row {
                        rowColors.forEach { argb ->
                            Box(
                                Modifier
                                    .padding(6.dp)
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(Color(argb))
                                    .clickable { onPick(argb) },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (argb == selected) {
                                    Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = Color.White)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onAuto) { Text("Auto") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** Quick-switch avatar row: each profile's initial in a chip tinted to that profile's own accent. */
@Composable
private fun ProfileAvatarRow(profiles: List<String>, active: String?, onSwitch: (String) -> Unit) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        profiles.forEach { name ->
            val selected = name == active
            // Each avatar previews that profile's own accent hue, so the switcher itself teaches
            // the color mapping.
            val accent = rememberProfileAccent(name, dark)
            Column(
                Modifier.padding(end = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(accent.accent)
                        .clickable { onSwitch(name) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(name.take(1).uppercase(), color = accent.onAccent, style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    name,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}
