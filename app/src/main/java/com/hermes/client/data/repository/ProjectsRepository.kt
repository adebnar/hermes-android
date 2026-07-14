package com.hermes.client.data.repository

import com.hermes.client.data.network.HermesGatewayClient
import com.hermes.client.data.network.ProjectSessionsResultDto
import com.hermes.client.data.network.ProjectTreeDto
import com.hermes.client.domain.Project
import com.hermes.client.domain.ProjectTree
import com.hermes.client.domain.toDomain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Browse-only access to the gateway's server-authoritative project tree over WS JSON-RPC.
 * Single-profile: `projects.tree` reflects the connected gateway's bound profile and takes no
 * profile param (see the design's profile-scoping note). Piggybacks on the socket the sessions
 * screen already opens via [ChatRepository.connect].
 */
class ProjectsRepository(
    private val client: HermesGatewayClient,
    private val json: Json,
) {
    /** Fetch the project overview (nodes with counts + up to [previewLimit] preview sessions). */
    suspend fun tree(previewLimit: Int = 3): ProjectTree {
        val result = client.call("projects.tree", buildJsonObject { put("preview_limit", previewLimit) })
        return json.decodeFromJsonElement(ProjectTreeDto.serializer(), result).toDomain()
    }

    /** Hydrate one project's sessions for drill-in. [projectId] must equal a tree node id. */
    suspend fun projectSessions(projectId: String): Project? {
        val result = client.call("projects.project_sessions", buildJsonObject { put("project_id", projectId) })
        return json.decodeFromJsonElement(ProjectSessionsResultDto.serializer(), result).project?.toDomain()
    }
}
