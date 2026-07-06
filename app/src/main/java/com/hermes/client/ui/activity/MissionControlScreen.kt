package com.hermes.client.ui.activity

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.client.ui.components.EmptyState
import com.hermes.client.ui.components.ErrorState
import com.hermes.client.ui.components.HermesTopBar
import com.hermes.client.ui.components.LoadingState
import com.hermes.client.ui.nav.ShellViewModel
import com.hermes.client.ui.theme.LocalProfileAccent
import com.hermes.client.ui.theme.rememberProfileAccent
import com.hermes.client.ui.util.relativeTime
import kotlinx.coroutines.launch

private data class QuickLink(val label: String, val icon: ImageVector, val route: String)

private val QUICK_LINKS = listOf(
    QuickLink("Cron", Icons.Rounded.Schedule, "cron"),
    QuickLink("Messaging", Icons.Rounded.Forum, "messaging"),
    QuickLink("Usage", Icons.Rounded.BarChart, "usage"),
    QuickLink("Agents", Icons.Rounded.Extension, "agents_tools"),
)

/**
 * Mission Control — the "Agent Activity" tab. Profile-spatial: a horizontal pager with one page
 * per tenant, each painted in that tenant's accent, so swiping left/right moves between tenants'
 * activity. The top bar + tenant tabs follow the current page; opening an item switches the
 * app-wide active profile to that page's tenant first. [onNavigate] opens feed items + quick links.
 */
@Composable
fun MissionControlScreen(
    onNavigate: (String) -> Unit,
    shell: ShellViewModel = hiltViewModel(),
) {
    val profiles by shell.profiles.collectAsStateWithLifecycle()
    val active by shell.active.collectAsStateWithLifecycle()
    val dark = isSystemInDarkTheme()

    // One page per profile; at least one page even before the list loads.
    val names: List<String?> = remember(profiles, active) {
        profiles.map { it.name as String? }.ifEmpty { listOf(active) }
    }

    val pagerState = rememberPagerState(
        initialPage = names.indexOf(active).coerceAtLeast(0),
        pageCount = { names.size },
    )
    val scope = rememberCoroutineScope()

    // Jump to the active profile's page once the profile list has loaded (it arrives async).
    var jumped by remember { mutableStateOf(false) }
    LaunchedEffect(names, active) {
        if (!jumped && active != null) {
            val idx = names.indexOf(active)
            if (idx >= 0) {
                pagerState.scrollToPage(idx)
                jumped = true
            }
        }
    }

    val currentProfile = names.getOrNull(pagerState.currentPage)
    // Top bar + tabs paint in the *current page's* accent, so the chrome shifts colour as you swipe.
    CompositionLocalProvider(LocalProfileAccent provides rememberProfileAccent(currentProfile, dark)) {
        Scaffold(
            topBar = {
                Column {
                    HermesTopBar(title = "Home")
                    if (names.size > 1) {
                        com.hermes.client.ui.components.ProfileSwitcher(
                            names = names.filterNotNull(),
                            active = currentProfile,
                            onSelect = { name ->
                                val idx = names.indexOf(name)
                                if (idx >= 0) scope.launch { pagerState.animateScrollToPage(idx) }
                            },
                        )
                    }
                }
            },
        ) { padding ->
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.padding(padding).fillMaxSize(),
                key = { names.getOrNull(it) ?: "_$it" },
            ) { page ->
                MissionControlPage(profile = names.getOrNull(page), dark = dark, onNavigate = onNavigate)
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun MissionControlPage(profile: String?, dark: Boolean, onNavigate: (String) -> Unit) {
    // One VM instance per tenant page, keyed by profile name.
    val vm: MissionControlViewModel = hiltViewModel(key = "mc-${profile ?: "_"}")
    val state by vm.state.collectAsStateWithLifecycle()
    val responses by vm.responses.collectAsStateWithLifecycle()
    var expandedIds by androidx.compose.runtime.saveable.rememberSaveable {
        androidx.compose.runtime.mutableStateOf(emptySet<String>())
    }
    var filter by androidx.compose.runtime.saveable.rememberSaveable {
        androidx.compose.runtime.mutableStateOf(FeedFilter.ALL)
    }
    var collapsed by androidx.compose.runtime.saveable.rememberSaveable {
        androidx.compose.runtime.mutableStateOf(emptySet<String>())
    }
    val onToggleSection: (String) -> Unit = { title ->
        collapsed = if (title in collapsed) collapsed - title else collapsed + title
    }
    val onToggle: (ActivityItem) -> Unit = { item ->
        val nowExpanded = item.id !in expandedIds
        expandedIds = if (nowExpanded) expandedIds + item.id else expandedIds - item.id
        if (nowExpanded) item.sessionId?.let { vm.loadResponse(it) }
    }
    val onRetryResponse: (ActivityItem) -> Unit = { item -> item.sessionId?.let { vm.loadResponse(it) } }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val now = remember(state.sections) { System.currentTimeMillis() }
    val onRunNow: (CronAlert) -> Unit = { alert ->
        scope.launch {
            val ok = vm.runCron(alert.jobId)
            Toast.makeText(
                context,
                if (ok) "Triggered ${alert.name}" else "Couldn't run ${alert.name}",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    LaunchedEffect(profile) { vm.load(profile) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }

    // Opening an item makes this page's tenant the active profile (awaited) before navigating, so
    // the chat/cron screen acts against the right per-profile DB.
    val onOpen: (String) -> Unit = { route -> scope.launch { vm.switchTo(profile); onNavigate(route) } }

    // Pull-to-refresh: the indicator shows only for an explicit pull (not the ON_RESUME reload),
    // and clears when the load finishes.
    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(state.loading) { if (!state.loading) refreshing = false }

    // Each page paints in its own tenant accent so peeking pages during a swipe read correctly.
    CompositionLocalProvider(LocalProfileAccent provides rememberProfileAccent(profile, dark)) {
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { refreshing = true; vm.refresh() },
        ) {
            Column {
                FeedTabs(
                    sections = state.sections,
                    needsYou = state.needsYou,
                    nowMs = now,
                    selected = filter,
                    onSelect = { filter = it },
                )
                MissionControlContent(
                    state = state,
                    view = feedView(state.sections, state.needsYou, now, filter),
                    filter = filter,
                    nowMs = now, onRetry = { vm.refresh() },
                    responses = responses, expandedIds = expandedIds,
                    onToggle = onToggle, onRetryResponse = onRetryResponse, onOpen = onOpen,
                    onRunNow = onRunNow,
                    collapsed = collapsed, onToggleSection = onToggleSection,
                )
            }
        }
    }
}

@Composable
private fun MissionControlContent(
    state: MissionControlState,
    view: FeedView,
    filter: FeedFilter,
    nowMs: Long,
    onRetry: () -> Unit,
    responses: Map<String, MissionControlViewModel.CronResponseUi>,
    expandedIds: Set<String>,
    onToggle: (ActivityItem) -> Unit,
    onRetryResponse: (ActivityItem) -> Unit,
    onOpen: (String) -> Unit,
    onRunNow: (CronAlert) -> Unit,
    collapsed: Set<String>,
    onToggleSection: (String) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        if (view.needsYou.isNotEmpty()) {
            item(key = "needs-header") {
                SectionHeader(
                    "Needs you",
                    view.needsYou.size,
                    collapsed = "Needs you" in collapsed,
                    onToggle = { onToggleSection("Needs you") },
                )
            }
            if ("Needs you" !in collapsed) {
                items(view.needsYou, key = { "needs-${it.jobId}" }) { alert ->
                    NeedsYouRow(alert, nowMs = nowMs, onClick = { onOpen(alert.route) }, onRunNow = { onRunNow(alert) })
                }
            }
        }
        when {
            state.loading && state.sections.isEmpty() -> item { LoadingState() }
            state.error != null -> item { ErrorState(message = state.error!!, onRetry = onRetry) }
            view.needsYou.isEmpty() && view.sections.isEmpty() -> item {
                if (filter == FeedFilter.ALL) {
                    EmptyState(
                        title = "Nothing happening yet",
                        subtitle = "Conversations and scheduled runs for this profile will show up here.",
                    )
                } else {
                    EmptyState(
                        title = "Nothing here",
                        subtitle = "Nothing matches this filter yet.",
                    )
                }
            }
            else -> view.sections.forEach { section ->
                item(key = "h-${section.title}") {
                    SectionHeader(
                        section.title,
                        section.items.size,
                        collapsed = section.title in collapsed,
                        onToggle = { onToggleSection(section.title) },
                    )
                }
                if (section.title !in collapsed) {
                    items(section.items, key = { it.id }) { activity ->
                        val expandable = activity.kind == ActivityKind.CRON &&
                            activity.sessionId != null && !activity.upcoming
                        ActivityRow(
                            item = activity,
                            nowMs = nowMs,
                            expandable = expandable,
                            isExpanded = activity.id in expandedIds,
                            response = activity.sessionId?.let { responses[it] },
                            onClick = { if (expandable) onToggle(activity) else onOpen(activity.route) },
                            onRetry = { onRetryResponse(activity) },
                            onOpenFull = { onOpen(activity.route) },
                        )
                    }
                }
            }
        }
        item(key = "quicklinks") {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                QUICK_LINKS.forEach { link ->
                    AssistChip(
                        onClick = { onOpen(link.route) },
                        label = { Text(link.label) },
                        leadingIcon = { Icon(link.icon, contentDescription = null, Modifier.width(18.dp)) },
                    )
                    Spacer(Modifier.width(8.dp))
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(label: String, count: Int, collapsed: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.titleSmall,
            color = LocalProfileAccent.current.accent,
            modifier = Modifier.weight(1f),
        )
        Text(
            count.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Icon(
            if (collapsed) Icons.Rounded.ExpandMore else Icons.Rounded.ExpandLess,
            contentDescription = if (collapsed) "Expand $label" else "Collapse $label",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NeedsYouRow(alert: CronAlert, nowMs: Long, onClick: () -> Unit, onRunNow: () -> Unit) {
    val reasonText = when (alert.reason) {
        CronAlertReason.FAILED -> "Last run failed"
        CronAlertReason.OVERDUE -> "Overdue"
    }
    val supporting = listOfNotNull(
        reasonText,
        alert.lastRunAtMs?.let { relativeTime(it, nowMs) },
    ).joinToString(" · ")
    ListItem(
        leadingContent = {
            Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
        },
        headlineContent = { Text(alert.name) },
        supportingContent = { Text(supporting) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onRunNow) { Text("Run now") }
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null)
            }
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun StatusPill(label: String) {
    val accent = LocalProfileAccent.current
    androidx.compose.material3.Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
        color = accent.container,
    ) {
        Text(
            label,
            Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = accent.onContainer,
        )
    }
}

@Composable
private fun ActivityRow(
    item: ActivityItem,
    nowMs: Long,
    expandable: Boolean = false,
    isExpanded: Boolean = false,
    response: MissionControlViewModel.CronResponseUi? = null,
    onClick: () -> Unit,
    onRetry: () -> Unit = {},
    onOpenFull: () -> Unit = {},
) {
    val icon: ImageVector = when {
        item.kind == ActivityKind.CONVERSATION -> Icons.AutoMirrored.Rounded.Chat
        item.upcoming -> Icons.Rounded.Schedule
        item.status.equals("success", ignoreCase = true) -> Icons.Rounded.CheckCircle
        item.status.equals("error", ignoreCase = true) ||
            item.status.equals("failed", ignoreCase = true) -> Icons.Rounded.ErrorOutline
        else -> Icons.Rounded.History
    }
    val tint = when {
        item.status.equals("error", ignoreCase = true) ||
            item.status.equals("failed", ignoreCase = true) -> MaterialTheme.colorScheme.error
        else -> LocalProfileAccent.current.accent
    }
    val time = relativeTime(item.timestampMs, nowMs)
    val pill = statusPill(item, nowMs)
    Column {
        ListItem(
            leadingContent = { Icon(icon, contentDescription = null, tint = tint) },
            headlineContent = { Text(item.title) },
            supportingContent = { Text(listOfNotNull(item.subtitle, time).joinToString(" · ")) },
            trailingContent = if (pill != null || expandable) {
                {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        pill?.let { StatusPill(it); Spacer(Modifier.width(6.dp)) }
                        if (expandable) {
                            Icon(
                                if (isExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                contentDescription = if (isExpanded) "Collapse response" else "Show response",
                            )
                        }
                    }
                }
            } else {
                null
            },
            modifier = Modifier.clickable(onClick = onClick),
        )
        if (expandable && isExpanded) {
            CronResponseCard(response = response, onRetry = onRetry, onOpenFull = onOpenFull)
        }
    }
}

@Composable
private fun CronResponseCard(
    response: MissionControlViewModel.CronResponseUi?,
    onRetry: () -> Unit,
    onOpenFull: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            when {
                response == null || response.loading ->
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                response.error -> {
                    Text("Couldn't load response", color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onRetry) { Text("Retry") }
                }
                else -> {
                    Text(
                        response.text ?: "No text output.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                    )
                    TextButton(onClick = onOpenFull) { Text("View full chat") }
                }
            }
        }
    }
}
