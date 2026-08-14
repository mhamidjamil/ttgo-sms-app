package com.spotwire.app.presentation.settings

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ListAlt
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.spotwire.app.core.theme.OnWarningAmber
import com.spotwire.app.core.theme.StatusFailed
import com.spotwire.app.core.theme.StatusSent
import com.spotwire.app.core.theme.SpotwireTheme
import com.spotwire.app.core.theme.WarningAmber
import com.spotwire.app.core.theme.WarningAmberBorder
import com.spotwire.app.core.utils.PhoneNormalizer
import com.spotwire.app.presentation.components.PhoneNumberField
import com.spotwire.app.presentation.components.rememberDefaultCountry
import com.spotwire.app.core.utils.PresenceReading
import com.spotwire.app.core.utils.canScanWifi
import com.spotwire.app.core.utils.freshScan
import com.spotwire.app.core.utils.requestWifiScan
import com.spotwire.app.core.utils.resolvePresence
import com.spotwire.app.core.utils.scanBlocker
import com.spotwire.app.domain.model.Place
import com.spotwire.app.domain.model.Closeness
import com.spotwire.app.domain.model.PlaceContact
import com.spotwire.app.domain.model.PresenceState
import com.spotwire.app.domain.model.Sensitivity
import com.spotwire.app.services.ArrivalService
import com.spotwire.app.services.ArrivalWatchdogReceiver
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import java.util.Locale

private const val TAG_SETTINGS = "SpotwireSettings"

// A phone's own hardware stops improving around here, so the search ends itself
// instead of holding the GPS on for a number that will not move again.
private const val EXCELLENT_ACCURACY_M = 5f

// Past this the circle can sit far enough off the real place that an arrival is
// never noticed, which is worth saying out loud before the place is saved.
private const val VAGUE_ACCURACY_M = 25f

/**
 * Arrival monitoring settings. Reached as its own bottom-bar tab, where there is
 * nothing to go back to — pass a null [onBack] to hide the back arrow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: (() -> Unit)? = null,
    onViewChangeHistory: () -> Unit = {},
    onViewMonitorLog: () -> Unit = {},
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
    var isMonitoring by remember { mutableStateOf(false) }
    var showLocationDisclosure by remember { mutableStateOf(false) }
    var showBackgroundDisclosure by remember { mutableStateOf(false) }
    // Re-read rather than sampled once: on Android 11 and up the answer is given
    // on a system screen that hands nothing back, so the only way to know is to
    // look again.
    var backgroundLocationAllowed by remember { mutableStateOf(hasBackgroundLocation(context)) }

    // Offered only once monitoring is already on: Android refuses the
    // all-the-time grant until the while-in-use one has been given, and a place
    // with no point on the map has no fence that could fire anyway.
    fun offerBackgroundLocation() {
        if (backgroundLocationAllowed || uiState.places.none { it.hasGeofence }) return
        showBackgroundDisclosure = true
    }

    // The switch says what the user asked for, which is what prefs hold, not
    // whether the service happens to be alive: a service the system killed used
    // to turn the switch off by itself. Whether it is actually running is a
    // separate line under it. If it was killed, restart it silently here, but
    // only once the places are loaded and only if the sweeps are needed at all:
    // starting a service that stops itself again would flash a notification
    // every time this tab is opened.
    LaunchedEffect(uiState.isLoading) {
        if (uiState.isLoading) return@LaunchedEffect
        isMonitoring = viewModel.getMonitoringEnabled()
        if (!isMonitoring || ArrivalService.isRunning) return@LaunchedEffect
        if (!ArrivalService.needsResidentService(uiState.places, context)) return@LaunchedEffect
        val missing = requiredMonitoringPermissions()
            .filterNot { permission -> hasPermission(context, permission) }
        if (missing.isEmpty()) ArrivalService.start(context)
    }

    // Detection health used to be read once per process, so coming back to this
    // tab days later showed the sweep from the first time it was opened. Ten
    // seconds matches the cadence the monitoring log already refreshes on.
    LaunchedEffect(Unit) {
        while (true) {
            viewModel.loadHealth()
            backgroundLocationAllowed = hasBackgroundLocation(context)
            delay(10_000)
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
            offerBackgroundLocation()
        } else {
            isMonitoring = false
        }
    }

    val backgroundLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // The result handed back on Android 11 and up is the answer from before
        // the settings screen opened, so the permission is read again rather
        // than believed. A grant has to reach the fence watcher now: nothing
        // else would register them until the next service start.
        backgroundLocationAllowed = hasBackgroundLocation(context)
        if (backgroundLocationAllowed) viewModel.refreshGeofences(context, uiState.places)
    }

    if (showBackgroundDisclosure) {
        BackgroundLocationDialog(
            onContinue = {
                showBackgroundDisclosure = false
                backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            },
            onDismiss = { showBackgroundDisclosure = false },
        )
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
            onSave = { updated, fixAccuracy ->
                viewModel.updatePlace(updated, fixAccuracy)
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
                    offerBackgroundLocation()
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
                viewModel.clearGeofences(context)
            }
        },
        geofencesBlocked = !backgroundLocationAllowed && uiState.places.any { it.hasGeofence },
        onFixGeofences = { showBackgroundDisclosure = true },
        onSave = { guardian -> viewModel.save(guardian) },
        onSendLocation = viewModel::openLocationPrompt,
        onBack = onBack,
        onViewChangeHistory = onViewChangeHistory,
        onViewMonitorLog = onViewMonitorLog,
        onAccountCheck = { viewModel.accountHealthLine() },
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
    geofencesBlocked: Boolean = false,
    onFixGeofences: () -> Unit = {},
    onSave: (String) -> Unit,
    onSendLocation: (String) -> Unit = {},
    onBack: (() -> Unit)? = null,
    onViewChangeHistory: () -> Unit = {},
    onViewMonitorLog: () -> Unit = {},
    onAccountCheck: suspend () -> String = { "" },
) {
    val phoneNormalizer = remember { PhoneNormalizer() }
    val defaultCountry = rememberDefaultCountry()
    var guardianCountry by remember { mutableStateOf(defaultCountry) }
    val guardianValid = guardianNumber.isBlank() || phoneNormalizer.isValid(guardianNumber, guardianCountry)

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
                    IconButton(onClick = onViewMonitorLog) {
                        Icon(Icons.AutoMirrored.Filled.ListAlt, contentDescription = "Monitoring log")
                    }
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
            if (uiState.noDeliveryRoute) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(WarningAmber)
                        .border(1.dp, WarningAmberBorder)
                        .padding(12.dp),
                ) {
                    CompositionLocalProvider(LocalContentColor provides OnWarningAmber) {
                        Column {
                            Text(
                                "⚠ Arrivals cannot be delivered",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "Monitoring will still detect you arriving, but there is nothing to " +
                                    "send with: connect your WhatsApp gateway in Profile, or verify " +
                                    "your number to send text messages.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            CurrentPlaceCheck(uiState.places, onSendLocation)
            Spacer(Modifier.height(20.dp))

            SectionTitle("Default Guardian")
            Text(
                "Always notified, on top of any contacts a place has of its own",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(8.dp))
            PhoneNumberField(
                number = guardianNumber,
                onNumberChange = onGuardianChange,
                country = guardianCountry,
                onCountryChange = { guardianCountry = it },
                label = "Guardian Phone",
                modifier = Modifier.fillMaxWidth(),
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
            // A service that is down because it has nothing to do must not read
            // like one the system killed: in hybrid mode not running IS the
            // healthy state, and the old line told the user to wait for a
            // recovery that is never coming.
            val fencesAreWatching =
                ArrivalService.watchedByGeofenceOnly(uiState.places, LocalContext.current)
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
                        when {
                            !isMonitoring -> "Tap to start"
                            uiState.serviceRunning -> "Running in background"
                            fencesAreWatching ->
                                "Watching by geofence, nothing needs to run in the background"
                            else -> "Not running right now, it will be brought back by the " +
                                "15 minute check"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
                Switch(checked = isMonitoring, onCheckedChange = onMonitoringToggle)
            }

            // Shown whenever monitoring is meant to be on, including when the
            // service is down: a stopped service is exactly when the battery
            // notice and the last successful check are worth reading.
            if (isMonitoring) {
                if (geofencesBlocked) GeofenceBlockedNotice(onFixGeofences)
                BatteryExemptionNotice()
                DetectionHealthCard(uiState, onAccountCheck)
            }

            uiState.error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(28.dp))
            Button(
                onClick = {
                    val normalized = phoneNormalizer.normalize(guardianNumber, guardianCountry)
                        ?: guardianNumber
                    onSave(normalized)
                },
                enabled = guardianValid && !uiState.isSaving,
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
private fun DetectionHealthCard(uiState: SettingsUiState, onAccountCheck: suspend () -> String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var verdict by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
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
            // Waiting out a real scan takes a few seconds, so the button has to
            // say it is working or the first tap looks like it did nothing.
            OutlinedButton(
                onClick = {
                    scope.launch {
                        testing = true
                        verdict = onAccountCheck() + "\n" + describeDetectionNow(context, uiState.places)
                        testing = false
                    }
                },
                enabled = !testing,
            ) {
                Text(if (testing) "Checking..." else "Test detection here")
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
private suspend fun describeDetectionNow(context: Context, places: List<Place>): String {
    scanBlocker(context)?.let { return "Cannot scan. $it." }
    val visible = freshScan(context)
    if (visible.isEmpty()) {
        return "No networks heard on this read. A fresh scan takes a few seconds, try again."
    }
    val saved = places.filter { it.savedBssids.isNotEmpty() }
    if (saved.isEmpty()) return "Heard ${visible.size} networks, but no place has any saved yet."

    val presence = resolvePresence(saved, visible)
    val winner = presence.winner
    val lines = presence.readings.map { reading ->
        val place = reading.place
        val name = place.label.ifBlank { place.id }
        when {
            reading.heardCount == 0 -> "$name: none of its ${place.savedBssids.size} networks heard."
            reading.heardCount < place.requiredMatches ->
                "$name: heard ${reading.heardCount} of its networks, needs ${place.requiredMatches}."
            reading.strongest < place.minRssi ->
                "$name: heard at ${reading.strongest} dBm, too weak for the closeness setting " +
                    "(${place.minRssi} dBm). This is what walking past outside looks like."
            !place.alertsEnabled -> "$name: here, but its alerts are switched off."
            winner == null -> "$name: in range at ${reading.strongest} dBm, but no place wins this check."
            winner.id != place.id -> "$name: in range at ${reading.strongest} dBm, but " +
                "${winner.label.ifBlank { winner.id }} is nearer."
            // Zero is the marker for being connected to the place's own
            // network, which outranks any real reading and looks absurd printed.
            reading.strongest == 0 ->
                "$name: here, connected to its network. It would alert after the wait."
            else -> "$name: here, at ${reading.strongest} dBm. It would alert after the wait."
        }
    }
    val tie = if (!presence.contested) emptyList() else listOf(
        presence.closest.joinToString(" and ") { it.place.label.ifBlank { it.place.id } } +
            " too close to separate, no winner."
    )
    return (lines + tie).joinToString("\n")
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

/**
 * Places that know where they are cannot be watched by the system's fence
 * watcher without the all-the-time grant, and the difference is invisible
 * otherwise: arrivals still work, they just cost the battery a service running
 * around the clock. The settings button is also the only way back from a
 * permanently denied grant, which the copy elsewhere already promises.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GeofenceBlockedNotice(onExplain: () -> Unit) {
    val context = LocalContext.current
    Spacer(Modifier.height(8.dp))
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "Geofences are off: background location is not allowed",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Your places are still found by sweeping for WiFi networks, which works but " +
                    "keeps monitoring running all day. Choosing \"Allow all the time\" lets " +
                    "Android wake the app only when you actually arrive.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onExplain) { Text("Allow all the time") }
                OutlinedButton(onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(
                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.parse("package:${context.packageName}"),
                            )
                        )
                    }.onFailure {
                        Log.w(TAG_SETTINGS, "App settings screen unavailable: ${it.message}")
                    }
                }) { Text("Open app settings") }
            }
        }
    }
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean =
    context.getSystemService(android.os.PowerManager::class.java)
        ?.isIgnoringBatteryOptimizations(context.packageName) ?: false

/**
 * "Where am I?" — answers what the arrival service would decide right now,
 * without waiting out the stability window or an SMS. It runs the service's own
 * matcher on a scan it waited for, so a place has to be heard often enough and
 * loudly enough and beat every other place in range. The looser rule this used
 * to run said "you are at Office" while walking past it on the street.
 */
@Composable
private fun CurrentPlaceCheck(places: List<Place>, onSendLocation: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checked by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var presence by remember { mutableStateOf<PresenceReading?>(null) }
    var heardCount by remember { mutableStateOf(0) }
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
            val match = presence?.winner
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = {
                        scope.launch {
                            checking = true
                            val visible = freshScan(context)
                            heardCount = visible.size
                            presence = resolvePresence(places, visible)
                            scanningAvailable = canScanWifi(context)
                            checked = true
                            checking = false
                        }
                    },
                    enabled = !checking,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Wifi, contentDescription = null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    // A scan the phone has not delivered yet takes a few seconds,
                    // and a button that looks idle gets tapped again.
                    Text(if (checking) "Checking..." else "Check current place")
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
                val tie = presence?.takeIf { it.contested }?.closest.orEmpty()
                Spacer(Modifier.height(10.dp))
                Text(
                    when {
                        match != null -> "You are at ${match.label.ifBlank { match.id }}"
                        tie.isNotEmpty() -> "Two places are both in range, too close to call: " +
                            tie.joinToString(" and ") { it.place.label.ifBlank { it.place.id } }
                        heardCount == 0 -> "No networks in range"
                        else -> "Unknown place"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (match != null) StatusSent else StatusFailed,
                )
                Text(
                    when {
                        match != null -> "$heardCount networks in range, one of them is this place"
                        tie.isNotEmpty() -> tie.joinToString(", ") {
                            "${it.place.label.ifBlank { it.place.id }} at ${it.strongest} dBm"
                        } + ". Nothing is sent until one of them is clearly nearer."
                        !scanningAvailable ->
                            "Turn on WiFi, or turn on \"WiFi scanning always available\" in the " +
                                "system WiFi settings. Arrival alerts cannot fire in this state."
                        heardCount == 0 ->
                            "Location permission or device location is off, so the phone cannot " +
                                "see any networks. Arrival alerts cannot fire in this state."
                        else -> "$heardCount networks in range, none of them saved on a place below"
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
                    // How this place is actually watched, in one quiet line: a
                    // fence costs nothing until it fires, WiFi-only means the
                    // sweeps have to keep running for it.
                    val watchMode = when {
                        place.hasGeofence -> "Geofence: ${place.radiusMeters} m radius"
                        place.savedBssids.isNotEmpty() -> "WiFi sweeps only"
                        else -> null
                    }
                    watchMode?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
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
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlaceEditorDialog(
    place: Place,
    showWaMessage: Boolean = false,
    onSave: (Place, Float?) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val phoneNormalizer = remember { PhoneNormalizer() }
    val defaultCountry = rememberDefaultCountry()
    var contactCountry by remember { mutableStateOf(defaultCountry) }
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
    // Blank rather than "0.0" for a place that has never been located, so the
    // fields read as empty instead of as a point in the Atlantic.
    val placeIsLocated = place.latitude != 0.0 || place.longitude != 0.0
    var latitudeText by remember {
        mutableStateOf(if (placeIsLocated) place.latitude.toString() else "")
    }
    var longitudeText by remember {
        mutableStateOf(if (placeIsLocated) place.longitude.toString() else "")
    }
    var radiusText by remember {
        mutableStateOf(place.radiusMeters.takeIf { it > 0 }?.toString() ?: "")
    }
    var locating by remember { mutableStateOf(false) }
    var fixAccuracy by remember { mutableStateOf<Float?>(null) }
    var locationError by remember { mutableStateOf<String?>(null) }
    var searchSeconds by remember { mutableIntStateOf(0) }

    val draftLatitude = latitudeText.trim().toDoubleOrNull()
    val draftLongitude = longitudeText.trim().toDoubleOrNull()
    val latitudeError = latitudeText.isNotBlank() && draftLatitude?.let { it in -90.0..90.0 } != true
    val longitudeError = longitudeText.isNotBlank() && draftLongitude?.let { it in -180.0..180.0 } != true
    val hasCoordinates = draftLatitude != null && draftLongitude != null &&
        !latitudeError && !longitudeError
    val radiusMeters = radiusText.toIntOrNull() ?: Place.DEFAULT_RADIUS_METERS

    val fusedLocation = remember { LocationServices.getFusedLocationProviderClient(context) }
    // A single fix is whatever the phone had lying around: on this phone the
    // first one came back stale and hundreds of metres out, and it was that
    // position that got saved onto the place. So fixes are watched as they
    // arrive and only a strictly better one is kept, the way a map app tightens
    // its blue circle while you stand still.
    val refinement = remember {
        object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val fix = result.lastLocation ?: return
                // A fix that carries no accuracy reports zero metres, which would
                // read as perfect and end the search on the worst reading of all.
                if (!fix.hasAccuracy()) return
                val best = fixAccuracy
                if (best != null && fix.accuracy >= best) return
                fixAccuracy = fix.accuracy
                latitudeText = String.format(Locale.US, "%.6f", fix.latitude)
                longitudeText = String.format(Locale.US, "%.6f", fix.longitude)
                if (radiusText.isBlank()) radiusText = Place.DEFAULT_RADIUS_METERS.toString()
                if (fix.accuracy <= EXCELLENT_ACCURACY_M) {
                    fusedLocation.removeLocationUpdates(this)
                    locating = false
                }
            }
        }
    }

    fun stopRefining() {
        fusedLocation.removeLocationUpdates(refinement)
        locating = false
    }

    @SuppressLint("MissingPermission")
    fun startRefining() {
        locationError = null
        // The old best is dropped rather than kept: someone refining again has
        // usually moved, and a stale tight reading would reject every fix from
        // where they are actually standing now.
        fixAccuracy = null
        locating = true
        fusedLocation.requestLocationUpdates(
            LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
                .setMinUpdateIntervalMillis(500)
                .build(),
            refinement,
            Looper.getMainLooper(),
        )
    }

    // Closing the dialog mid-search must take the updates with it. Location that
    // outlives the screen that asked for it is invisible battery drain, and it is
    // the exact background use Google Play pulls apps off the store for.
    DisposableEffect(Unit) {
        onDispose { fusedLocation.removeLocationUpdates(refinement) }
    }

    LaunchedEffect(locating) {
        if (!locating) return@LaunchedEffect
        searchSeconds = 0
        while (true) {
            delay(1_000)
            searchSeconds++
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startRefining()
        else locationError = "Location permission is off, so this phone cannot report where it is."
    }

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
                Text("Location", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary)
                Text(
                    "Optional. A place that knows where it is on the map can be set up before " +
                        "you have ever been there, and is still found when its WiFi is off.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                Spacer(Modifier.height(6.dp))
                // Nothing is thrown away on a clock here: the search runs until
                // the user is happy with the accuracy they can see, because
                // indoors it is the person, not a timer, who knows whether it is
                // worth walking to a window for another twenty metres.
                OutlinedButton(
                    onClick = {
                        if (locating) stopRefining()
                        else if (hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)) startRefining()
                        else locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(when {
                        locating -> "Stop and use this"
                        fixAccuracy != null -> "Refine again"
                        else -> "Use my current location"
                    })
                }
                // The two fields below are the readout the rest of the time; these
                // lines add what they cannot show, which is how good the fix is
                // and whether it is still getting better.
                fixAccuracy?.let { accuracy ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "$latitudeText, $longitudeText",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        if (locating) "Accurate to ${accuracy.toInt()} m and improving, ${searchSeconds}s so far"
                        else "Accurate to ${accuracy.toInt()} m",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
                if (locating && fixAccuracy == null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Searching for satellites, this can take a minute indoors (${searchSeconds}s)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                    // Half a minute with nothing at all usually means concrete
                    // between the phone and the sky, so say what actually helps
                    // rather than giving up on them.
                    if (searchSeconds >= 30) {
                        Text(
                            "Try moving near a window; WiFi and Bluetooth scanning both improve accuracy",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                }
                locationError?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        // Typing is an override, so a search still running has to
                        // let go rather than overwrite the field a second later.
                        value = latitudeText,
                        onValueChange = { if (locating) stopRefining(); latitudeText = it; fixAccuracy = null },
                        label = { Text("Latitude") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                        isError = latitudeError,
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = longitudeText,
                        onValueChange = { if (locating) stopRefining(); longitudeText = it; fixAccuracy = null },
                        label = { Text("Longitude") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                        isError = longitudeError,
                    )
                }
                // Saving a vague fix is allowed, because a courtyard between tall
                // walls may never do better, but it is never allowed to be a
                // surprise later when the place is silently never detected.
                fixAccuracy?.takeIf { !locating && it > VAGUE_ACCURACY_M }?.let { accuracy ->
                    Spacer(Modifier.height(6.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(WarningAmber)
                            .border(1.dp, WarningAmberBorder)
                            .padding(8.dp),
                    ) {
                        Text(
                            "This fix is only accurate to ${accuracy.toInt()} m. The place circle " +
                                "may sit off target; keep refining or set the position by hand.",
                            style = MaterialTheme.typography.bodySmall,
                            color = OnWarningAmber,
                        )
                    }
                }
                if (hasCoordinates) {
                    Spacer(Modifier.height(8.dp))
                    Text("Detection radius: $radiusMeters meters",
                        style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(100, 150, 200, 300, 500).forEach { meters ->
                            FilterChip(
                                selected = radiusMeters == meters,
                                onClick = { radiusText = meters.toString() },
                                label = { Text("$meters m") },
                            )
                        }
                    }
                    OutlinedTextField(
                        value = radiusText,
                        onValueChange = { radiusText = it.filter(Char::isDigit).take(3) },
                        label = { Text("Custom") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        supportingText = {
                            Text("Meters. Anything outside ${Place.MIN_RADIUS_METERS} to " +
                                "${Place.MAX_RADIUS_METERS} is pulled back into range on save.")
                        },
                    )
                    TextButton(onClick = {
                        if (locating) stopRefining()
                        latitudeText = ""
                        longitudeText = ""
                        radiusText = ""
                        fixAccuracy = null
                        locationError = null
                    }) { Text("Clear location") }
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
                PhoneNumberField(
                    number = newNumber,
                    onNumberChange = { newNumber = it; contactError = null },
                    country = contactCountry,
                    onCountryChange = { contactCountry = it },
                    label = "Phone",
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = contactError,
                )
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = {
                        val normalized = phoneNormalizer.normalize(newNumber, contactCountry)
                        if (normalized == null) {
                            contactError = "Enter a valid mobile number for the country selected"
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
                    latitude = if (hasCoordinates) draftLatitude ?: 0.0 else 0.0,
                    longitude = if (hasCoordinates) draftLongitude ?: 0.0 else 0.0,
                    radiusMeters = if (!hasCoordinates) 0 else
                        radiusMeters.coerceIn(Place.MIN_RADIUS_METERS, Place.MAX_RADIUS_METERS),
                ), fixAccuracy.takeIf { hasCoordinates })
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
                    "To tell when you have arrived somewhere, Spotwire reads which WiFi " +
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
                    "Your saved places are stored in your own account: the WiFi networks you " +
                        "captured there, and the map position and radius if you set one. When " +
                        "you arrive, the people you picked are told the place name and the " +
                        "time, never a network name or a map position. You can switch this off " +
                        "here at any time.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = { TextButton(onClick = onAgree) { Text("Continue") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}

/**
 * The second half of the disclosure, asked only after monitoring is on. It has
 * to explain what the phone gains rather than just name a permission, because
 * "Allow all the time" sounds like more surveillance when it actually buys the
 * opposite: the app sleeps until a fence fires instead of scanning all day.
 */
@Composable
private fun BackgroundLocationDialog(onContinue: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Allow all-the-time location") },
        text = {
            Column {
                Text(
                    "Your places have a point on the map, so Android itself can watch for you " +
                        "crossing into them and wake Spotwire at the moment you arrive, even " +
                        "with the app closed. That only happens if location is set to " +
                        "\"Allow all the time\".",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Without it, arrivals are found by sweeping for WiFi networks around the " +
                        "clock instead. That still works, it just keeps monitoring running all " +
                        "day and costs noticeably more battery.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "On newer phones Continue opens Android's own location screen for this app, " +
                        "where the choice is listed as \"Allow all the time\".",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        },
        confirmButton = { TextButton(onClick = onContinue) { Text("Continue") } },
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

// Before Android 10 there is no separate all-the-time grant to ask for: the
// while-in-use one already covers a service running in the background.
private fun hasBackgroundLocation(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        hasPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)

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
    SpotwireTheme {
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
    SpotwireTheme {
        SettingsContent(
            uiState = SettingsUiState(
                isLoading = false,
                guardianNumber = "03001234567",
                places = previewPlaces,
                serviceRunning = true,
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
    SpotwireTheme {
        PlaceEditorDialog(place = previewPlaces.first(), onSave = { _, _ -> }, onDismiss = {})
    }
}
