package com.hermes.client.data.repository

import com.hermes.client.data.network.HermesRestApi
import com.hermes.client.data.network.ModelUsageDto
import com.hermes.client.data.network.UsageDto

class AnalyticsRepository(private val rest: HermesRestApi) {
    suspend fun usage(profile: String? = null): UsageDto = rest.analyticsUsage(profile)
    suspend fun models(profile: String? = null): List<ModelUsageDto> = rest.analyticsModels(profile)
}
