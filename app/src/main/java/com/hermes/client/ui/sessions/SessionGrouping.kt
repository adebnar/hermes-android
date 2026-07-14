package com.hermes.client.ui.sessions

import com.hermes.client.data.repository.GroupExpansionStore
import com.hermes.client.domain.Session

/** Which list the Chats screen shows: a flat recency list, or the gateway's project tree. */
enum class ViewMode { SESSIONS, PROJECTS }

/**
 * A workspace sub-group within a profile. [sessions] is empty when the group is collapsed;
 * [count] always reflects the true number of rows so the header reads correctly either way.
 */
data class WorkspaceGroup(
    val workspace: String,
    val count: Int,
    val collapsed: Boolean,
    val sessions: List<Session>,
)

/**
 * A profile group (top tier). [workspaces] is empty when the profile itself is collapsed, so a
 * collapsed profile renders as just its header — hiding every workspace and row beneath it.
 */
data class ProfileGroup(
    val profile: String,
    val count: Int,
    val collapsed: Boolean,
    val workspaces: List<WorkspaceGroup>,
)

/**
 * Build the render-ready two-tier tree: Profile → Workspace → rows. The result is exactly what
 * the list should draw — collapsed groups already have their children removed — so the screen
 * stays dumb and the grouping is unit-testable. Collapse state comes from [collapsed] (a set of
 * keys built by [GroupExpansionStore]); [activeProfile] sorts first so the current tenant is on top.
 */
fun groupSessions(
    sessions: List<Session>,
    collapsed: Set<String>,
    activeProfile: String?,
): List<ProfileGroup> =
    sessions
        .groupBy { it.profile ?: "default" }
        .map { (profile, inProfile) ->
            val profileCollapsed = GroupExpansionStore.profileKey(profile) in collapsed
            val workspaces =
                if (profileCollapsed) emptyList()
                else inProfile
                    .groupBy { it.workspace }
                    .toSortedMap(compareBy({ it == "No workspace" }, { it }))
                    .map { (workspace, rows) ->
                        val wsCollapsed = GroupExpansionStore.workspaceKey(profile, workspace) in collapsed
                        WorkspaceGroup(
                            workspace = workspace,
                            count = rows.size,
                            collapsed = wsCollapsed,
                            sessions = if (wsCollapsed) emptyList() else rows,
                        )
                    }
            ProfileGroup(profile, inProfile.size, profileCollapsed, workspaces)
        }
        .sortedWith(
            compareByDescending<ProfileGroup> { it.profile == activeProfile }
                .thenByDescending { it.count }
                .thenBy { it.profile },
        )

/** Flat, most-recent-first order for Sessions mode. Sessions with no [Session.lastActive] sort last. */
fun sessionsByRecency(sessions: List<Session>): List<Session> =
    sessions.sortedByDescending { it.lastActive ?: Long.MIN_VALUE }
