package com.textgate.app.presentation.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.textgate.app.core.theme.StatusFailed
import com.textgate.app.core.theme.StatusSent
import com.textgate.app.core.theme.TextGateTheme
import com.textgate.app.core.utils.PhoneNormalizer
import com.textgate.app.core.utils.canScanWifi
import com.textgate.app.core.utils.placeInRange
import com.textgate.app.core.utils.requestWifiScan
import com.textgate.app.core.utils.scanBlocker
import com.textgate.app.core.utils.visibleAccessPoints
import com.textgate.app.core.utils.visibleBssids
import com.textgate.app.domain.model.Place
import com.textgate.app.domain.model.Closeness
import com.textgate.app.domain.model.PlaceContact
import com.textgate.app.domain.model.PresenceState
import com.textgate.app.domain.model.Sensitivity
import com.textgate.app.services.ArrivalService
import com.textgate.app.services.ArrivalWatchdogReceiver
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

private const val TAG_SETTINGS = "TextGateSettings"

/**
 * Arrival monitoring settings. Reached as its own bottom-bar tab, where there is
 * nothing to go back to — pass a null [onBack] to hide the back arrow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: (() -> Unit)? = null,
    onViewChangeHistory: () -> Unit = {},
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var guardianNumber by remember(uiState.guardianNumber) { mutableStateOf(uiState.guardianNumber) }

    var scanResults by remember { mutableStateOf<List<ScanResult>>(emptyList()) }
    var showScanDialog by remember { mutableStateOf(false) }
    var scanTargetPlaceId by remember { mutableStateOf<String?>(null) }
    var editingPlaceId by remember { mutableStateOf<String?>(null) }
    var isMonitoring by remember { mutableStateOf(ArrivalService.isRunning) }
    var showLocationDisclosure by remember { mutableStateOf(false) }

    // Persist monitoring state so it survives process death / system service kill.
    // On composition, restore from prefs — if the user intended monitoring ON but
    // the service was killed (e.g. battery optimization), restart it silently.
    LaunchedEffect(Unit) {
        if (!isMonitoring && viewModel.getMonitoringEnabled()) {
            val missing = requiredMonitoringPermissions()
                .filterNot { permission -> hasPermission(context, permission) }
            if (missing.isEmpty()) {
                isMonitoring = true
                ArrivalService.start(context)
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            scanResults = scanWifi(context)
            showScanDialog = true
        }
    }

    val monitoringPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val granted = requiredMonitoringPermissions()
            .all { permission -> grants[permission] == true || hasPermission(context, permission) }
        if (!granted) Log.w(TAG_SETTINGS, "Monitoring not started: location permission refused")
        if (granted) {
            isMonitoring = true
            scope.launch { viewModel.setMonitoringEnabled(true) }
            ArrivalService.start(context)
        } else {
            isMonitoring = false
        }
    }

    if (showLocationDisclosure) {
        LocationDisclosureDialog(
            onAgree = {
                showLocationDisclosure = false
                monitoringPermissionLauncher.launch(
                    monitoringPermissionsToRequest()
                        .filterNot { permission -> hasPermission(context, permission) }
                        .toTypedArray()
                )
            },
            onDismiss = {
                showLocationDisclosure = false
                isMonitoring = false
            },
        )
    }

    LaunchedEffect(uiState.saveSuccess) {
        if (uiState.saveSuccess) {
            snackbarHostState.showSnackbar("Saved")
            viewModel.clearSuccess()
        }
    }

    LaunchedEffect(uiState.locationResult) {
        uiState.locationResult?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearLocationResult()
        }
    }

    uiState.locationPlaceId?.let { placeId ->
        val place = uiState.places.firstOrNull { it.id == placeId }
        LocationRecipientsDialog(
            placeLabel = place?.label?.ifBlank { placeId } ?: placeId,
            recipients = uiState.locationRecipients,
            isLoading = uiState.isLoadingRecipients,
            isSending = uiState.isSendingLocation,
            onSend = { selected -> viewModel.sendCurrentLocation(placeId, selected) },
            onDismiss = viewModel::dismissLocationPrompt,
        )
    }

    if (showScanDialog) {
        val target = uiState.places.firstOrNull { it.id == scanTargetPlaceId }
        WifiCaptureDialog(
            results = scanResults,
            alreadySaved = target?.savedBssids.orEmpty(),
            onCapture = { selected, strongest ->
                if (target != null) {
                    // Cumulative: capturing again at the same place adds what is
                    // audible today without dropping what was saved on an earlier
                    // visit, which is how a mesh gets covered node by node.
                    val merged = (target.savedBssids + selected).distinct()
                    val topName = scanResults.firstOrNull { it.BSSID.lowercase() == strongest }
                        ?.SSID.orEmpty()
                    viewModel.updatePlace(
                        target.copy(
                            bssid = strongest.ifBlank { target.bssid },
                            bssids = merged,
                            label = target.label.ifBlank { topName.ifBlank { strongest } },
                        )
                    )
                }
                showScanDialog = false
            },
            onDismiss = { showScanDialog = false },
        )
    }

    // Tapping a place opens its editor dialog: name, contacts, arrival message.
    uiState.places.firstOrNull { it.id == editingPlaceId }?.let { place ->
        PlaceEditorDialog(
            place = place,
            showWaMessage = uiState.waConfigured,
            onSave = { updated ->
                viewModel.updatePlace(updated)
                editingPlaceId = null
            },
            onDismiss = { editingPlaceId = null },
        )
    }

    SettingsContent(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        guardianNumber = guardianNumber,
        onGuardianChange = { guardianNumber = it },
        onEditPlace = { editingPlaceId = it },
        onAddPlace = viewModel::addPlace,
        onRemovePlace = viewModel::removePlace,
        onScanPlace = { placeId ->
            scanTargetPlaceId = placeId
            requestScanOrLaunch(context, permissionLauncher) {
                scanResults = scanWifi(context)
                showScanDialog = true
            }
        },
        isMonitoring = isMonitoring,
        onMonitoringToggle = { enabled ->
            if (enabled) {
                val missing = monitoringPermissionsToRequest()
                    .filterNot { permission -> hasPermission(context, permission) }
                if (missing.isEmpty()) {
                    isMonitoring = true
                    scope.launch { viewModel.setMonitoringEnabled(true) }
                    ArrivalService.start(context)
                } else {
                    // Google Play requires the app to say what it collects, that
                    // it keeps collecting while the app is closed, and why,
                    // BEFORE the system permission dialog appears — and to take
                    // no for an answer. Asking cold is a policy rejection.
                    showLocationDisclosure = true
                }
            } else {
                isMonitoring = false
                scope.launch { viewModel.setMonitoringEnabled(false) }
                ArrivalService.stop(context)
                ArrivalWatchdogReceiver.cancelChecks(context)
            }
        },
        onSave = { guardian -> viewModel.save(guardian) },
        onSendLocation = viewModel::openLocationPrompt,
        onBack = onBack,
        onViewChangeHistory = onViewChangeHistory,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsContent(
    uiState: SettingsUiState,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    guardianNumber: String,
    onGuardianChange: (String) -> Unit,
    onEditPlace: (String) -> Unit,
    onAddPlace: () -> Unit,
    onRemovePlace: (String) -> Unit,
    onScanPlace: (String) -> Unit,
    isMonitoring: Boolean,
    onMonitoringToggle: (Boolean) -> Unit,
    onSave: (String) -> Unit,
    onSendLocation: (String) -> Unit = {},
    onBack: (() -> Unit)? = null,
    onViewChangeHistory: () -> Unit = {},
) {
    val phoneNormalizer = remember { PhoneNormalizer() }
    val guardianError = phoneNormalizer.validationError(guardianNumber)

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Arrival Monitoring") },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onViewChangeHistory) {
                        Icon(Icons.Default.History, contentDescription = "Change history")
                    }
                },
            )
        },
    ) { padding ->
        if (uiState.isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            CurrentPlaceCheck(uiState.places, onSendLocation)
            Spacer(Modifier.height(20.dp))

            SectionTitle("Default Guardian")
            Text(
                "Always notified, on top of any contacts a place has of its own",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = guardianNumber,
                onValueChange = onGuardianChange,
                label = { Text("Guardian Phone (Pakistani, e.g. 03001234567)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth(),
                isError = guardianError != null,
                supportingText = guardianError?.let { { Text(it) } },
            )

            Spacer(Modifier.height(20.dp))
            SectionTitle("Places")
            Text(
                "Tap a place to manage its contacts and arrival message. Place edits save immediately.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(8.dp))

            uiState.places.forEach { place ->
                key(place.id) {
                    PlaceCard(
                        place = place,
                        onClick = { onEditPlace(place.id) },
                        onScan = { onScanPlace(place.id) },
                        onRemove = { onRemovePlace(place.id) },
                    )
                    Spacer(Modifier.height(10.dp))
                }
            }

            OutlinedButton(onClick = onAddPlace, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Add Place")
            }

            Spacer(Modifier.height(20.dp))
            SectionTitle("Monitoring")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (isMonitoring) "Arrival monitoring is ON" else "Arrival monitoring is OFF",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        if (isMonitoring) "Running in background" else "Tap to start",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
                Switch(checked = isMonitoring, onCheckedChange = onMonitoringToggle)
            }

            if (isMonitoring) {
                BatteryExemptionNotice()
                DetectionHealthCard(uiState)
            }

            uiState.error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(28.dp))
            Button(
                onClick = {
                    val normalized = phoneNormalizer.normalize(guardianNumber) ?: guardianNumber
                    onSave(normalized)
                },
                enabled = guardianError == null && !uiState.isSaving,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (uiState.isSaving) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text("Save Guardian Number", style = MaterialTheme.typography.titleLarge)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * What detection currently believes, in plain words. The ongoing notification
 * used to say "monitoring active" whether the app had checked a minute ago or
 * had been unable to see anything for a week, so a dead feature looked identical
 * to a working one.
 */
@Composable
private fun DetectionHealthCard(uiState: SettingsUiState) {
    val context = LocalContext.current
    var verdict by remember { mutableStateOf<String?>(null) }
    val sinceCheck = if (uiState.lastObservedAt == 0L) null
        else System.currentTimeMillis() - uiState.lastObservedAt
    val blindTooLong = sinceCheck != null && sinceCheck > 24 * 60 * 60 * 1000L

    Spacer(Modifier.height(8.dp))
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (blindTooLong) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("Is detection working?", style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold)
            Text(
                when {
                    sinceCheck == null -> "Nothing checked yet. This fills in after the first sweep."
                    blindTooLong -> "The app has not been able to see any networks for over a " +
                        "day. Arrivals are not being detected. Check that WiFi scanning and " +
                        "location are on."
                    else -> "Last successful check ${describeAge(sinceCheck)}."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            uiState.places.filter { it.savedBssids.isNotEmpty() }.forEach { place ->
                Text(
                    "${place.label.ifBlank { place.id }}: ${describeState(uiState.placeStates[place.id])}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = { verdict = describeDetectionNow(context, uiState.places) }) {
                Text("Test detection here")
            }
            verdict?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun describeAge(millis: Long): String {
    val minutes = millis / 60_000
    return when {
        minutes < 2 -> "just now"
        minutes < 60 -> "$minutes minutes ago"
        minutes < 60 * 24 -> "${minutes / 60} hours ago"
        else -> "${minutes / (60 * 24)} days ago"
    }
}

private fun describeState(state: PresenceState?): String = when (state) {
    PresenceState.HERE -> "here, already alerted for this visit"
    PresenceState.APPROACHING -> "here, waiting before alerting"
    PresenceState.BLIND -> "cannot tell, nothing visible to check against"
    PresenceState.AWAY, null -> "away"
}

// Answers what the app would decide right now and, more usefully, why it would
// not alert. This is what turns a silent failure into a five second diagnosis.
private fun describeDetectionNow(context: Context, places: List<Place>): String {
    scanBlocker(context)?.let { return "Cannot scan. $it." }
    requestWifiScan(context)
    val visible = visibleAccessPoints(context)
    if (visible.isEmpty()) {
        return "No networks heard on this read. A fresh scan takes a few seconds, try again."
    }
    val saved = places.filter { it.savedBssids.isNotEmpty() }
    if (saved.isEmpty()) return "Heard ${visible.size} networks, but no place has any saved yet."

    return saved.joinToString("\n") { place ->
        val heard = place.savedBssids.mapNotNull { visible[it] }
        val name = place.label.ifBlank { place.id }
        when {
            heard.isEmpty() -> "$name: none of its ${place.savedBssids.size} networks heard."
            heard.size < place.requiredMatches ->
                "$name: heard ${heard.size} of its networks, needs ${place.requiredMatches}."
            heard.max() < place.minRssi ->
                "$name: heard at ${heard.max()} dBm, too weak for the closeness setting " +
                    "(${place.minRssi} dBm). This is what walking past outside looks like."
            !place.alertsEnabled -> "$name: here, but its alerts are switched off."
            else -> "$name: here, at ${heard.max()} dBm. It would alert after the wait."
        }
    }
}

/**
 * Without this exemption the phone's battery manager is free to kill monitoring,
 * and the background check that would restart it is not allowed to run either.
 * It is the single setting that decides whether arrivals keep working for weeks
 * or quietly stop after a few days, so it is shown until it is granted.
 */
@Composable
private fun BatteryExemptionNotice() {
    val context = LocalContext.current
    var exempt by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { exempt = isIgnoringBatteryOptimizations(context) }
    if (exempt) return

    Spacer(Modifier.height(8.dp))
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "Battery saver can stop arrival alerts",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "This phone is allowed to shut monitoring down in the background, which " +
                    "is the usual reason alerts stop arriving after a few days. Allowing " +
                    "it to run in the background fixes that.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = {
                runCatching {
                    launcher.launch(
                        Intent(
                            android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            android.net.Uri.parse("package:${context.packageName}"),
                        )
                    )
                }.onFailure {
                    Log.w(TAG_SETTINGS, "Battery optimisation screen unavailable: ${it.message}")
                }
            }) { Text("Allow background running") }
        }
    }
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean =
    context.getSystemService(android.os.PowerManager::class.java)
        ?.isIgnoringBatteryOptimizations(context.packageName) ?: false

/**
 * "Where am I?" — answers what the arrival service would decide right now,
 * without waiting out the stability window or an SMS. The match rule is the
 * same one the service uses: a place counts as here when its network is within
 * range, connected or not.
 */
@Composable
private fun CurrentPlaceCheck(places: List<Place>, onSendLocation: (String) -> Unit) {
    val context = LocalContext.current
    var checked by remember { mutableStateOf(false) }
    var inRange by remember { mutableStateOf<Set<String>>(emptySet()) }
    var scanningAvailable by remember { mutableStateOf(true) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "Where am I?",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Checks the WiFi networks within range of this phone against your saved places. " +
                    "You do not have to be connected to them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(8.dp))
            val match = if (checked) placeInRange(places, inRange) else null
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = {
                        requestWifiScan(context)
                        inRange = visibleBssids(context)
                        scanningAvailable = canScanWifi(context)
                        checked = true
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Wifi, contentDescription = null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Check current place")
                }
                // Only worth offering once we know where the phone is — the
                // message names the place.
                if (match != null) {
                    Button(
                        onClick = { onSendLocation(match.id) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Send message")
                    }
                }
            }

            if (checked) {
                Spacer(Modifier.height(10.dp))
                Text(
                    when {
                        match != null -> "You are at ${match.label.ifBlank { match.id }}"
                        inRange.isEmpty() -> "No networks in range"
                        else -> "Unknown place"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (match != null) StatusSent else StatusFailed,
                )
                Text(
                    when {
                        match != null -> "${inRange.size} networks in range, one of them is this place"
                        !scanningAvailable ->
                            "Turn on WiFi, or turn on \"WiFi scanning always available\" in the " +
                                "system WiFi settings. Arrival alerts cannot fire in this state."
                        inRange.isEmpty() ->
                            "Location permission or device location is off, so the phone cannot " +
                                "see any networks. Arrival alerts cannot fire in this state."
                        else -> "${inRange.size} networks in range, none of them saved on a place below"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
        }
    }
}

/**
 * Who to tell that the user is here. Everyone this place would alert starts
 * checked, because that is the usual answer, and anyone can be dropped for this
 * one message without touching the place's saved contacts.
 */
@Composable
private fun LocationRecipientsDialog(
    placeLabel: String,
    recipients: List<PlaceContact>,
    isLoading: Boolean,
    isSending: Boolean,
    onSend: (List<PlaceContact>) -> Unit,
    onDismiss: () -> Unit,
) {
    val excluded = remember { mutableStateListOf<String>() }
    val selected = recipients.filterNot { it.number in excluded }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send your location") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Tells them you are at $placeLabel right now. This is a manual message, so " +
                        "it uses your daily SMS quota and shows up under Manual history.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                Spacer(Modifier.height(8.dp))
                when {
                    isLoading -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    recipients.isEmpty() -> Text(
                        "This place has nobody to message yet. Add a guardian number or contacts first.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    else -> recipients.forEach { contact ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = contact.number !in excluded,
                                onCheckedChange = { keep ->
                                    if (keep) excluded.remove(contact.number)
                                    else excluded.add(contact.number)
                                },
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    contact.name.ifBlank { contact.number },
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                if (contact.name.isNotBlank()) {
                                    Text(
                                        contact.number,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSend(selected) },
                enabled = selected.isNotEmpty() && !isSending,
            ) {
                if (isSending) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Text("Send to ${selected.size}")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(6.dp))
}

// Clean place card: name + WiFi + a compact summary. Everything else (contacts,
// message) lives in the editor dialog opened by tapping the card.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaceCard(
    place: Place,
    onClick: () -> Unit,
    onScan: () -> Unit,
    onRemove: (() -> Unit)?,
) {
    Card(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        place.label.ifBlank { "Unnamed place" },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        buildString {
                            append(
                                when (place.savedBssids.size) {
                                    0 -> "No WiFi set"
                                    1 -> "1 network saved"
                                    else -> "${place.savedBssids.size} networks saved, " +
                                        "needs ${place.requiredMatches}"
                                }
                            )
                            append(" · ")
                            append(
                                when (place.contacts.size) {
                                    0 -> "guardian only"
                                    1 -> "guardian + 1 contact"
                                    else -> "guardian + ${place.contacts.size} contacts"
                                }
                            )
                            if (place.message.isNotBlank()) append(" · custom message")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
                OutlinedButton(onClick = onScan) {
                    Icon(Icons.Default.Wifi, contentDescription = null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Capture")
                }
                if (onRemove != null) {
                    IconButton(onClick = onRemove) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Remove place",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Editor dialog for one place: rename it, write a custom arrival message, and
 * manage its contact list (add / edit / remove — e.g. Wife, Parents, Manager).
 * Edits are a local draft; nothing changes until Save.
 */
@Composable
private fun PlaceEditorDialog(
    place: Place,
    showWaMessage: Boolean = false,
    onSave: (Place) -> Unit,
    onDismiss: () -> Unit,
) {
    val phoneNormalizer = remember { PhoneNormalizer() }
    var label by remember { mutableStateOf(place.label) }
    var message by remember { mutableStateOf(place.message) }
    var waMessage by remember { mutableStateOf(place.waMessage) }
    val contacts = remember { mutableStateListOf(*place.contacts.toTypedArray()) }
    var newName by remember { mutableStateOf("") }
    var newNumber by remember { mutableStateOf("") }
    var contactError by remember { mutableStateOf<String?>(null) }
    var alertsEnabled by remember { mutableStateOf(place.alertsEnabled) }
    var sensitivity by remember { mutableStateOf(place.sensitivity) }
    var dwellOverride by remember {
        mutableStateOf(place.dwellMinutesOverride.takeIf { it > 0 }?.toString() ?: "")
    }
    var quietFrom by remember { mutableStateOf(place.quietFrom) }
    var quietTo by remember { mutableStateOf(place.quietTo) }
    var showAdvanced by remember { mutableStateOf(false) }
    var minRssi by remember { mutableIntStateOf(place.minRssi) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(place.label.ifBlank { "Edit place" }) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Place name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("e.g. Ali's home") },
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = message,
                    onValueChange = { if (it.length <= 90) message = it },
                    label = { Text("Arrival message (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("${message.length}/90 — blank = \"<your name> arrived at ${label.ifBlank { "place" }}\"") },
                )
                if (showWaMessage) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = waMessage,
                        onValueChange = { if (it.length <= 300) waMessage = it },
                        label = { Text("WhatsApp message (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        supportingText = { Text("${waMessage.length}/300 — blank = same as the SMS message") },
                    )
                }

                Spacer(Modifier.height(12.dp))
                Text("Alerts", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary)
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Alert me for this place", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Off keeps the place saved but never sends anything.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                    Switch(checked = alertsEnabled, onCheckedChange = { alertsEnabled = it })
                }

                if (alertsEnabled) {
                    Spacer(Modifier.height(8.dp))
                    Text("How soon to alert", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth()) {
                        Sensitivity.entries.forEach { option ->
                            FilterChip(
                                selected = sensitivity == option.id,
                                onClick = { sensitivity = option.id },
                                label = { Text(option.label) },
                                modifier = Modifier.padding(end = 6.dp),
                            )
                        }
                    }
                    Text(
                        sensitivityExplanation(sensitivity),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Shorter waits tell people sooner and catch short visits, but are more " +
                            "likely to announce a visit you did not really make. The wait only " +
                            "counts while your phone is sitting still, so a short setting will " +
                            "not fire while you are driving past.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )

                    Spacer(Modifier.height(8.dp))
                    Text("How close you must be", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth()) {
                        Closeness.entries.forEach { option ->
                            FilterChip(
                                selected = Closeness.forDbm(minRssi) == option,
                                onClick = { minRssi = option.dbm },
                                label = { Text(option.label) },
                                modifier = Modifier.padding(end = 6.dp),
                            )
                        }
                    }
                    Text(
                        "Inside only means the app must hear your network loudly, the way it " +
                            "sounds from inside the building rather than from the street.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )

                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = { showAdvanced = !showAdvanced }) {
                        Text(if (showAdvanced) "Hide advanced" else "Advanced")
                    }
                    if (showAdvanced) {
                        OutlinedTextField(
                            value = dwellOverride,
                            onValueChange = { dwellOverride = it.filter(Char::isDigit).take(3) },
                            label = { Text("Exact wait in minutes") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                            supportingText = { Text("Blank follows the choice above.") },
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = quietFrom,
                                onValueChange = { quietFrom = it.take(5) },
                                label = { Text("No alerts from") },
                                singleLine = true,
                                placeholder = { Text("23:00") },
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            OutlinedTextField(
                                value = quietTo,
                                onValueChange = { quietTo = it.take(5) },
                                label = { Text("until") },
                                singleLine = true,
                                placeholder = { Text("06:00") },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Text(
                            "Leave both blank for no quiet hours. Nothing else changes, so a real " +
                                "arrival right after the window still alerts normally.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text("Contacts", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary)
                Text(
                    "The default guardian is always notified. Anyone added below is notified too, " +
                        "one message each.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                Spacer(Modifier.height(4.dp))

                contacts.forEachIndexed { index, contact ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                contact.name.ifBlank { "(no name)" },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                contact.number,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            )
                        }
                        IconButton(onClick = { contacts.removeAt(index) }) {
                            Icon(Icons.Default.Close, contentDescription = "Remove contact",
                                Modifier.size(18.dp))
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Name (e.g. Wife)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = newNumber,
                    onValueChange = { newNumber = it; contactError = null },
                    label = { Text("Phone (03001234567)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth(),
                    isError = contactError != null,
                    supportingText = contactError?.let { { Text(it) } },
                )
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = {
                        val normalized = phoneNormalizer.normalize(newNumber)
                        if (normalized == null) {
                            contactError = "Enter a valid Pakistani number (e.g. 03001234567)"
                        } else if (contacts.any { it.number == normalized }) {
                            contactError = "That number is already in the list"
                        } else {
                            contacts.add(PlaceContact(name = newName.trim(), number = normalized))
                            newName = ""
                            newNumber = ""
                            contactError = null
                        }
                    },
                    enabled = newNumber.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add Contact")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(place.copy(
                    label = label.trim(), message = message, waMessage = waMessage,
                    contacts = contacts.toList(),
                    alertsEnabled = alertsEnabled,
                    sensitivity = sensitivity,
                    dwellMinutesOverride = dwellOverride.toIntOrNull() ?: 0,
                    quietFrom = quietFrom.trim(),
                    quietTo = quietTo.trim(),
                    minRssi = minRssi,
                ))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun sensitivityExplanation(id: String): String = when (Sensitivity.from(id)) {
    Sensitivity.QUICK ->
        "Alerts about 3 minutes after you settle here. Best for places you only stop at " +
            "briefly. A short stop next door might set it off."
    Sensitivity.CAREFUL ->
        "Alerts about 20 minutes after you settle here. Use this where neighbouring " +
            "networks overlap yours. Very short visits will end before the alert goes out."
    Sensitivity.BALANCED ->
        "Alerts about 8 minutes after you settle here. The safe middle, and what most " +
            "places should stay on."
    null -> "Currently using the app's original wait. Pick one above to change it."
}

/**
 * Captures the networks audible at a place, rather than picking one. A router
 * with two bands, a mesh, or a replacement unit all break single-network
 * detection silently, so the place keeps a set and only needs part of it.
 *
 * The strongest network and anything sharing its name are ticked to begin with,
 * because those are almost always the same physical router. Everything else is
 * deliberately left unticked: a ticked neighbour makes the app think the user is
 * home when they are not, which is the exact failure this feature exists to stop.
 */
@Composable
private fun WifiCaptureDialog(
    results: List<ScanResult>,
    alreadySaved: List<String>,
    onCapture: (selected: List<String>, strongest: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sorted = remember(results) { results.sortedByDescending { it.level }.take(15) }
    val strongest = sorted.firstOrNull()
    val preTicked = remember(sorted) {
        val siblings = sorted.filter {
            strongest != null && it.SSID.isNotBlank() && it.SSID == strongest.SSID
        }.map { it.BSSID.lowercase() }
        (siblings + alreadySaved).toSet()
    }
    val ticked = remember(preTicked) { mutableStateListOf(*preTicked.toTypedArray()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Capture networks here") },
        text = {
            if (sorted.isEmpty()) {
                Text("No networks found. Make sure WiFi is on and location is enabled.")
            } else {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "Only tick networks you know are yours. Ticking a neighbour's network " +
                            "will make the app think you are here when you are not.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                    Spacer(Modifier.height(8.dp))
                    sorted.forEach { result ->
                        val id = result.BSSID.lowercase()
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = id in ticked,
                                onCheckedChange = { on ->
                                    if (on) ticked.add(id) else ticked.remove(id)
                                },
                            )
                            Column(Modifier.weight(1f)) {
                                Text(result.SSID.ifBlank { "(Hidden network)" },
                                    style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "${result.BSSID} · ${result.level} dBm" +
                                        if (id in alreadySaved) " · already saved" else "",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCapture(ticked.toList(), strongest?.BSSID?.lowercase().orEmpty()) },
                enabled = ticked.isNotEmpty(),
            ) { Text("Save networks") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Suppress("DEPRECATION")
private fun scanWifi(context: Context): List<ScanResult> {
    // Ask for a fresh sweep first, otherwise the picker can list networks from
    // wherever the phone was the last time something scanned.
    requestWifiScan(context)
    val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    return wm?.scanResults ?: emptyList()
}

private fun requestScanOrLaunch(
    context: Context,
    launcher: androidx.activity.result.ActivityResultLauncher<String>,
    onGranted: () -> Unit,
) {
    if (hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)) onGranted()
    else launcher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
}

// Detection genuinely cannot work without this one.
/**
 * Google Play's prominent disclosure. It has to appear before the system
 * permission dialog, say plainly what is collected and that it continues while
 * the app is closed, and offer a real way to decline. Wording it as anything
 * other than a straight description of the behaviour is what gets apps pulled.
 */
@Composable
private fun LocationDisclosureDialog(onAgree: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Before you turn this on") },
        text = {
            Column {
                Text(
                    "To tell when you have arrived somewhere, TextGate reads which WiFi " +
                        "networks are near your phone. Android counts that as location data, " +
                        "so it asks for the location permission.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "This keeps running while the app is closed, with a permanent notification " +
                        "showing while it does. It is the only way an alert can be sent when you " +
                        "get home without you opening the app first.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Network names never leave your phone. When you arrive, only the place " +
                        "label you chose is sent to the people you picked. You can switch this " +
                        "off here at any time.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = { TextButton(onClick = onAgree) { Text("Continue") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}

private fun requiredMonitoringPermissions(): List<String> = listOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
)

// Asked for together, but declining any of these must not switch the feature
// off. The notification permission only decides whether the ongoing notice is
// visible, and step counting only sharpens the wait; treating either as a
// precondition is how a single declined prompt used to disable arrivals for good.
private fun monitoringPermissionsToRequest(): List<String> = buildList {
    addAll(requiredMonitoringPermissions())
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        add(Manifest.permission.ACTIVITY_RECOGNITION)
    }
}

private fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

// ── Preview helpers ───────────────────────────────────────────────────────────

private val previewPlaces = listOf(
    Place(Place.HOME_ID, "My Home", "AA:BB:CC:DD:EE:01",
        contacts = listOf(PlaceContact("Wife", "+923001111111"), PlaceContact("Parents", "+923002222222"))),
    Place(Place.OFFICE_ID, "Office", "AA:BB:CC:DD:EE:02",
        contacts = listOf(PlaceContact("Manager", "+923003333333"))),
    Place("place_1", "Ali's home", "AA:BB:CC:DD:EE:03", message = "Reached Ali's place safely"),
)

@Preview(showBackground = true, name = "Settings — Loading")
@Composable
private fun SettingsLoadingPreview() {
    TextGateTheme {
        SettingsContent(
            uiState = SettingsUiState(isLoading = true),
            guardianNumber = "", onGuardianChange = {},
            onEditPlace = {}, onAddPlace = {}, onRemovePlace = {}, onScanPlace = {},
            isMonitoring = false, onMonitoringToggle = {},
            onSave = {}, onBack = {},
        )
    }
}

@Preview(showBackground = true, name = "Settings — Places with contacts")
@Composable
private fun SettingsFilledPreview() {
    TextGateTheme {
        SettingsContent(
            uiState = SettingsUiState(
                isLoading = false,
                guardianNumber = "03001234567",
                places = previewPlaces,
            ),
            guardianNumber = "03001234567", onGuardianChange = {},
            onEditPlace = {}, onAddPlace = {}, onRemovePlace = {}, onScanPlace = {},
            isMonitoring = true, onMonitoringToggle = {},
            onSave = {}, onBack = {},
        )
    }
}

@Preview(showBackground = true, name = "Settings — Place editor dialog")
@Composable
private fun PlaceEditorPreview() {
    TextGateTheme {
        PlaceEditorDialog(place = previewPlaces.first(), onSave = {}, onDismiss = {})
    }
}
