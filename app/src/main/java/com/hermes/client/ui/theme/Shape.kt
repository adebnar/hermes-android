package com.hermes.client.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// Rounder corner scale to match the Rounded icon set (resolved decision, 2026-07-02). These
// feed Material3 defaults (cards, chips, menus, text fields) app-wide; call sites that need a
// bespoke radius still can, but most should read `MaterialTheme.shapes.*` for consistency.

val HermesShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)
