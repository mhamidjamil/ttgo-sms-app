package com.spotwire.app.presentation.history

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.spotwire.app.core.theme.*
import com.spotwire.app.core.utils.DateUtils
import com.spotwire.app.data.local.PreferencesDataSource
import com.spotwire.app.domain.model.HistoryEntry
import com.spotwire.app.domain.model.SmsStatus
import com.spotwire.app.presentation.auto.AutoHistorySection
import org.koin.androidx.compose.koinViewModel

private enum class HistoryFilter(val label: String) {
    AUTOMATED("Automated"),
    MANUAL("Manual"),
}

/**
 * Single History page for both message types. Manual and automated messages are
 * both history, so they share one destination and a filter instead of two tabs.
 * Which half opens first is the user's choice, made on the Profile page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(viewModel: HistoryViewModel = koinViewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    // The stored preference arrives a moment after the first composition, so it
    // is the key here: it changes once, from the placeholder to the real choice,
    // and after that the user's taps stick.
    var filter by remember(uiState.defaultTab) {
        mutableStateOf(
            if (uiState.defaultTab == PreferencesDataSource.HISTORY_TAB_MANUAL) HistoryFilter.MANUAL
            else HistoryFilter.AUTOMATED
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text("History", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(10.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            HistoryFilter.entries.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = filter == option,
                    onClick = { filter = option },
                    shape = SegmentedButtonDefaults.itemShape(index, HistoryFilter.entries.size),
                ) { Text(option.label) }
            }
        }
        Spacer(Modifier.height(12.dp))
        when (filter) {
            // Each half keeps its own ViewModel, so switching the filter does not
            // reload the other list or restart its status polling.
            HistoryFilter.MANUAL -> ManualHistorySection(viewModel)
            HistoryFilter.AUTOMATED -> AutoHistorySection()
        }
    }
}

@Composable
private fun ManualHistorySection(viewModel: HistoryViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    DisposableEffect(Unit) {
        viewModel.startPolling()
        onDispose { viewModel.stopPolling() }
    }
    ManualHistoryContent(
        uiState = uiState,
        onRefresh = viewModel::refreshEntry,
        onRetry = viewModel::retry,
    )
}

@Composable
private fun ManualHistoryContent(
    uiState: HistoryUiState,
    onRefresh: (HistoryEntry) -> Unit,
    onRetry: (HistoryEntry) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Text(
            "Messages you composed and sent yourself",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(12.dp))
        when {
            uiState.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            uiState.entries.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No messages sent yet", style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            }
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(uiState.entries, key = { it.id }) { entry ->
                    HistoryCard(
                        entry = entry,
                        isRefreshing = entry.id in uiState.refreshingIds,
                        isRetrying = entry.id in uiState.retryingIds,
                        onRefresh = { onRefresh(entry) },
                        onRetry = { onRetry(entry) },
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryCard(
    entry: HistoryEntry,
    isRefreshing: Boolean,
    isRetrying: Boolean,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Text(entry.phoneNumber, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    entry.message.take(120) + if (entry.message.length > 120) "…" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusChip(entry.status)
                    // Which road it took. Two routes now carry these, and "it
                    // failed" means very different things on each.
                    Text(
                        if (entry.isWhatsApp) "WhatsApp" else "Text",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                    Text(
                        DateUtils.formatTimestamp(entry.enqueuedAt?.toDate()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                }
                // The reason, in the words of whatever refused it. A failure with
                // no reason attached is a support conversation waiting to happen.
                if (entry.error.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        entry.error,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (entry.status == SmsStatus.FAILED) {
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = onRetry, enabled = !isRetrying) {
                        if (isRetrying) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        else Text("Try again")
                    }
                }
            }
            IconButton(onClick = onRefresh, enabled = !isRefreshing) {
                if (isRefreshing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.Refresh, contentDescription = "Refresh status",
                    tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun StatusChip(status: SmsStatus) {
    val (color, label) = when (status) {
        SmsStatus.PENDING -> StatusPending to "Pending"
        SmsStatus.IN_PROGRESS -> StatusInProgress to "Sending"
        SmsStatus.SENT -> StatusSent to "Sent ✓"
        SmsStatus.FAILED -> StatusFailed to "Failed"
        SmsStatus.BLOCKED -> StatusBlocked to "Blocked"
        SmsStatus.UNKNOWN -> StatusUnknown to "Unknown"
    }
    Surface(color = color.copy(alpha = 0.15f), shape = MaterialTheme.shapes.small) {
        Text(label, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall, color = color)
    }
}

// ── Preview helpers ───────────────────────────────────────────────────────────

private val sampleEntries = listOf(
    HistoryEntry("1", "+923001234567", "Meeting confirmed for tomorrow at 10am", SmsStatus.SENT, null, "+923001234567", "app:uid"),
    HistoryEntry("2", "+923009876543", "Can you call me back?", SmsStatus.PENDING, null, "+923009876543", "app:uid"),
    HistoryEntry("3", "+923001111111", "Happy birthday!", SmsStatus.IN_PROGRESS, null, "+923001111111", "app:uid"),
    HistoryEntry("4", "+923002222222", "Blocked number test", SmsStatus.BLOCKED, null, "+923002222222", "app:uid"),
)

@Preview(showBackground = true, name = "History — With entries")
@Composable
private fun HistoryWithEntriesPreview() {
    SpotwireTheme {
        ManualHistoryContent(uiState = HistoryUiState(entries = sampleEntries, isLoading = false), onRefresh = {}, onRetry = {})
    }
}

@Preview(showBackground = true, name = "History — Empty")
@Composable
private fun HistoryEmptyPreview() {
    SpotwireTheme {
        ManualHistoryContent(uiState = HistoryUiState(entries = emptyList(), isLoading = false), onRefresh = {}, onRetry = {})
    }
}

@Preview(showBackground = true, name = "History — Loading")
@Composable
private fun HistoryLoadingPreview() {
    SpotwireTheme {
        ManualHistoryContent(uiState = HistoryUiState(isLoading = true), onRefresh = {}, onRetry = {})
    }
}
