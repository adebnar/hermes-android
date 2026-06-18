package com.hermes.client.ui.components

import com.hermes.client.data.network.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Test

class StatusDotTest {
    @Test fun maps_states_to_labels() {
        assertEquals("Connected", connectionLabel(ConnectionState.Connected))
        assertEquals("Connecting…", connectionLabel(ConnectionState.Connecting))
        assertEquals("Reconnecting…", connectionLabel(ConnectionState.Reconnecting))
        assertEquals("Offline", connectionLabel(ConnectionState.Disconnected))
        assertEquals("Error: boom", connectionLabel(ConnectionState.Error("boom")))
    }
}
