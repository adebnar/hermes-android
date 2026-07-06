package com.hermes.client.ui.activity

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hermes.client.ui.theme.LocalProfileAccent

private val FILTERS = listOf(
    FeedFilter.ALL to "All",
    FeedFilter.TODAY to "Today",
    FeedFilter.UPCOMING to "Upcoming",
    FeedFilter.RECENT to "Recent",
)

/** Per-tenant-accent segmented time filter for the Home feed; counts reflect the current page. */
@Composable
fun FeedTabs(
    sections: List<ActivitySection>,
    needsYou: List<CronAlert>,
    nowMs: Long,
    selected: FeedFilter,
    onSelect: (FeedFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = LocalProfileAccent.current
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        FILTERS.forEachIndexed { i, (filter, label) ->
            val count = feedView(sections, needsYou, nowMs, filter).count
            SegmentedButton(
                selected = selected == filter,
                onClick = { onSelect(filter) },
                shape = SegmentedButtonDefaults.itemShape(i, FILTERS.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = accent.accent,
                    activeContentColor = accent.onAccent,
                ),
            ) { Text("$label $count") }
        }
    }
}
