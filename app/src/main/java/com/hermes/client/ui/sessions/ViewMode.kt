package com.hermes.client.ui.sessions

import com.hermes.client.domain.Session

/** Which list the Chats screen shows: a flat recency list, or the gateway's project tree. */
enum class ViewMode { SESSIONS, PROJECTS }

/** Flat, most-recent-first order for Sessions mode. Sessions with no [Session.lastActive] sort last. */
fun sessionsByRecency(sessions: List<Session>): List<Session> =
    sessions.sortedByDescending { it.lastActive ?: Long.MIN_VALUE }
