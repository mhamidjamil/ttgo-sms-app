package com.textgate.app.presentation.auto

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.textgate.app.core.theme.*
import com.textgate.app.core.utils.DateUtils
import com.textgate.app.domain.model.AutoHistoryEntry
import com.textgate.app.domain.model.SmsStatus
import org.koin.androidx.compose.koinViewModel
import java.util.Date

@Composable
fun AutoScreen(viewModel: AutoViewModel = koinViewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    // Poll job statuses while the screen is visible — same as the History page —
    // so "Pending" chips resolve to sent/failed instead of sticking forever.
    DisposableEffect(Unit) {
        viewModel.startPolling()
        onDispose { viewModel.stopPolling() }
    }
    AutoContent(uiState = uiState)
}

@Composable
private fun AutoContent(uiState: AutoUiState) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text("Auto Notifications", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(4.dp))
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
            uiState.entries.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "No arrivals recorded yet",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Set up arrival monitoring in Profile → Settings",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    )
                }
            }
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(uiState.entries, key = { it.id }) { entry ->
                    AutoEntryCard(entry = entry)
                }
            }
        }
    }
}

@Composable
private fun AutoEntryCard(entry: AutoHistoryEntry) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Stored label (any custom place); legacy entries only have the id.
                val locationLabel = entry.locationLabel.ifBlank {
                    when (entry.location) {
                        "home" -> "Home"
                        "office" -> "Office"
                        else -> entry.location
                    }
                }
                Text(
                    "Arrived at $locationLabel",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                StatusChip(entry.status)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                entry.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
            )
            Spacer(Modifier.height(6.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    DateUtils.formatTimestamp(entry.sentAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        if (entry.channel == "whatsapp") "WhatsApp" else "SMS",
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                if (entry.routineTriggered) {
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
        Text(
            label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

// ── Preview helpers ───────────────────────────────────────────────────────────

private val sampleAutoEntries = listOf(
    AutoHistoryEntry(
        id = "1", location = "office", locationLabel = "Office", channel = "sms",
        sentAt = Date(), status = SmsStatus.SENT, jobPhoneKey = "+923001234567",
        message = "Alice arrived at Office at 9:05 AM", routineTriggered = true,
    ),
    AutoHistoryEntry(
        id = "2", location = "place_1", locationLabel = "Ali's home", channel = "whatsapp",
        sentAt = Date(), status = SmsStatus.SENT, jobPhoneKey = "+923001234567",
        message = "Alice arrived at Ali's home at 7:42 PM", routineTriggered = false,
    ),
)

@Preview(showBackground = true, name = "Auto — With arrivals")
@Composable
private fun AutoWithEntriesPreview() {
    TextGateTheme { AutoContent(uiState = AutoUiState(entries = sampleAutoEntries)) }
}

@Preview(showBackground = true, name = "Auto — Empty")
@Composable
private fun AutoEmptyPreview() {
    TextGateTheme { AutoContent(uiState = AutoUiState(entries = emptyList())) }
}

@Preview(showBackground = true, name = "Auto — Loading")
@Composable
private fun AutoLoadingPreview() {
    TextGateTheme { AutoContent(uiState = AutoUiState(isLoading = true)) }
}
