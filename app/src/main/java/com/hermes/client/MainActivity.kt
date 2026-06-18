package com.hermes.client

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import com.hermes.client.data.auth.CredentialStore
import com.hermes.client.ui.nav.HermesNav
import com.hermes.client.ui.theme.HermesTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var credentialStore: CredentialStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val hasConfig = credentialStore.load() != null
        setContent {
            HermesTheme {
                Surface {
                    HermesNav(hasConfig = hasConfig)
                }
            }
        }
    }
}
