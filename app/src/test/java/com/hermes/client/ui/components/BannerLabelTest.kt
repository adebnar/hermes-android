package com.hermes.client.ui.components

import com.hermes.client.data.network.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Test

class BannerLabelTest {
    @Test fun disconnected_is_friendly() {
        assertEquals(
            "You're offline — new messages send when you reconnect.",
            bannerLabel(ConnectionState.Disconnected),
        )
    }
    @Test fun error_is_error_copy() {
        assertEquals("Connection error — tap Retry.", bannerLabel(ConnectionState.Error("boom")))
    }
    @Test fun connecting_delegates_to_connectionLabel() {
        assertEquals(connectionLabel(ConnectionState.Connecting), bannerLabel(ConnectionState.Connecting))
    }
}
