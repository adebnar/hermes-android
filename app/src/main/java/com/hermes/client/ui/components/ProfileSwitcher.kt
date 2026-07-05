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

/** Tenant switcher: a row of chips, each with the profile's coloured initial. One consistent
 *  control across Home / Chats / You. Same contract as the previous per-screen chip rows. */
@Composable
fun ProfileSwitcher(
    names: List<String>,
    active: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        names.forEach { name ->
            FilterChip(
                selected = name == active,
                onClick = { onSelect(name) },
                label = { Text(name) },
                leadingIcon = { ProfileAvatar(name, size = 18.dp) },
            )
            Spacer(Modifier.width(8.dp))
        }
    }
}
