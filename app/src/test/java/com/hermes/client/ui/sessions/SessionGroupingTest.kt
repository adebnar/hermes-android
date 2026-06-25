package com.hermes.client.ui.sessions

import com.hermes.client.data.repository.GroupExpansionStore
import com.hermes.client.domain.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionGroupingTest {
    private fun s(id: String, profile: String, workspace: String) = Session(
        id = id, title = id, model = null, provider = null, messageCount = 1,
        profile = profile, workspace = workspace,
    )

    private val sessions = listOf(
        s("a", "personal", "app"),
        s("b", "personal", "app"),
        s("c", "personal", "docs"),
        s("d", "odos", "infra"),
    )

    @Test fun groups_two_tiers_with_active_profile_first() {
        val tree = groupSessions(sessions, collapsed = emptySet(), activeProfile = "odos")

        // Active profile sorts first even though it has fewer sessions.
        assertEquals(listOf("odos", "personal"), tree.map { it.profile })
        val personal = tree.first { it.profile == "personal" }
        assertEquals(3, personal.count)
        assertEquals(listOf("app", "docs"), personal.workspaces.map { it.workspace })
        assertEquals(listOf("a", "b"), personal.workspaces.first { it.workspace == "app" }.sessions.map { it.id })
    }

    @Test fun collapsed_profile_hides_all_workspaces_and_rows() {
        val collapsed = setOf(GroupExpansionStore.profileKey("personal"))
        val tree = groupSessions(sessions, collapsed, activeProfile = null)

        val personal = tree.first { it.profile == "personal" }
        assertTrue(personal.collapsed)
        assertTrue("a collapsed profile renders no workspaces/rows", personal.workspaces.isEmpty())
        // Count still reflects reality so the header reads "3".
        assertEquals(3, personal.count)
    }

    @Test fun collapsed_workspace_hides_its_rows_but_keeps_count() {
        val collapsed = setOf(GroupExpansionStore.workspaceKey("personal", "app"))
        val tree = groupSessions(sessions, collapsed, activeProfile = null)

        val app = tree.first { it.profile == "personal" }.workspaces.first { it.workspace == "app" }
        assertTrue(app.collapsed)
        assertTrue("collapsed workspace hides rows", app.sessions.isEmpty())
        assertEquals("but the count is preserved", 2, app.count)

        val docs = tree.first { it.profile == "personal" }.workspaces.first { it.workspace == "docs" }
        assertFalse("a sibling workspace stays expanded", docs.collapsed)
        assertEquals(listOf("c"), docs.sessions.map { it.id })
    }
}
