package com.hermes.client.di

import android.content.Context
import com.hermes.client.data.auth.CredentialStore
import com.hermes.client.data.auth.EncryptedCredentialStore
import com.hermes.client.data.network.GatedAuth
import com.hermes.client.data.network.GatedAuthenticator
import com.hermes.client.data.network.HermesGatewayClient
import com.hermes.client.data.network.HermesRestApi
import com.hermes.client.data.repository.ChatRepository
import com.hermes.client.data.repository.ModelRepository
import com.hermes.client.data.repository.ProfileRepository
import com.hermes.client.data.repository.ProjectsRepository
import com.hermes.client.data.repository.SessionRepository
import com.hermes.client.data.repository.ToolsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Provides
    @Singleton
    fun provideGatedAuth(json: Json, store: CredentialStore): GatedAuth =
        GatedAuth(json) { store.load() }

    @Provides
    @Singleton
    fun provideOkHttpClient(gatedAuth: GatedAuth): OkHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        // Gated-dashboard auth: the cookie jar carries the session cookies on every REST call,
        // and the authenticator re-logs-in and retries on a 401. In loopback/token mode the jar
        // stays empty and the authenticator is a no-op (no username configured).
        .cookieJar(gatedAuth.cookieJar)
        .authenticator(GatedAuthenticator(gatedAuth))
        .build()

    @Provides
    @Singleton
    fun provideCredentialStore(@ApplicationContext context: Context): CredentialStore =
        EncryptedCredentialStore(context)

    @Provides
    @Singleton
    fun provideAppScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Provides
    @Singleton
    fun provideHermesGatewayClient(
        okHttp: OkHttpClient,
        json: Json,
        scope: CoroutineScope,
        store: CredentialStore,
        gatedAuth: GatedAuth,
    ): HermesGatewayClient = HermesGatewayClient(
        okHttp = okHttp,
        json = json,
        scope = scope,
        // Gated mode mints a fresh single-use WS ticket per connect; loopback mode appends the
        // session token. The ticket POST goes through the authenticated client, so a missing
        // session is recovered (401 → login → retry) before the socket opens.
        wsUrlProvider = {
            val cfg = store.load() ?: error("no gateway configured")
            if (cfg.isGated) {
                val ticket = withContext(Dispatchers.IO) { gatedAuth.wsTicket(okHttp) }
                    ?: error("ws ticket unavailable")
                "${cfg.wsBase}?ticket=$ticket"
            } else {
                cfg.wsUrl
            }
        },
    )

    @Provides
    @Singleton
    fun provideHermesRestApi(
        okHttp: OkHttpClient,
        json: Json,
        store: CredentialStore,
    ): HermesRestApi = HermesRestApi(
        okHttp = okHttp,
        json = json,
        configProvider = { store.load() },
    )

    @Provides
    @Singleton
    fun provideChatRepository(client: HermesGatewayClient): ChatRepository =
        ChatRepository(client)

    @Provides
    @Singleton
    fun provideProjectsRepository(
        client: HermesGatewayClient,
        json: Json,
    ): ProjectsRepository =
        ProjectsRepository(client, json)

    @Provides
    @Singleton
    fun provideSessionRepository(rest: HermesRestApi): SessionRepository =
        SessionRepository(rest)

    @Provides
    @Singleton
    fun provideModelRepository(rest: HermesRestApi): ModelRepository =
        ModelRepository(rest)

    @Provides
    @Singleton
    fun provideProfileRepository(rest: HermesRestApi): ProfileRepository =
        ProfileRepository(rest)

    @Provides
    @Singleton
    fun provideToolsRepository(rest: HermesRestApi): ToolsRepository =
        ToolsRepository(rest)

    @Provides
    @Singleton
    fun providePinStore(@ApplicationContext context: Context): com.hermes.client.data.repository.PinStore =
        com.hermes.client.data.repository.PinStore(context)

    @Provides
    @Singleton
    fun provideGroupExpansionStore(
        @ApplicationContext context: Context,
    ): com.hermes.client.data.repository.GroupExpansionStore =
        com.hermes.client.data.repository.GroupExpansionStore(context)

    @Provides
    @Singleton
    fun provideViewModeStore(
        @ApplicationContext context: Context,
    ): com.hermes.client.data.repository.ViewModeStore =
        com.hermes.client.data.repository.ViewModeStore(context)

    @Provides
    @Singleton
    fun provideProfileAccentStore(
        @ApplicationContext context: Context,
    ): com.hermes.client.data.repository.ProfileAccentStore =
        com.hermes.client.data.repository.ProfileAccentStore(context)

    @Provides
    @Singleton
    fun provideModelFavoritesStore(
        @ApplicationContext context: Context,
    ): com.hermes.client.data.repository.ModelFavoritesStore =
        com.hermes.client.data.repository.ModelFavoritesStore(context)

    @Provides
    @Singleton
    fun provideNotificationSettings(
        @ApplicationContext context: Context,
    ): com.hermes.client.data.repository.NotificationSettings =
        com.hermes.client.data.repository.NotificationSettings(context)

    @Provides
    @Singleton
    fun provideHermesNotifier(
        @ApplicationContext context: Context,
    ): com.hermes.client.notifications.HermesNotifier =
        com.hermes.client.notifications.HermesNotifier(context)

    @Provides
    @Singleton
    fun provideAnalyticsRepository(rest: HermesRestApi): com.hermes.client.data.repository.AnalyticsRepository =
        com.hermes.client.data.repository.AnalyticsRepository(rest)

    @Provides
    @Singleton
    fun provideSettingsStore(@ApplicationContext context: Context): com.hermes.client.data.repository.SettingsStore =
        com.hermes.client.data.repository.SettingsStore(context)

    @Provides
    @Singleton
    fun provideConfigRepository(rest: HermesRestApi): com.hermes.client.data.repository.ConfigRepository =
        com.hermes.client.data.repository.ConfigRepository(rest)

    @Provides
    @Singleton
    fun provideEnvRepository(rest: HermesRestApi): com.hermes.client.data.repository.EnvRepository =
        com.hermes.client.data.repository.EnvRepository(rest)
}
