package com.hermes.client.data.network

import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GatewayHealthMonitorTest {
    private val api = mockk<HermesRestApi>()
    private class FakeConnectivity(var online: Boolean = true) : ConnectivityChecker {
        override fun isOnline() = online
    }

    private fun ok() = GatewayStatusDto(version = "1.2.3", gatewayRunning = true, gatewayState = "running")

    @Test fun probe_reports_healthy_on_2xx() = runTest {
        coEvery { api.gatewayStatus() } returns ok()
        val m = GatewayHealthMonitor(api, FakeConnectivity(true), MutableStateFlow(ConnectionState.Connected), backgroundScope)
        m.probe()
        val h = m.health.value
        assertTrue(h is GatewayHealth.Healthy)
        assertEquals("1.2.3", (h as GatewayHealth.Healthy).version)
        assertTrue(h.running)
    }

    @Test fun probe_reports_device_offline_without_calling_api() = runTest {
        val conn = FakeConnectivity(online = false)
        val m = GatewayHealthMonitor(api, conn, MutableStateFlow(ConnectionState.Connected), backgroundScope)
        m.probe()
        assertEquals(GatewayHealth.DeviceOffline, m.health.value)
        io.mockk.coVerify(exactly = 0) { api.gatewayStatus() }
    }

    @Test fun probe_reports_gateway_unreachable_when_both_attempts_fail() = runTest {
        coEvery { api.gatewayStatus() } throws RuntimeException("timeout")
        val m = GatewayHealthMonitor(api, FakeConnectivity(true), MutableStateFlow(ConnectionState.Connected), backgroundScope)
        m.probe()
        assertTrue(m.health.value is GatewayHealth.GatewayUnreachable)
    }

    @Test fun transient_first_failure_then_success_stays_healthy() = runTest {
        (coEvery { api.gatewayStatus() } throws RuntimeException("blip")).andThen(ok())
        val m = GatewayHealthMonitor(api, FakeConnectivity(true), MutableStateFlow(ConnectionState.Connected), backgroundScope)
        m.probe()
        assertTrue(m.health.value is GatewayHealth.Healthy)
    }

    @Test fun unauthorized_is_reported_without_retry() = runTest {
        coEvery { api.gatewayStatus() } throws HermesApiException(401, "unauthorized")
        val m = GatewayHealthMonitor(api, FakeConnectivity(true), MutableStateFlow(ConnectionState.Connected), backgroundScope)
        m.probe()
        val h = m.health.value
        assertTrue(h is GatewayHealth.GatewayUnreachable)
        assertEquals("unauthorized", (h as GatewayHealth.GatewayUnreachable).detail)
        io.mockk.coVerify(exactly = 1) { api.gatewayStatus() }
    }

    @Test fun genuine_cancellation_propagates_and_does_not_mark_unreachable() = runTest {
        coEvery { api.gatewayStatus() } throws kotlinx.coroutines.CancellationException("cancelled")
        val m = GatewayHealthMonitor(api, FakeConnectivity(true), MutableStateFlow(ConnectionState.Connected), backgroundScope)
        var threw = false
        try {
            m.probe()
        } catch (e: kotlinx.coroutines.CancellationException) {
            threw = true
        }
        assertTrue(threw)
        assertTrue(m.health.value is GatewayHealth.Unknown) // never set to a down state
    }

    @Test fun recovery_from_unreachable_to_healthy() = runTest {
        (coEvery { api.gatewayStatus() } throws RuntimeException("down")).andThenThrows(RuntimeException("down")).andThen(ok())
        val m = GatewayHealthMonitor(api, FakeConnectivity(true), MutableStateFlow(ConnectionState.Connected), backgroundScope)
        m.probe() // both attempts fail -> unreachable
        assertTrue(m.health.value is GatewayHealth.GatewayUnreachable)
        m.probe() // next probe succeeds
        assertTrue(m.health.value is GatewayHealth.Healthy)
    }

    // Uses UnconfinedTestDispatcher: with this project's kotlinx-coroutines-test 1.11.0 /
    // Kotlin 2.3.10 pairing, StandardTestDispatcher (the runTest default) never dispatches a
    // backgroundScope.launch child via advanceUntilIdle() — reproduced with a minimal
    // backgroundScope.launch { flow.collect {} } case outside this class. Unconfined avoids it;
    // the assertions below are unchanged from the brief.
    @Test fun ws_disconnect_triggers_a_probe() = runTest(UnconfinedTestDispatcher()) {
        coEvery { api.gatewayStatus() } returns ok()
        val conn = MutableStateFlow<ConnectionState>(ConnectionState.Connected)
        val m = GatewayHealthMonitor(api, FakeConnectivity(true), conn, backgroundScope)
        advanceUntilIdle()
        conn.value = ConnectionState.Disconnected
        advanceUntilIdle()
        assertTrue(m.health.value is GatewayHealth.Healthy)
        io.mockk.coVerify(atLeast = 1) { api.gatewayStatus() }
    }
}
