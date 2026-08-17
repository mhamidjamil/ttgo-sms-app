package com.spotwire.app.presentation.links

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
import com.spotwire.app.core.theme.SpotwireTheme
import com.spotwire.app.core.utils.DateUtils
import com.spotwire.app.presentation.components.PhoneNumberField
import com.spotwire.app.presentation.components.rememberDefaultCountry
import com.spotwire.app.domain.model.AccountLink
import com.spotwire.app.domain.model.LinkPermissions
import com.spotwire.app.domain.model.LinkState
import com.spotwire.app.domain.model.Place
import org.koin.androidx.compose.koinViewModel

@Composable
fun LinkedAccountsScreen(
    onBack: () -> Unit,
    onViewTimeline: (AccountLink) -> Unit = {},
    viewModel: LinkedAccountsViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    LinkedAccountsContent(
        uiState = uiState,
        onInvite = viewModel::invite,
        onRespond = viewModel::respond,
        onSetPermissions = viewModel::setPermissions,
        onRemove = viewModel::remove,
        onRequestLocation = { viewModel.requestLocation(it) },
        onFollowLive = { viewModel.requestLocation(it, live = true) },
        onDismissLocationAnswer = viewModel::dismissLocationAnswer,
        onClearMessages = viewModel::clearMessages,
        onViewTimeline = onViewTimeline,
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LinkedAccountsContent(
    uiState: LinkedAccountsUiState,
    onInvite: (String, LinkPermissions, String) -> Unit,
    onRespond: (AccountLink, Boolean) -> Unit,
    onSetPermissions: (AccountLink, LinkPermissions) -> Unit,
    onRemove: (AccountLink) -> Unit,
    onRequestLocation: (AccountLink) -> Unit,
    onFollowLive: (AccountLink) -> Unit,
    onDismissLocationAnswer: () -> Unit,
    onClearMessages: () -> Unit,
    onViewTimeline: (AccountLink) -> Unit = {},
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
            onInvite = { phone, permissions, country ->
                onInvite(phone, permissions, country)
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
            title = { Text(if (uiState.isLive) "Following their location" else "Current location") },
            text = { LocationPanel(uiState) },
            confirmButton = {
                TextButton(onClick = onDismissLocationAnswer) {
                    Text(if (uiState.isLive) "Stop" else "Close")
                }
            },
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
                "A linked person sees a place name and nothing more, unless you switch on the " +
                    "extra permissions on their card. You choose those one by one, and can " +
                    "change any of them at any time.",
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
                            onFollowLive = { onFollowLive(link) },
                            onViewTimeline = { onViewTimeline(link) },
                            places = uiState.places,
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
    onFollowLive: () -> Unit = {},
    onViewTimeline: () -> Unit = {},
    places: List<Place> = emptyList(),
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
                        onChange = {
                            onSetPermissions(link.permissions.copy(
                                requestLocation = it,
                                // Turning the ask off has to take the stronger
                                // answer with it, or the switch above would
                                // read as off while this one still applied.
                                preciseLocation = it && link.permissions.preciseLocation,
                            ))
                        },
                    )
                    if (link.permissions.requestLocation) {
                        PermissionRow(
                            label = "Answer with my exact position and nearby WiFi",
                            checked = link.permissions.preciseLocation,
                            enabled = !isBusy,
                            onChange = { onSetPermissions(link.permissions.copy(preciseLocation = it)) },
                        )
                    }
                    // A guardian's switch. Turning it on hands over every place
                    // and every stay, so the per-place rows below are switched
                    // off with it: naming single places would say nothing they
                    // could not already see.
                    PermissionRow(
                        label = "See my whole timeline",
                        checked = link.permissions.visitLog,
                        enabled = !isBusy,
                        onChange = {
                            onSetPermissions(link.permissions.copy(
                                visitLog = it,
                                visitLogPlaceIds = if (it) emptyList() else link.permissions.visitLogPlaceIds,
                            ))
                        },
                    )
                    if (!link.permissions.visitLog && places.isNotEmpty()) {
                        Text(
                            "Or only these places",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                        places.forEach { place ->
                            val allowed = place.id in link.permissions.visitLogPlaceIds
                            PermissionRow(
                                label = place.label.ifBlank { place.id },
                                checked = allowed,
                                enabled = !isBusy,
                                onChange = { on ->
                                    val ids = if (on) link.permissions.visitLogPlaceIds + place.id
                                        else link.permissions.visitLogPlaceIds - place.id
                                    onSetPermissions(link.permissions.copy(visitLogPlaceIds = ids))
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onRequestLocation, enabled = !isBusy) {
                            Text("Where are they")
                        }
                        OutlinedButton(onClick = onFollowLive, enabled = !isBusy) {
                            Text("Follow live")
                        }
                        Spacer(Modifier.weight(1f))
                        OutlinedButton(
                            onClick = onRemove,
                            enabled = !isBusy,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        ) { Text("Remove") }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Following keeps asking until you stop it, so the position gets " +
                                "sharper. All of it works only if they allowed it on their side.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onViewTimeline, enabled = !isBusy) { Text("Timeline") }
                    }
                }
            }
        }
    }
}

/**
 * What has come back so far. A live ask keeps this open, so it says how many
 * readings have arrived and how good the newest one is: a first fix indoors is
 * often hundreds of metres out and gets better while this is on screen.
 */
@Composable
private fun LocationPanel(uiState: LinkedAccountsUiState) {
    val fix = uiState.latestFix
    Column {
        if (fix == null && uiState.locationAnswer == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Asking their device. This needs their app to be running.")
            }
            return@Column
        }
        if (fix == null) {
            Text(uiState.locationAnswer.orEmpty())
            return@Column
        }
        if (fix.placeLabel.isNotBlank()) {
            Text("At ${fix.placeLabel}", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(6.dp))
        }
        Text(
            "%.6f, %.6f".format(fix.latitude, fix.longitude),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "Accurate to about ${fix.accuracyMeters.toInt()} m, " +
                "reading ${uiState.fixCount} at ${DateUtils.time12h(fix.at)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        if (fix.networks.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                "WiFi around them",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            // Loudest first, and only a handful: the nearest few are what name
            // the building, and a list of thirty is unreadable in a dialog.
            fix.networks.take(8).forEach { row ->
                val parts = row.split("|")
                Text(
                    parts.getOrNull(0)?.ifBlank { "(hidden network)" } ?: row,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "${parts.getOrNull(1).orEmpty()}  ${parts.getOrNull(2).orEmpty()} dBm",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            }
        }
        if (uiState.isLive) {
            Spacer(Modifier.height(10.dp))
            Text(
                "Still following. The position sharpens as more readings arrive; " +
                    "Stop closes it on their phone too.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
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
    onInvite: (String, LinkPermissions, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var phone by remember { mutableStateOf("") }
    val country = rememberDefaultCountry()
    var autoUpdates by remember { mutableStateOf(true) }
    var requestLocation by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Link an account") },
        text = {
            Column {
                Text(
                    "They need a Spotwire account with this number already verified.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                Spacer(Modifier.height(10.dp))
                PhoneNumberField(
                    number = phone,
                    onNumberChange = { phone = it },
                    label = "Their phone",
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
                    onInvite(phone, LinkPermissions(autoUpdates, requestLocation), country)
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
    SpotwireTheme {
        LinkedAccountsContent(
            uiState = LinkedAccountsUiState(links = sampleLinks, isLoading = false),
            onInvite = { _, _, _ -> }, onRespond = { _, _ -> }, onSetPermissions = { _, _ -> },
            onRemove = {}, onRequestLocation = {}, onFollowLive = {}, onDismissLocationAnswer = {},
            onClearMessages = {}, onBack = {},
        )
    }
}

@Preview(showBackground = true, name = "Linked accounts — Empty")
@Composable
private fun LinkedAccountsEmptyPreview() {
    SpotwireTheme {
        LinkedAccountsContent(
            uiState = LinkedAccountsUiState(isLoading = false),
            onInvite = { _, _, _ -> }, onRespond = { _, _ -> }, onSetPermissions = { _, _ -> },
            onRemove = {}, onRequestLocation = {}, onFollowLive = {}, onDismissLocationAnswer = {},
            onClearMessages = {}, onBack = {},
        )
    }
}
