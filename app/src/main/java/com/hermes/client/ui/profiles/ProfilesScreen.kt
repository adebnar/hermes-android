package com.hermes.client.ui.profiles

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.client.ui.nav.ShellViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesScreen(
    onMenu: () -> Unit,
    vm: ShellViewModel = hiltViewModel(),
) {
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val active by vm.active.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Profiles") },
                navigationIcon = { IconButton(onClick = onMenu) { Text("☰") } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            items(profiles, key = { it.name }) { p ->
                ListItem(
                    headlineContent = { Text(p.name) },
                    supportingContent = {
                        Text(
                            when {
                                p.name == active -> "Active"
                                p.isDefault -> "Default"
                                else -> "Tap to switch"
                            },
                        )
                    },
                    trailingContent = if (p.name == active) ({ Text("✓") }) else null,
                    modifier = Modifier.clickable { vm.switchProfile(p.name) },
                )
            }
        }
    }
}
