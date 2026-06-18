package com.hermes.client.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** A small colored dot indicating connection status. */
@Composable
fun StatusDot(
    connected: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 10.dp,
) {
    val color = if (connected) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color),
    )
}
