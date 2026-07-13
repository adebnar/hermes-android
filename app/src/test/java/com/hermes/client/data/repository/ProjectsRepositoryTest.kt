package com.hermes.client.data.repository

import com.hermes.client.data.network.HermesGatewayClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProjectsRepositoryTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun tree_calls_projects_tree_and_parses_nodes() = runTest {
        val client = mockk<HermesGatewayClient>(relaxed = true)
        coEvery { client.call(any(), any()) } returns json.parseToJsonElement(
            """{ "projects": [ { "id": "p1", "label": "Alpha", "isAuto": false, "sessionCount": 2 } ],
                 "active_id": "p1" }""",
        )
        val repo = ProjectsRepository(client, json)

        val tree = repo.tree()

        assertEquals("p1", tree.activeId)
        assertEquals("Alpha", tree.projects.single().label)
        coVerify { client.call("projects.tree", any()) }
    }

    @Test fun tree_passes_preview_limit_param() = runTest {
        val client = mockk<HermesGatewayClient>(relaxed = true)
        coEvery { client.call(any(), any()) } returns json.parseToJsonElement("""{"projects":[]}""")
        val repo = ProjectsRepository(client, json)

        repo.tree(previewLimit = 5)

        coVerify { client.call("projects.tree", match { it["preview_limit"]!!.jsonPrimitive.content == "5" }) }
    }

    @Test fun projectSessions_calls_rpc_with_id_and_parses_project() = runTest {
        val client = mockk<HermesGatewayClient>(relaxed = true)
        coEvery { client.call(any(), any()) } returns json.parseToJsonElement(
            """{ "project": { "id": "p1", "label": "Alpha", "isAuto": false, "sessionCount": 1,
                 "repos": [], "previewSessions": [] } }""",
        )
        val repo = ProjectsRepository(client, json)

        val project = repo.projectSessions("p1")

        assertEquals("Alpha", project?.label)
        coVerify { client.call("projects.project_sessions", match { it["project_id"]!!.jsonPrimitive.content == "p1" }) }
    }

    @Test fun projectSessions_returns_null_when_project_absent() = runTest {
        val client = mockk<HermesGatewayClient>(relaxed = true)
        coEvery { client.call(any(), any()) } returns json.parseToJsonElement("""{"project":null}""")
        val repo = ProjectsRepository(client, json)

        assertNull(repo.projectSessions("nope"))
    }
}
