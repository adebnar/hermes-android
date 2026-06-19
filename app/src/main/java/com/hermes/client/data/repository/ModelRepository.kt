package com.hermes.client.data.repository

import com.hermes.client.data.network.HermesRestApi
import com.hermes.client.data.network.ModelOptionDto
import com.hermes.client.data.network.ModelProviderDto

class ModelRepository(private val rest: HermesRestApi) {
    suspend fun options(): List<ModelOptionDto> = rest.modelOptions()
    suspend fun providers(): List<ModelProviderDto> = rest.modelProviders()
    suspend fun set(provider: String, model: String) = rest.setModel(provider, model)
}
