package com.textgate.app.presentation.settings

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
import com.textgate.app.core.theme.TextGateTheme
import com.textgate.app.core.utils.DateUtils
import com.textgate.app.domain.model.SettingsChange
import org.koin.androidx.compose.koinViewModel
import java.util.Date

@Composable
fun SettingsHistoryScreen(
    onBack: () -> Unit,
    viewModel: SettingsHistoryViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    SettingsHistoryContent(uiState = uiState, onBack = onBack)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsHistoryContent(
    uiState: SettingsHistoryUiState,
    onBack: () -> Unit,
) {
    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text("Change History") },
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
                "Every settings change is recorded here, so a value that disappears can be traced.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(12.dp))
            when {
                uiState.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                uiState.changes.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No changes recorded yet",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                }
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(uiState.changes, key = { it.id }) { change -> ChangeCard(change) }
                }
            }
        }
    }
}

@Composable
private fun ChangeCard(change: SettingsChange) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                change.field,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${change.oldValue.ifBlank { "(empty)" }}  ->  ${change.newValue.ifBlank { "(empty)" }}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                DateUtils.formatTimestamp(change.changedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
        }
    }
}

// ── Preview helpers ───────────────────────────────────────────────────────────

private val sampleChanges = listOf(
    SettingsChange("1", "Guardian number", "", "+923001234567", Date()),
    SettingsChange("2", "Home WiFi", "", "AA:BB:CC:DD:EE:01", Date()),
    SettingsChange("3", "Home contacts", "none (default guardian)", "Wife, Brother", Date()),
    SettingsChange("4", "Arrival monitoring", "off", "on", Date()),
)

@Preview(showBackground = true, name = "Settings history — With entries")
@Composable
private fun SettingsHistoryPreview() {
    TextGateTheme {
        SettingsHistoryContent(
            uiState = SettingsHistoryUiState(changes = sampleChanges, isLoading = false),
            onBack = {},
        )
    }
}

@Preview(showBackground = true, name = "Settings history — Empty")
@Composable
private fun SettingsHistoryEmptyPreview() {
    TextGateTheme {
        SettingsHistoryContent(
            uiState = SettingsHistoryUiState(changes = emptyList(), isLoading = false),
            onBack = {},
        )
    }
}
