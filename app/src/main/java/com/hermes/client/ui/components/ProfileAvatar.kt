package com.hermes.client.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hermes.client.ui.theme.rememberProfileAccent

/** A lettered, per-tenant-coloured avatar token (the profile's initial in its accent). */
@Composable
fun ProfileAvatar(name: String?, modifier: Modifier = Modifier, size: Dp = 28.dp) {
    val accent = rememberProfileAccent(name, isSystemInDarkTheme())
    Box(
        modifier.size(size).clip(CircleShape).background(accent.accent),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            (name ?: "·").take(1).uppercase(),
            color = accent.onAccent,
            style = if (size >= 40.dp) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge,
        )
    }
}
