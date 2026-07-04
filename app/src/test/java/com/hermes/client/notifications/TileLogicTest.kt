package com.hermes.client.notifications

import org.junit.Assert.assertEquals
import org.junit.Test

class TileLogicTest {
    @Test fun enabled_disables_regardless_of_canStart() {
        assertEquals(TileAction.DISABLE, tileClickAction(enabled = true, canStart = true))
        assertEquals(TileAction.DISABLE, tileClickAction(enabled = true, canStart = false))
    }

    @Test fun disabled_and_can_start_enables() {
        assertEquals(TileAction.ENABLE, tileClickAction(enabled = false, canStart = true))
    }

    @Test fun disabled_and_cannot_start_routes_for_permission() {
        assertEquals(TileAction.OPEN_FOR_PERMISSION, tileClickAction(enabled = false, canStart = false))
    }
}
