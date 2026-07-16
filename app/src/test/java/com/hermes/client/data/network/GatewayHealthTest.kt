package com.hermes.client.data.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayHealthTest {
    @Test fun healthy_and_unknown_are_not_unhealthy() {
        assertFalse(GatewayHealth.Unknown.isUnhealthy())
        assertFalse(GatewayHealth.Healthy(version = "1.2.3", running = true, latencyMs = 42).isUnhealthy())
    }

    @Test fun device_offline_and_gateway_unreachable_are_unhealthy() {
        assertTrue(GatewayHealth.DeviceOffline.isUnhealthy())
        assertTrue(GatewayHealth.GatewayUnreachable("unreachable").isUnhealthy())
    }
}
