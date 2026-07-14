package com.hermes.client.ui.sessions

import com.hermes.client.domain.Project
import com.hermes.client.domain.ProjectLane
import com.hermes.client.domain.ProjectRepo
import com.hermes.client.domain.Session

/** Group id for sessions that have no git repo (loose chats). */
const val NO_PROJECT_ID = "__no_project__"

/**
 * Cross-profile Projects derived client-side from the session list, grouped by git repo root.
 *
 * Stopgap: the gateway's `projects.tree` is pinned to the launch (default) profile and takes no
 * profile param, so it can't serve a selected tenant's projects to the phone. Until the gateway
 * gains a `profile` param, this lists every repo that has chats **across all profiles**, so the
 * phone shows real projects instead of just the default profile's one.
 *
 * Each [Project] carries all its sessions in a single lane; [Project.previewSessions] is the three
 * most-recent. Sessions keep their own [Session.profile], so opening one still resolves against the
 * right per-profile DB, and the UI can badge each project/session with its tenant. Loose sessions
 * (no git repo) collapse into one "No project" group, sorted last.
 */
fun deriveProjectsFromSessions(sessions: List<Session>): List<Project> {
    // Prefer the resolved git repo root, but fall back to the working directory — many sessions
    // never get a git_repo_root, and grouping on it alone would collapse most projects into the
    // "No project" bucket. Sessions with neither are the loose group.
    val byRepo = sessions.groupBy { it.gitRepoRoot?.ifBlank { null } ?: it.cwd?.ifBlank { null } }

    val projects = byRepo
        .filterKeys { it != null }
        .map { (repo, rows) -> buildProject(repo!!, basename(repo), rows) }
        .sortedWith(
            compareByDescending<Project> { it.lastActive ?: Long.MIN_VALUE }
                .thenBy { it.label.lowercase() },
        )

    val loose = byRepo[null].orEmpty()
    val looseProject = if (loose.isNotEmpty()) buildProject(NO_PROJECT_ID, "No project", loose) else null

    return projects + listOfNotNull(looseProject)
}

private fun buildProject(id: String, label: String, rows: List<Session>): Project {
    val byRecency = rows.sortedByDescending { it.lastActive ?: Long.MIN_VALUE }
    val path = id.takeIf { it != NO_PROJECT_ID }
    return Project(
        id = id,
        label = label,
        path = path,
        color = null,
        isAuto = true,
        sessionCount = rows.size,
        lastActive = rows.mapNotNull { it.lastActive }.maxOrNull(),
        repos = listOf(
            ProjectRepo(
                id = id,
                label = label,
                path = path,
                sessionCount = rows.size,
                lanes = listOf(
                    ProjectLane(id = "all", label = "", path = null, isMain = true, sessions = byRecency),
                ),
            ),
        ),
        previewSessions = byRecency.take(3),
    )
}

private fun basename(path: String): String =
    path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').ifBlank { path }
