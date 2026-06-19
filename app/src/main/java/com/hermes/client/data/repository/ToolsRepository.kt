package com.hermes.client.data.repository

import com.hermes.client.data.network.HermesRestApi
import com.hermes.client.data.network.SkillDto
import com.hermes.client.data.network.ToolsetDto

class ToolsRepository(private val rest: HermesRestApi) {
    suspend fun skills(): List<SkillDto> = rest.skills()
    suspend fun toggleSkill(name: String, enabled: Boolean) = rest.toggleSkill(name, enabled)
    suspend fun toolsets(): List<ToolsetDto> = rest.toolsets()
}
