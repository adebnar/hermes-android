package com.hermes.client.ui.components

import com.hermes.client.data.network.GatewayHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthStripTest {
    @Test fun style_is_none_when_healthy_or_unknown() {
        assertEquals(HealthStripStyle.NONE, healthStripStyle(GatewayHealth.Unknown))
        assertEquals(HealthStripStyle.NONE, healthStripStyle(GatewayHealth.Healthy("1", true, 10)))
    }

    @Test fun style_error_for_gateway_unreachable_neutral_for_device_offline() {
        assertEquals(HealthStripStyle.ERROR, healthStripStyle(GatewayHealth.GatewayUnreachable("x")))
        assertEquals(HealthStripStyle.NEUTRAL, healthStripStyle(GatewayHealth.DeviceOffline))
    }

    @Test fun label_null_when_healthy() {
        assertNull(healthStripLabel(GatewayHealth.Healthy("1", true, 10)))
        assertNull(healthStripLabel(GatewayHealth.Unknown))
    }

    @Test fun label_distinguishes_offline_unreachable_unauthorized() {
        assertEquals("You're offline", healthStripLabel(GatewayHealth.DeviceOffline))
        assertEquals("Gateway unreachable", healthStripLabel(GatewayHealth.GatewayUnreachable("unreachable")))
        assertEquals("Gateway unauthorized", healthStripLabel(GatewayHealth.GatewayUnreachable("unauthorized")))
    }

    @Test fun sheet_body_healthy_includes_version_and_latency() {
        val body = healthSheetBody(GatewayHealth.Healthy(version = "1.2.3", running = true, latencyMs = 42))
        assertTrue(body.contains("running"))
        assertTrue(body.contains("1.2.3"))
        assertTrue(body.contains("42"))
    }

    @Test fun sheet_body_reachable_not_running_when_running_false() {
        val body = healthSheetBody(GatewayHealth.Healthy(version = null, running = false, latencyMs = null))
        assertTrue(body.contains("not running"))
    }

    @Test fun sheet_body_offline_and_unauthorized_copy() {
        assertTrue(healthSheetBody(GatewayHealth.DeviceOffline).contains("offline"))
        assertTrue(healthSheetBody(GatewayHealth.GatewayUnreachable("unauthorized")).contains("unauthorized"))
    }
}
