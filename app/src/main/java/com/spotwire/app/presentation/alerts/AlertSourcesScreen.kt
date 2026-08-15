package com.spotwire.app.presentation.alerts

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.spotwire.app.core.theme.SpotwireTheme
import com.spotwire.app.core.utils.DateUtils
import com.spotwire.app.domain.model.AlertSubscription
import com.spotwire.app.domain.model.IncomingAlert
import org.koin.androidx.compose.koinViewModel
import java.util.Date

@Composable
fun AlertSourcesScreen(
    onBack: () -> Unit,
    viewModel: AlertSourcesViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    // Being on this screen is reading them.
    LaunchedEffect(uiState.alerts.size) { viewModel.markAlertsSeen() }
    AlertSourcesContent(
        uiState = uiState,
        onSetSubscribed = viewModel::setSubscribed,
        onBack = onBack,
    )
}

/** One alert somebody sent this person, exactly as it was sent. */
@Composable
private fun IncomingAlertCard(alert: IncomingAlert) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(
                alert.senderName.ifBlank { alert.senderPhone },
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(4.dp))
            Text(alert.message, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                DateUtils.formatTimestamp(alert.sentAt?.toDate()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlertSourcesContent(
    uiState: AlertSourcesUiState,
    onSetSubscribed: (AlertSubscription, Boolean) -> Unit,
    onBack: () -> Unit,
) {
    var pendingUnsubscribe by remember { mutableStateOf<AlertSubscription?>(null) }

    // Stopping alerts also messages the sender, so it is worth a confirmation.
    pendingUnsubscribe?.let { subscription ->
        AlertDialog(
            onDismissRequest = { pendingUnsubscribe = null },
            title = { Text("Stop these alerts?") },
            text = {
                Text(
                    "Are you sure you want to stop receiving automated location updates from " +
                        "${subscription.senderName.ifBlank { subscription.senderPhone }}? " +
                        "They will be told you unsubscribed."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onSetSubscribed(subscription, false)
                    pendingUnsubscribe = null
                }) { Text("Unsubscribe") }
            },
            dismissButton = {
                TextButton(onClick = { pendingUnsubscribe = null }) { Text("Cancel") }
            },
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text("Incoming Alerts") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            Text(
                "People who send automated location alerts to your number. Turning someone off " +
                    "stops future alerts; the ones already received stay in their records.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(12.dp))
            when {
                uiState.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                uiState.error != null && uiState.subscriptions.isEmpty() ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            uiState.error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                uiState.subscriptions.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Nobody is sending you automated alerts",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                }
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (uiState.alerts.isNotEmpty()) {
                        item {
                            Text(
                                "Alerts sent to you",
                                style = MaterialTheme.typography.titleSmall,
                            )
                        }
                        items(uiState.alerts, key = { "alert-" + it.id }) { alert ->
                            IncomingAlertCard(alert)
                        }
                        item {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Who may alert you",
                                style = MaterialTheme.typography.titleSmall,
                            )
                        }
                    }
                    items(uiState.subscriptions, key = { it.senderUid }) { subscription ->
                        SubscriptionCard(
                            subscription = subscription,
                            isBusy = subscription.senderUid in uiState.busyUids,
                            onToggle = { enabled ->
                                if (enabled) onSetSubscribed(subscription, true)
                                else pendingUnsubscribe = subscription
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SubscriptionCard(
    subscription: AlertSubscription,
    isBusy: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    subscription.senderName.ifBlank { subscription.senderPhone },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    subscription.senderPhone,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (subscription.subscribed) {
                        "Receiving alerts · last ${DateUtils.formatTimestamp(subscription.lastAlertAt)}"
                    } else {
                        "Unsubscribed"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            }
            if (isBusy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Switch(checked = subscription.subscribed, onCheckedChange = onToggle)
        }
    }
}

// ── Preview helpers ───────────────────────────────────────────────────────────

private val sampleSubscriptions = listOf(
    AlertSubscription("uid1", "Hamid", "+923001234567", subscribed = true, lastAlertAt = Date()),
    AlertSubscription("uid2", "Tauseeq", "+923009876543", subscribed = false, lastAlertAt = Date()),
)

@Preview(showBackground = true, name = "Incoming alerts — With senders")
@Composable
private fun AlertSourcesPreview() {
    SpotwireTheme {
        AlertSourcesContent(
            uiState = AlertSourcesUiState(
                subscriptions = sampleSubscriptions,
                isLoading = false,
                myPhone = "+923001111111",
            ),
            onSetSubscribed = { _, _ -> },
            onBack = {},
        )
    }
}

@Preview(showBackground = true, name = "Incoming alerts — Empty")
@Composable
private fun AlertSourcesEmptyPreview() {
    SpotwireTheme {
        AlertSourcesContent(
            uiState = AlertSourcesUiState(isLoading = false, myPhone = "+923001111111"),
            onSetSubscribed = { _, _ -> },
            onBack = {},
        )
    }
}
