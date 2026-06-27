package com.textgate.app.presentation.links

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.textgate.app.core.theme.TextGateTheme
import com.textgate.app.domain.model.AccountLink
import com.textgate.app.domain.model.LinkPermissions
import com.textgate.app.domain.model.LinkState
import org.koin.androidx.compose.koinViewModel

@Composable
fun LinkedAccountsScreen(
    onBack: () -> Unit,
    viewModel: LinkedAccountsViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    LinkedAccountsContent(
        uiState = uiState,
        onInvite = viewModel::invite,
        onRespond = viewModel::respond,
        onSetPermissions = viewModel::setPermissions,
        onRemove = viewModel::remove,
        onRequestLocation = viewModel::requestLocation,
        onDismissLocationAnswer = viewModel::dismissLocationAnswer,
        onClearMessages = viewModel::clearMessages,
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LinkedAccountsContent(
    uiState: LinkedAccountsUiState,
    onInvite: (String, LinkPermissions) -> Unit,
    onRespond: (AccountLink, Boolean) -> Unit,
    onSetPermissions: (AccountLink, LinkPermissions) -> Unit,
    onRemove: (AccountLink) -> Unit,
    onRequestLocation: (AccountLink) -> Unit,
    onDismissLocationAnswer: () -> Unit,
    onClearMessages: () -> Unit,
    onBack: () -> Unit,
) {
    var showInviteDialog by remember { mutableStateOf(false) }
    var pendingRemoval by remember { mutableStateOf<AccountLink?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.message, uiState.error) {
        (uiState.message ?: uiState.error)?.let {
            snackbarHostState.showSnackbar(it)
            onClearMessages()
        }
    }

    if (showInviteDialog) {
        InviteDialog(
            isInviting = uiState.isInviting,
            onInvite = { phone, permissions ->
                onInvite(phone, permissions)
                showInviteDialog = false
            },
            onDismiss = { showInviteDialog = false },
        )
    }

    pendingRemoval?.let { link ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text("Remove link?") },
            text = {
                Text(
                    "${link.otherName.ifBlank { link.otherPhone }} will stop receiving your " +
                        "location updates and can no longer ask where you are."
                )
            },
            confirmButton = {
                TextButton(onClick = { onRemove(link); pendingRemoval = null }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { pendingRemoval = null }) { Text("Cancel") } },
        )
    }

    if (uiState.awaitingLocationFor != null) {
        AlertDialog(
            onDismissRequest = onDismissLocationAnswer,
            title = { Text("Current location") },
            text = {
                if (uiState.locationAnswer == null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Asking their device. This needs their app to be running.")
                    }
                } else {
                    Text(uiState.locationAnswer)
                }
            },
            confirmButton = { TextButton(onClick = onDismissLocationAnswer) { Text("Close") } },
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Linked Accounts") },
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
                "Linked people only ever see a place name, never a map position or WiFi details. " +
                    "You choose what each of them is allowed to do, and can change it any time.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = { showInviteDialog = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Link an Account")
            }
            Spacer(Modifier.height(12.dp))
            when {
                uiState.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                uiState.links.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No linked accounts yet",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                }
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(uiState.links, key = { it.otherUid }) { link ->
                        LinkCard(
                            link = link,
                            isBusy = link.otherUid in uiState.busyUids,
                            onRespond = { accept -> onRespond(link, accept) },
                            onSetPermissions = { onSetPermissions(link, it) },
                            onRemove = { pendingRemoval = link },
                            onRequestLocation = { onRequestLocation(link) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LinkCard(
    link: AccountLink,
    isBusy: Boolean,
    onRespond: (Boolean) -> Unit,
    onSetPermissions: (LinkPermissions) -> Unit,
    onRemove: () -> Unit,
    onRequestLocation: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        link.otherName.ifBlank { link.otherPhone },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        link.otherPhone,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
                if (isBusy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else StateChip(link.state)
            }

            when (link.state) {
                LinkState.PENDING_INCOMING -> {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "They want to link with you.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onRespond(true) }, enabled = !isBusy) { Text("Accept") }
                        OutlinedButton(onClick = { onRespond(false) }, enabled = !isBusy) { Text("Reject") }
                    }
                }
                LinkState.PENDING_OUTGOING -> {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Waiting for them to accept. Nothing is shared until they do.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = onRemove, enabled = !isBusy) { Text("Cancel Invite") }
                }
                LinkState.DECLINED -> {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "This invite was declined.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = onRemove, enabled = !isBusy) { Text("Remove") }
                }
                LinkState.ACTIVE -> {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "What they can do",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    PermissionRow(
                        label = "Receive my arrival updates",
                        checked = link.permissions.autoLocationUpdates,
                        enabled = !isBusy,
                        onChange = { onSetPermissions(link.permissions.copy(autoLocationUpdates = it)) },
                    )
                    PermissionRow(
                        label = "Ask where I am right now",
                        checked = link.permissions.requestLocation,
                        enabled = !isBusy,
                        onChange = { onSetPermissions(link.permissions.copy(requestLocation = it)) },
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onRequestLocation, enabled = !isBusy) {
                            Text("Get Current Location")
                        }
                        OutlinedButton(
                            onClick = onRemove,
                            enabled = !isBusy,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        ) { Text("Remove") }
                    }
                    Text(
                        "Asking works only if they gave you that permission on their side.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun StateChip(state: LinkState) {
    val label = when (state) {
        LinkState.ACTIVE -> "Linked"
        LinkState.PENDING_INCOMING -> "Needs your answer"
        LinkState.PENDING_OUTGOING -> "Invite sent"
        LinkState.DECLINED -> "Declined"
    }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun InviteDialog(
    isInviting: Boolean,
    onInvite: (String, LinkPermissions) -> Unit,
    onDismiss: () -> Unit,
) {
    var phone by remember { mutableStateOf("") }
    var autoUpdates by remember { mutableStateOf(true) }
    var requestLocation by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Link an account") },
        text = {
            Column {
                Text(
                    "They need a TextGate account with this number already verified.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text("Their phone (03001234567)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "What they may do with your location",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                PermissionRow(
                    label = "Receive my arrival updates",
                    checked = autoUpdates,
                    enabled = true,
                    onChange = { autoUpdates = it },
                )
                PermissionRow(
                    label = "Ask where I am right now",
                    checked = requestLocation,
                    enabled = true,
                    onChange = { requestLocation = it },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onInvite(phone, LinkPermissions(autoUpdates, requestLocation))
                },
                enabled = phone.isNotBlank() && !isInviting,
            ) { Text("Send Invite") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ── Preview helpers ───────────────────────────────────────────────────────────

private val sampleLinks = listOf(
    AccountLink("uid1", "Tauseeq", "+923001234567", LinkState.PENDING_INCOMING),
    AccountLink(
        "uid2", "Ammi", "+923009876543", LinkState.ACTIVE,
        LinkPermissions(autoLocationUpdates = true, requestLocation = true),
    ),
    AccountLink("uid3", "Ali", "+923001111111", LinkState.PENDING_OUTGOING),
)

@Preview(showBackground = true, name = "Linked accounts — With links")
@Composable
private fun LinkedAccountsPreview() {
    TextGateTheme {
        LinkedAccountsContent(
            uiState = LinkedAccountsUiState(links = sampleLinks, isLoading = false),
            onInvite = { _, _ -> }, onRespond = { _, _ -> }, onSetPermissions = { _, _ -> },
            onRemove = {}, onRequestLocation = {}, onDismissLocationAnswer = {},
            onClearMessages = {}, onBack = {},
        )
    }
}

@Preview(showBackground = true, name = "Linked accounts — Empty")
@Composable
private fun LinkedAccountsEmptyPreview() {
    TextGateTheme {
        LinkedAccountsContent(
            uiState = LinkedAccountsUiState(isLoading = false),
            onInvite = { _, _ -> }, onRespond = { _, _ -> }, onSetPermissions = { _, _ -> },
            onRemove = {}, onRequestLocation = {}, onDismissLocationAnswer = {},
            onClearMessages = {}, onBack = {},
        )
    }
}
