package com.hermes.client.ui.sessions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.hermes.client.domain.Project
import com.hermes.client.domain.Session
import com.hermes.client.ui.theme.LocalProfileAccent

/** Parse the gateway's "#RRGGBB" project color; fall back to the tenant accent when null/invalid. */
@Composable
private fun projectTint(color: String?): Color {
    val accent = LocalProfileAccent.current.accent
    return color?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() } ?: accent
}

/** Projects overview: one tappable card per project. */
@Composable
fun ProjectOverview(projects: List<Project>, onOpenProject: (Project) -> Unit) {
    LazyColumn(Modifier.fillMaxWidth()) {
        items(projects, key = { it.id }) { p -> ProjectCard(p, onClick = { onOpenProject(p) }) }
    }
}

/** Distinct profiles (tenants) a project's chats belong to — projects span profiles in this view. */
private fun Project.tenantProfiles(): List<String> =
    repos.asSequence()
        .flatMap { it.lanes.asSequence() }
        .flatMap { it.sessions.asSequence() }
        .mapNotNull { it.profile?.takeIf { p -> p.isNotBlank() } }
        .distinct()
        .toList()

@Composable
fun ProjectCard(project: Project, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(project.label) },
        supportingContent = {
            val tenants = project.tenantProfiles()
            val tenantText = if (tenants.isEmpty()) "" else " · ${tenants.joinToString(", ")}"
            Text("${project.sessionCount} session${if (project.sessionCount == 1) "" else "s"}$tenantText")
        },
        leadingContent = {
            Icon(Icons.Rounded.Folder, contentDescription = null, tint = projectTint(project.color), modifier = Modifier.size(24.dp))
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/**
 * Drill-in for one hydrated project: a back row, then its sessions. A single-lane project renders a
 * flat list; multi-lane projects show repo/branch sub-headers.
 */
@Composable
fun ProjectScopeView(project: Project, onBack: () -> Unit, onOpenSession: (Session) -> Unit) {
    val lanes = project.repos.flatMap { repo -> repo.lanes.map { repo to it } }
    val multi = lanes.size > 1
    LazyColumn(Modifier.fillMaxWidth()) {
        item(key = "back") {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onBack).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start,
            ) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = LocalProfileAccent.current.accent, modifier = Modifier.padding(end = 8.dp))
                Text(project.label, style = MaterialTheme.typography.titleSmall, color = LocalProfileAccent.current.accent)
            }
        }
        lanes.forEach { (repo, lane) ->
            if (multi) {
                item(key = "lane-${repo.id}-${lane.id}") {
                    Text(
                        "${repo.label} · ${lane.label}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                    )
                }
            }
            items(lane.sessions, key = { "sess-${it.id}" }) { s ->
                ListItem(
                    headlineContent = { Text(s.title) },
                    // Badge the tenant since a project can span profiles.
                    supportingContent = {
                        Text(listOfNotNull(s.profile?.takeIf { it.isNotBlank() }, s.model).joinToString(" · "))
                    },
                    modifier = Modifier.clickable { onOpenSession(s) },
                )
            }
        }
        if (lanes.isEmpty()) {
            item(key = "empty-scope") {
                Text(
                    "No sessions in this project.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}
