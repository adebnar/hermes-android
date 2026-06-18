package com.hermes.client.data.auth

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EncryptedCredentialStoreTest {
    private val store = EncryptedCredentialStore(ApplicationProvider.getApplicationContext())

    @Test fun save_then_load_round_trips() {
        store.clear()
        store.save(GatewayConfig(baseUrl = "http://hermes-mac:9119", token = "abc"))
        val loaded = store.load()!!
        assertEquals("http://hermes-mac:9119", loaded.baseUrl)
        assertEquals("abc", loaded.token)
        assertEquals("ws://hermes-mac:9119/api/ws?token=abc", loaded.wsUrl)
    }

    @Test fun clear_removes_config() {
        store.save(GatewayConfig("http://x:1", "t"))
        store.clear()
        assertNull(store.load())
    }
}
