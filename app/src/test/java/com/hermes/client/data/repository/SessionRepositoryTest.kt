package com.hermes.client.data.repository

import com.hermes.client.data.network.HermesRestApi
import com.hermes.client.data.network.ProfileSessionsDto
import com.hermes.client.data.network.SessionDto
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionRepositoryTest {
    private val rest = mockk<HermesRestApi>()
    private val repo = SessionRepository(rest)

    private fun dto(id: String, source: String?, msgs: Int, archived: Boolean = false) =
        SessionDto(sessionId = id, source = source, messageCount = msgs, archived = archived, profile = "personal")

    // Parity with desktop SIDEBAR_EXCLUDED_SOURCES: the list hides cron, subagent, tool, and every
    // messaging-platform source, plus empty (0-message) sessions. Local sources (tui/cli/…), the
    // app's own hermes-dispatch sessions, and unknown/null sources are kept.
    @Test fun listAllProfiles_hides_excluded_sources_and_empty_sessions() = runTest {
        coEvery { rest.profileSessions(any(), false) } returns ProfileSessionsDto(
            sessions = listOf(
                dto("keep-tui", "tui", 5),
                dto("hide-cron", "cron", 12),           // cron → hidden
                dto("hide-subagent", "subagent", 8),    // subagent → hidden
                dto("hide-tool", "tool", 4),            // tool → hidden
                dto("hide-telegram", "telegram", 6),    // messaging → hidden
                dto("hide-empty", "tui", 0),            // 0 messages → hidden
                dto("keep-dispatch", "hermes-dispatch", 2),
                dto("keep-null-source", null, 3),       // unknown/null source → kept
            ),
        )
        assertEquals(
            listOf("keep-tui", "keep-dispatch", "keep-null-source"),
            repo.listAllProfiles().map { it.id },
        )
    }

    @Test fun archivedAllProfiles_also_hides_cron_and_empty() = runTest {
        coEvery { rest.profileSessions(any(), true) } returns ProfileSessionsDto(
            sessions = listOf(
                dto("a-keep", "cli", 3, archived = true),
                dto("a-cron", "cron", 9, archived = true),
                dto("a-empty", "tui", 0, archived = true),
            ),
        )
        assertEquals(listOf("a-keep"), repo.archivedAllProfiles().map { it.id })
    }
}
