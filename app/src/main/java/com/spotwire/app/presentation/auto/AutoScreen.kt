package com.spotwire.app.presentation.auto

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.spotwire.app.core.theme.*
import com.spotwire.app.core.utils.DateUtils
import com.spotwire.app.domain.model.AutoHistoryEntry
import com.spotwire.app.domain.model.SmsStatus
import org.koin.androidx.compose.koinViewModel
import java.util.Date

/**
 * Automated half of the merged History page. One card per arrival, however many
 * contacts that place notifies, with the per-recipient detail behind the eye
 * button. Polls job statuses while visible so "Pending" dots resolve to sent or
 * failed instead of sticking forever.
 */
@Composable
fun AutoHistorySection(viewModel: AutoViewModel = koinViewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    DisposableEffect(Unit) {
        viewModel.startPolling()
        onDispose { viewModel.stopPolling() }
    }
    AutoContent(
        uiState = uiState,
        onRefreshGroup = viewModel::refreshGroup,
        onRefreshEntry = viewModel::refreshEntry,
        onRetryEntry = viewModel::retryEntry,
    )
}

@Composable
private fun AutoContent(
    uiState: AutoUiState,
    onRefreshGroup: (AutoArrivalGroup) -> Unit,
    onRefreshEntry: (AutoHistoryEntry) -> Unit,
    onRetryEntry: (AutoHistoryEntry) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Text(
            "Notifications sent to your contacts when you arrive at a saved place",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(12.dp))

        when {
            uiState.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            uiState.groups.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "No arrivals recorded yet",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Set up arrival monitoring in the Arrival tab",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    )
                }
            }
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(uiState.groups, key = { it.key }) { group ->
                    ArrivalCard(
                        group = group,
                        busyIds = uiState.busyIds,
                        onRefreshGroup = { onRefreshGroup(group) },
                        onRefreshEntry = onRefreshEntry,
                        onRetryEntry = onRetryEntry,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArrivalCard(
    group: AutoArrivalGroup,
    busyIds: Set<String>,
    onRefreshGroup: () -> Unit,
    onRefreshEntry: (AutoHistoryEntry) -> Unit,
    onRetryEntry: (AutoHistoryEntry) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var showRecipients by remember { mutableStateOf(false) }
    val isBusy = group.entries.any { it.id in busyIds }
    // WhatsApp deliveries have no gateway job to poll, so a group made only of
    // them has nothing to reload.
    val hasSms = group.entries.any { it.channel == "sms" }

    if (showRecipients) {
        RecipientsDialog(
            group = group,
            busyIds = busyIds,
            onRefreshEntry = onRefreshEntry,
            onRetryEntry = onRetryEntry,
            onDismiss = { showRecipients = false },
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = { expanded = !expanded },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 14.dp, bottom = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Arrived at ${displayLabel(group)}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (group.entries.size == 1) "1 recipient"
                        else "${group.entries.size} recipients",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    )
                }
                if (hasSms) {
                    IconButton(onClick = onRefreshGroup, enabled = !isBusy) {
                        if (isBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Icon(
                            Icons.Default.Refresh, contentDescription = "Refresh all statuses",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                IconButton(onClick = { showRecipients = true }) {
                    Icon(
                        Icons.Default.Visibility, contentDescription = "Show recipients",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                if (expanded) group.fullMessage else group.headline,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                modifier = Modifier.padding(end = 8.dp),
            )
            if (!expanded) {
                Text(
                    "Tap to see the full message",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    DateUtils.formatTimestamp(group.sentAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
                if (group.routineTriggered) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            "Routine",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                StatusDots(group.entries)
            }
        }
    }
}

// One dot per recipient, in the order they were messaged. Green means delivered,
// amber still in flight, red failed — enough to see "four went, one did not"
// without opening anything.
@Composable
private fun StatusDots(entries: List<AutoHistoryEntry>) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        entries.forEach { entry ->
            Box(Modifier.size(10.dp).clip(CircleShape).background(statusColor(entry.status)))
        }
    }
}

@Composable
private fun RecipientsDialog(
    group: AutoArrivalGroup,
    busyIds: Set<String>,
    onRefreshEntry: (AutoHistoryEntry) -> Unit,
    onRetryEntry: (AutoHistoryEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Arrived at ${displayLabel(group)}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Everyone notified about this arrival",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                Spacer(Modifier.height(4.dp))
                group.entries.forEach { entry ->
                    RecipientRow(
                        entry = entry,
                        isBusy = entry.id in busyIds,
                        onRefresh = { onRefreshEntry(entry) },
                        onRetry = { onRetryEntry(entry) },
                    )
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun RecipientRow(
    entry: AutoHistoryEntry,
    isBusy: Boolean,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                entry.recipientName.ifBlank { entry.jobPhoneKey },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (entry.recipientName.isNotBlank()) {
                Text(
                    entry.jobPhoneKey,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            // The reason has always been stored on a failed row and never shown,
            // so "Failed" was the whole explanation the user got.
            if (entry.error.isNotBlank()) {
                Text(
                    entry.error,
                    style = MaterialTheme.typography.labelSmall,
                    color = StatusFailed.copy(alpha = 0.8f),
                )
            }
        }
        Text(
            statusLabel(entry),
            style = MaterialTheme.typography.labelMedium,
            color = statusColor(entry.status),
        )
        // A fixed slot keeps the status column aligned whether or not the row
        // has an action. Delivered rows have nothing left to do.
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            when {
                isBusy -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                entry.channel != "sms" || entry.status == SmsStatus.SENT -> Unit
                entry.status == SmsStatus.PENDING || entry.status == SmsStatus.IN_PROGRESS ->
                    IconButton(onClick = onRefresh) {
                        Icon(
                            Icons.Default.Refresh, contentDescription = "Refresh status",
                            tint = StatusPending,
                        )
                    }
                else -> IconButton(onClick = onRetry) {
                    Icon(Icons.Default.Replay, contentDescription = "Retry", tint = StatusFailed)
                }
            }
        }
    }
}

// Stored label (any custom place); legacy entries only have the id.
private fun displayLabel(group: AutoArrivalGroup): String = group.locationLabel.ifBlank {
    when (group.location) {
        "home" -> "Home"
        "office" -> "Office"
        else -> group.location
    }
}

private fun statusColor(status: SmsStatus): Color = when (status) {
    SmsStatus.PENDING -> StatusPending
    SmsStatus.IN_PROGRESS -> StatusInProgress
    SmsStatus.SENT -> StatusSent
    SmsStatus.FAILED -> StatusFailed
    SmsStatus.BLOCKED -> StatusBlocked
    SmsStatus.UNKNOWN -> StatusUnknown
}

// A row that is still only in this phone's write queue is waiting on the
// network, not on the gateway. Calling both "Pending" is what let an alert that
// never left the phone look like one already lined up to send.
private fun statusLabel(entry: AutoHistoryEntry): String =
    if (entry.status == SmsStatus.PENDING && entry.pendingWrite) "Waiting for network"
    else when (entry.status) {
        SmsStatus.PENDING -> "Pending"
        SmsStatus.IN_PROGRESS -> "Sending"
        SmsStatus.SENT -> "Sent"
        SmsStatus.FAILED -> "Failed"
        SmsStatus.BLOCKED -> "Blocked"
        SmsStatus.UNKNOWN -> "Unknown"
    }

// ── Preview helpers ───────────────────────────────────────────────────────────

private fun sampleEntry(id: String, name: String, number: String, status: SmsStatus) =
    AutoHistoryEntry(
        id = id, location = "office", locationLabel = "Office", channel = "sms",
        sentAt = Date(), status = status, jobPhoneKey = number, recipientName = name,
        message = "Reached the office safely at 9:05 AM\n- Sent by +923001234567 via Spotwire" +
            "\n- To stop alerts, sign up on Spotwire",
        routineTriggered = true,
    )

private val sampleGroups = listOf(
    AutoArrivalGroup(
        key = "office|2026-07-31", location = "office", locationLabel = "Office",
        sentAt = Date(), routineTriggered = true,
        entries = listOf(
            sampleEntry("1", "Wife", "+923001111111", SmsStatus.SENT),
            sampleEntry("2", "Manager", "+923002222222", SmsStatus.PENDING),
            sampleEntry("3", "", "+923003333333", SmsStatus.FAILED),
        ),
    ),
    AutoArrivalGroup(
        key = "home|2026-07-30", location = "home", locationLabel = "Home",
        sentAt = Date(), routineTriggered = false,
        entries = listOf(sampleEntry("4", "Parents", "+923004444444", SmsStatus.SENT)),
    ),
)

@Preview(showBackground = true, name = "Auto — With arrivals")
@Composable
private fun AutoWithEntriesPreview() {
    SpotwireTheme {
        AutoContent(uiState = AutoUiState(groups = sampleGroups, isLoading = false),
            onRefreshGroup = {}, onRefreshEntry = {}, onRetryEntry = {})
    }
}

@Preview(showBackground = true, name = "Auto — Recipients popup")
@Composable
private fun AutoRecipientsPreview() {
    SpotwireTheme {
        RecipientsDialog(group = sampleGroups.first(), busyIds = emptySet(),
            onRefreshEntry = {}, onRetryEntry = {}, onDismiss = {})
    }
}

@Preview(showBackground = true, name = "Auto — Empty")
@Composable
private fun AutoEmptyPreview() {
    SpotwireTheme {
        AutoContent(uiState = AutoUiState(groups = emptyList(), isLoading = false),
            onRefreshGroup = {}, onRefreshEntry = {}, onRetryEntry = {})
    }
}

@Preview(showBackground = true, name = "Auto — Loading")
@Composable
private fun AutoLoadingPreview() {
    SpotwireTheme {
        AutoContent(uiState = AutoUiState(isLoading = true),
            onRefreshGroup = {}, onRefreshEntry = {}, onRetryEntry = {})
    }
}
