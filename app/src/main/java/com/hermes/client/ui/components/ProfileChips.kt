package com.hermes.client.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A single, shared tenant-switcher: a horizontal row of profile chips with the active one
 * selected. Used identically on the Chats and Agent Activity tabs so switching profiles looks
 * and behaves the same everywhere. [onSelect] receives the chosen profile name.
 */
@Composable
fun ProfileChips(
    names: List<String>,
    active: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        names.forEach { name ->
            FilterChip(
                selected = name == active,
                onClick = { onSelect(name) },
                label = { Text(name) },
            )
            Spacer(Modifier.width(8.dp))
        }
    }
}
