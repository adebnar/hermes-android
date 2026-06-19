package com.hermes.client.di

import android.content.Context
import com.hermes.client.data.auth.CredentialStore
import com.hermes.client.data.auth.EncryptedCredentialStore
import com.hermes.client.data.network.HermesGatewayClient
import com.hermes.client.data.network.HermesRestApi
import com.hermes.client.data.repository.ChatRepository
import com.hermes.client.data.repository.ModelRepository
import com.hermes.client.data.repository.ProfileRepository
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
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
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
    ): HermesGatewayClient = HermesGatewayClient(
        okHttp = okHttp,
        json = json,
        scope = scope,
        wsUrlProvider = { store.load()?.wsUrl ?: error("no gateway configured") },
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
}
