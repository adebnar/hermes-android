package com.hermes.client.domain

import com.hermes.client.data.network.ProjectSessionsResultDto
import com.hermes.client.data.network.ProjectTreeDto
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectMappersTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val treeJson = """
    {
      "projects": [
        {
          "id": "p_ab12", "label": "Roam and Trail", "path": "/u/andrew/roam-and-trail",
          "color": "#4F8A10", "icon": "root-folder", "isAuto": false,
          "sessionCount": 3, "lastActive": 1752000000.0,
          "repos": [
            { "id": "/u/andrew/roam-and-trail", "label": "roam-and-trail", "path": "/u/andrew/roam-and-trail",
              "sessionCount": 3,
              "groups": [
                { "id": "main", "label": "main", "path": "/u/andrew/roam-and-trail",
                  "isMain": true, "isKanban": false, "sessions": [] }
              ] }
          ],
          "previewSessions": [
            { "id": "s1", "title": "Affiliate setup", "model": "gpt", "last_active": 1752000000.0,
              "message_count": 4, "cwd": "/u/andrew/roam-and-trail",
              "git_branch": "main", "git_repo_root": "/u/andrew/roam-and-trail", "source": "hermes-dispatch" }
          ]
        },
        {
          "id": "/u/andrew/inbound", "label": "inbound", "path": "/u/andrew/inbound",
          "isAuto": true, "sessionCount": 1, "lastActive": 1751990000.0,
          "repos": [],
          "previewSessions": [ { "id": "s2", "title": "Flight tracker", "message_count": 2 } ]
        }
      ],
      "active_id": "p_ab12",
      "scoped_session_ids": ["s1", "s2"]
    }
    """.trimIndent()

    @Test fun maps_full_tree_including_repos_lanes_and_previews() {
        val tree = json.decodeFromString(ProjectTreeDto.serializer(), treeJson).toDomain()

        assertEquals("p_ab12", tree.activeId)
        assertEquals(2, tree.projects.size)

        val explicit = tree.projects[0]
        assertEquals("Roam and Trail", explicit.label)
        assertEquals("#4F8A10", explicit.color)
        assertEquals(false, explicit.isAuto)
        assertEquals(3, explicit.sessionCount)
        assertEquals(1, explicit.repos.size)
        assertEquals("roam-and-trail", explicit.repos[0].label)
        assertEquals(1, explicit.repos[0].lanes.size)
        assertTrue(explicit.repos[0].lanes[0].isMain)
        // preview session mapped through Session.toDomain
        assertEquals("Affiliate setup", explicit.previewSessions.single().title)
        assertEquals("main", explicit.previewSessions.single().gitBranch)
        assertEquals("/u/andrew/roam-and-trail", explicit.previewSessions.single().cwd)
    }

    @Test fun auto_project_has_null_color_and_no_repos() {
        val tree = json.decodeFromString(ProjectTreeDto.serializer(), treeJson).toDomain()
        val auto = tree.projects[1]
        assertEquals("inbound", auto.label)
        assertNull(auto.color)
        assertTrue(auto.isAuto)
        assertTrue(auto.repos.isEmpty())
        assertEquals("Flight tracker", auto.previewSessions.single().title)
    }

    @Test fun project_sessions_result_hydrates_lane_sessions() {
        val projJson = """
        { "project": { "id": "p_ab12", "label": "Roam and Trail", "path": "/u/andrew/roam-and-trail",
          "isAuto": false, "sessionCount": 1, "lastActive": 1752000000.0,
          "repos": [ { "id": "r", "label": "roam-and-trail", "path": "/u/andrew/roam-and-trail",
            "sessionCount": 1, "groups": [ { "id": "main", "label": "main", "path": "/u",
              "isMain": true, "isKanban": false,
              "sessions": [ { "id": "s1", "title": "Affiliate setup", "model": "gpt",
                "last_active": 1752000000.0, "message_count": 4 } ] } ] } ],
          "previewSessions": [] } }
        """.trimIndent()

        val project = json.decodeFromString(ProjectSessionsResultDto.serializer(), projJson).project!!.toDomain()
        assertEquals("Affiliate setup", project.repos.single().lanes.single().sessions.single().title)
    }

    @Test fun missing_project_maps_to_null() {
        val project = json.decodeFromString(ProjectSessionsResultDto.serializer(), """{"project":null}""").project
        assertNull(project)
    }
}
