package com.hermes.client.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.roundToInt

private val ThumbSize = 56.dp

// Pure, unit-tested. Progress along the track (0f..1f).
fun slideProgress(dragPx: Float, trackPx: Float): Float =
    if (trackPx <= 0f) 0f else dragPx.coerceIn(0f, trackPx) / trackPx

fun isConfirmed(progress: Float): Boolean = progress >= 0.9f

/** Drag the thumb across the track to confirm a deliberate (dangerous) action. */
@Composable
fun SlideToConfirm(label: String, accent: Color, onConfirm: () -> Unit, modifier: Modifier = Modifier) {
    var trackPx by remember { mutableFloatStateOf(0f) }
    var dragPx by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    // pointerInput(Unit) captures its lambdas once; wrap onConfirm so a drag-end always calls the
    // CURRENT lambda (the caller swaps it when the Once/Session toggle flips) rather than a stale one.
    val currentOnConfirm by rememberUpdatedState(onConfirm)
    Box(
        modifier
            .fillMaxWidth()
            .height(ThumbSize)
            .onSizeChanged { size ->
                val thumbPx = with(density) { ThumbSize.toPx() }
                trackPx = max(0f, size.width.toFloat() - thumbPx)
            }
            .background(accent.copy(alpha = 0.15f), CircleShape),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(label, modifier = Modifier.fillMaxWidth(), color = accent)
        Box(
            Modifier
                .size(ThumbSize)
                .offset { IntOffset(dragPx.roundToInt(), 0) }
                .background(accent, CircleShape)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = { if (isConfirmed(slideProgress(dragPx, trackPx))) currentOnConfirm() else dragPx = 0f },
                        onHorizontalDrag = { _, delta -> dragPx = (dragPx + delta).coerceIn(0f, trackPx) },
                    )
                },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Color.White) }
    }
}
