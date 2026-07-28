package com.textgate.app.presentation.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
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
import com.textgate.app.core.theme.TextGateTheme
import com.textgate.app.core.utils.PhoneNormalizer
import com.textgate.app.domain.model.Place
import com.textgate.app.domain.model.PlaceContact
import com.textgate.app.services.ArrivalService
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
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
        if (granted) {
            isMonitoring = true
            scope.launch { viewModel.setMonitoringEnabled(true) }
            ArrivalService.start(context)
        } else {
            isMonitoring = false
        }
    }

    LaunchedEffect(uiState.saveSuccess) {
        if (uiState.saveSuccess) {
            snackbarHostState.showSnackbar("Settings saved")
            viewModel.clearSuccess()
        }
    }

    if (showScanDialog) {
        WifiPickerDialog(
            results = scanResults,
            onSelect = { result ->
                val targetId = scanTargetPlaceId
                val place = uiState.places.firstOrNull { it.id == targetId }
                if (place != null) {
                    viewModel.updatePlace(
                        place.copy(
                            bssid = result.BSSID,
                            label = place.label.ifBlank { result.SSID.ifBlank { result.BSSID } },
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
                val missing = requiredMonitoringPermissions()
                    .filterNot { permission -> hasPermission(context, permission) }
                if (missing.isEmpty()) {
                    isMonitoring = true
                    scope.launch { viewModel.setMonitoringEnabled(true) }
                    ArrivalService.start(context)
                } else {
                    monitoringPermissionLauncher.launch(missing.toTypedArray())
                }
            } else {
                isMonitoring = false
                scope.launch { viewModel.setMonitoringEnabled(false) }
                ArrivalService.stop(context)
            }
        },
        onSave = { guardian -> viewModel.save(guardian) },
        onBack = onBack,
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
    onBack: () -> Unit,
) {
    val phoneNormalizer = remember { PhoneNormalizer() }
    val guardianError = phoneNormalizer.validationError(guardianNumber)

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Arrival Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
            SectionTitle("Default Guardian")
            Text(
                "Used for places that have no contacts of their own",
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
                "Tap a place to manage its contacts and arrival message.",
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
                        onRemove = if (Place.isDefaultId(place.id)) null else ({ onRemovePlace(place.id) }),
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
                    Text("Save Settings", style = MaterialTheme.typography.titleLarge)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
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
                            append(if (place.bssid.isBlank()) "No WiFi set" else "WiFi ${place.bssid}")
                            append(" · ")
                            append(
                                when (place.contacts.size) {
                                    0 -> "default guardian"
                                    1 -> "1 contact"
                                    else -> "${place.contacts.size} contacts"
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
                    Text("Scan")
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
                Text("Contacts", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary)
                Text(
                    if (contacts.isEmpty()) "None yet — the default guardian will be notified."
                    else "Everyone below is notified when you arrive here.",
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
                onSave(place.copy(label = label.trim(), message = message, waMessage = waMessage, contacts = contacts.toList()))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun WifiPickerDialog(
    results: List<ScanResult>,
    onSelect: (ScanResult) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select WiFi Network") },
        text = {
            if (results.isEmpty()) {
                Text("No networks found. Make sure WiFi is on and location is enabled.")
            } else {
                Column {
                    results.sortedByDescending { it.level }.take(10).forEach { result ->
                        TextButton(onClick = { onSelect(result) }, modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(result.SSID.ifBlank { "(Hidden network)" },
                                    style = MaterialTheme.typography.bodyMedium)
                                Text(result.BSSID, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Suppress("DEPRECATION")
private fun scanWifi(context: Context): List<ScanResult> {
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

private fun requiredMonitoringPermissions(): List<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
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
