package com.hermes.client.notifications

/** What a Quick Settings tile tap should do, given current state. */
enum class TileAction { ENABLE, DISABLE, OPEN_FOR_PERMISSION }

/**
 * Pure: decide the tile-tap action. [canStart] = the service can be started now (API < 33, or
 * POST_NOTIFICATIONS granted). On → turn off; off and can start → on; off and can't start (a tile
 * can't request a runtime permission) → route into the app to grant it.
 */
fun tileClickAction(enabled: Boolean, canStart: Boolean): TileAction = when {
    enabled -> TileAction.DISABLE
    canStart -> TileAction.ENABLE
    else -> TileAction.OPEN_FOR_PERMISSION
}
