package com.textgate.app.presentation.monitor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.textgate.app.core.theme.StatusBlocked
import com.textgate.app.core.theme.StatusFailed
import com.textgate.app.core.theme.StatusInProgress
import com.textgate.app.core.theme.StatusPending
import com.textgate.app.core.theme.StatusSent
import com.textgate.app.core.utils.DateUtils
import com.textgate.app.core.utils.scanBlocker
import com.textgate.app.data.local.MonitorLogStore
import com.textgate.app.domain.model.PresenceState
import kotlinx.coroutines.delay
import org.koin.androidx.compose.koinViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What monitoring is doing right now and everything it did over the last day,
 * read from the on-device log. This page exists so "why did no alert come" is
 * answered by scrolling, not by guessing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonitorLogScreen(
    onBack: () -> Unit,
    viewModel: MonitorLogViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var blocker by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            blocker = scanBlocker(context)
            viewModel.refresh()
            delay(10_000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Monitoring Log") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            StatusCard(uiState, blocker)
            Spacer(Modifier.height(16.dp))
            Text(
                "Last 24 hours",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(6.dp))
            when {
                uiState.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                uiState.entries.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Nothing recorded yet. Rows appear as monitoring checks run.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(uiState.entries) { entry ->
                        LogRow(entry)
                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(uiState: MonitorLogUiState, blocker: String?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            val (dotColor, statusText) = when {
                !uiState.monitoringEnabled -> StatusFailed to "Monitoring is switched off"
                !uiState.serviceRunning -> StatusPending to "Monitoring is starting or was stopped by the system"
                blocker != null -> StatusBlocked to "Monitoring is on but cannot observe"
                else -> StatusSent to "Monitoring is active"
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(dotColor))
                Spacer(Modifier.width(8.dp))
                Text(statusText, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            if (blocker != null && uiState.monitoringEnabled) {
                Spacer(Modifier.height(4.dp))
                Text(blocker, style = MaterialTheme.typography.bodySmall, color = StatusFailed)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Last successful check: " + if (uiState.lastObservedAt > 0L)
                    DateUtils.formatTimestamp(Date(uiState.lastObservedAt)) else "never",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            uiState.placeStatuses.forEach { place ->
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(place.label, style = MaterialTheme.typography.bodySmall)
                    val (stateColor, stateWord) = when (place.state) {
                        PresenceState.HERE -> StatusSent to "Here"
                        PresenceState.APPROACHING -> StatusPending to "Confirming"
                        PresenceState.AWAY -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f) to "Away"
                        PresenceState.BLIND -> StatusBlocked to "Cannot observe"
                    }
                    Text(stateWord, style = MaterialTheme.typography.bodySmall, color = stateColor)
                }
            }
        }
    }
}

@Composable
private fun LogRow(entry: MonitorLogStore.Entry) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(kindColor(entry.kind)))
        Spacer(Modifier.width(8.dp))
        Text(
            rowTime(entry.at),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            modifier = Modifier.width(84.dp),
        )
        Text(
            entry.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(
                alpha = if (entry.kind == MonitorLogStore.Kind.CHECK) 0.65f else 0.9f
            ),
        )
    }
}

@Composable
private fun kindColor(kind: String): Color = when (kind) {
    MonitorLogStore.Kind.ALERT -> StatusSent
    MonitorLogStore.Kind.PROBLEM -> StatusFailed
    MonitorLogStore.Kind.EVENT -> StatusInProgress
    else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
}

// Yesterday's rows carry their date, because a bare clock time from 23:50 read
// this morning looks like a check that just happened.
private fun rowTime(at: Long): String {
    val date = Date(at)
    return if (DateUtils.dayString(date) == DateUtils.todayString())
        SimpleDateFormat("HH:mm", Locale.US).format(date)
    else SimpleDateFormat("d MMM HH:mm", Locale.US).format(date)
}
