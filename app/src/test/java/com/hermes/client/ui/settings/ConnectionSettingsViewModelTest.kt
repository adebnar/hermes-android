package com.hermes.client.ui.settings

import com.hermes.client.data.auth.CredentialStore
import com.hermes.client.data.auth.GatewayConfig
import com.hermes.client.data.network.HermesRestApi
import com.hermes.client.data.repository.ChatRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionSettingsViewModelTest {
    private val store = mockk<CredentialStore>(relaxed = true)
    private val rest = mockk<HermesRestApi>(relaxed = true)
    private val chat = mockk<ChatRepository>(relaxed = true)

    @Before fun setUp() { Dispatchers.setMain(StandardTestDispatcher()) }

    private fun buildVm() = ConnectionSettingsViewModel(store, rest, chat)

    @Test fun prefills_fields_from_stored_config() {
        every { store.load() } returns GatewayConfig("https://host.ts.net", "tok123")
        val vm = buildVm()
        assertEquals("https://host.ts.net", vm.state.value.url)
        assertEquals("tok123", vm.state.value.token)
    }

    @Test fun save_persists_new_config_and_reconnects() {
        every { store.load() } returns GatewayConfig("https://old", "")
        val vm = buildVm()
        vm.onUrlChange("https://new.ts.net")
        vm.onTokenChange("newtok")
        vm.save()
        verify { store.save(GatewayConfig("https://new.ts.net", "newtok")) }
        verify { chat.reconnect() }
        assertEquals(true, vm.state.value.saved)
    }

    @Test fun test_probes_with_entered_values_without_saving() = runTest {
        every { store.load() } returns null
        coEvery { rest.statusFor("https://h", "t") } returns true
        val vm = buildVm()
        vm.onUrlChange("https://h")
        vm.onTokenChange("t")
        vm.test()
        advanceUntilIdle()
        coVerify { rest.statusFor("https://h", "t") }
        verify(exactly = 0) { store.save(any()) }
        assertEquals("Connected ✓", vm.state.value.testResult)
    }
}
