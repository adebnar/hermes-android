package com.hermes.client.ui.sessions

import com.hermes.client.domain.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectDerivationTest {
    private fun s(
        id: String,
        profile: String,
        repo: String?,
        lastActive: Long?,
    ) = Session(
        id = id, title = id, model = null, provider = null, messageCount = 1,
        profile = profile, lastActive = lastActive, gitRepoRoot = repo,
    )

    @Test fun groups_sessions_by_repo_across_profiles() {
        val out = deriveProjectsFromSessions(
            listOf(
                s("a", "personal", "/u/andrew/personal/travel-business", 100),
                s("b", "personal", "/u/andrew/personal/travel-business", 200),
                s("c", "dito", "/u/andrew/work/clients/dito/Southington", 150),
            ),
        )
        // Two repo-projects (travel-business has 2 sessions, Southington 1), no loose group.
        assertEquals(2, out.size)
        val travel = out.single { it.id == "/u/andrew/personal/travel-business" }
        assertEquals("travel-business", travel.label)
        assertEquals(2, travel.sessionCount)
        assertEquals(2, travel.repos.single().lanes.single().sessions.size)
    }

    @Test fun spans_profiles_within_one_repo() {
        val out = deriveProjectsFromSessions(
            listOf(
                s("p", "personal", "/u/shared", 10),
                s("o", "odos", "/u/shared", 20),
            ),
        )
        val proj = out.single()
        assertEquals(2, proj.sessionCount)
        // Sessions retain their own profile so the UI can badge tenants / open against the right DB.
        assertEquals(setOf("personal", "odos"), proj.repos.single().lanes.single().sessions.map { it.profile }.toSet())
    }

    @Test fun sessions_without_a_repo_collapse_into_no_project_last() {
        val out = deriveProjectsFromSessions(
            listOf(
                s("x", "personal", "/u/andrew/personal/inbound", 300),
                s("loose1", "personal", null, 50),
                s("loose2", "odos", "", 60),
            ),
        )
        assertEquals(2, out.size)
        assertEquals("__no_project__", out.last().id) // loose group sorts last
        assertEquals("No project", out.last().label)
        assertEquals(2, out.last().sessionCount) // null AND blank repo both loose
    }

    @Test fun orders_projects_newest_first_and_previews_recent() {
        val out = deriveProjectsFromSessions(
            listOf(
                s("old", "personal", "/u/a", 100),
                s("new", "personal", "/u/b", 999),
                s("b2", "personal", "/u/b", 500),
            ),
        )
        assertEquals(listOf("/u/b", "/u/a"), out.map { it.id }) // /u/b more recent → first
        assertEquals(listOf("new", "b2"), out.first().previewSessions.map { it.id }) // recent-first previews
    }

    @Test fun falls_back_to_cwd_when_no_git_repo_root() {
        val out = deriveProjectsFromSessions(
            listOf(
                Session(
                    id = "a", title = "a", model = null, provider = null, messageCount = 1,
                    profile = "personal", gitRepoRoot = null, cwd = "/u/andrew/personal/travel-business",
                ),
                // Same repo, one with git root set, one only cwd → still one project (git root wins as key).
                Session(
                    id = "b", title = "b", model = null, provider = null, messageCount = 1,
                    profile = "personal", gitRepoRoot = "/u/andrew/personal/flights", cwd = "/u/andrew/personal/flights/sub",
                ),
            ),
        )
        assertEquals(
            setOf("/u/andrew/personal/travel-business", "/u/andrew/personal/flights"),
            out.map { it.id }.toSet(),
        )
        assertEquals("travel-business", out.single { it.id == "/u/andrew/personal/travel-business" }.label)
    }

    @Test fun empty_input_yields_no_projects() {
        assertTrue(deriveProjectsFromSessions(emptyList()).isEmpty())
    }
}
