package com.textgate.app.presentation.history

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
import com.textgate.app.core.theme.*
import com.textgate.app.core.utils.DateUtils
import com.textgate.app.domain.model.HistoryEntry
import com.textgate.app.domain.model.SmsStatus
import org.koin.androidx.compose.koinViewModel

@Composable
fun HistoryScreen(viewModel: HistoryViewModel = koinViewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    DisposableEffect(Unit) {
        viewModel.startPolling()
        onDispose { viewModel.stopPolling() }
    }
    HistoryContent(
        uiState = uiState,
        onRefresh = viewModel::refreshEntry,
    )
}

@Composable
private fun HistoryContent(
    uiState: HistoryUiState,
    onRefresh: (HistoryEntry) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text("SMS History", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
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
                        onRefresh = { onRefresh(entry) },
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
    onRefresh: () -> Unit,
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
                    Text(
                        DateUtils.formatTimestamp(entry.enqueuedAt?.toDate()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
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
    TextGateTheme {
        HistoryContent(uiState = HistoryUiState(entries = sampleEntries, isLoading = false), onRefresh = {})
    }
}

@Preview(showBackground = true, name = "History — Empty")
@Composable
private fun HistoryEmptyPreview() {
    TextGateTheme {
        HistoryContent(uiState = HistoryUiState(entries = emptyList(), isLoading = false), onRefresh = {})
    }
}

@Preview(showBackground = true, name = "History — Loading")
@Composable
private fun HistoryLoadingPreview() {
    TextGateTheme {
        HistoryContent(uiState = HistoryUiState(isLoading = true), onRefresh = {})
    }
}
