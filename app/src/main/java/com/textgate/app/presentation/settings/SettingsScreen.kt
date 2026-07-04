@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.textgate.app.core.theme.TextGateTheme
import com.textgate.app.core.utils.PhoneNormalizer
import com.textgate.app.domain.model.Place
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
    var isMonitoring by remember { mutableStateOf(ArrivalService.isRunning) }

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
            ArrivalService.start(context)
        } else {
            isMonitoring = false
            scope.launch {
                snackbarHostState.showSnackbar("Location and notification permissions are required")
            }
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

    SettingsContent(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        guardianNumber = guardianNumber,
        onGuardianChange = { guardianNumber = it },
        onAddGuardianNumber = viewModel::addGuardianNumber,
        onRemoveGuardianNumber = viewModel::removeGuardianNumber,
        onTogglePlaceRecipient = viewModel::togglePlaceRecipient,
        onPlaceChange = viewModel::updatePlace,
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
                    ArrivalService.start(context)
                } else {
                    monitoringPermissionLauncher.launch(missing.toTypedArray())
                }
            } else {
                isMonitoring = false
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
    onAddGuardianNumber: (String) -> Unit = {},
    onRemoveGuardianNumber: (String) -> Unit = {},
    onTogglePlaceRecipient: (String, String) -> Unit = { _, _ -> },
    onPlaceChange: (Place) -> Unit,
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
            SectionTitle("Guardian Contact")
            Text(
                "Arrival notifications go to this number (WhatsApp when linked, SMS otherwise)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = guardianNumber,
                onValueChange = onGuardianChange,
                label = { Text("Default Guardian Phone (Pakistani, e.g. 03001234567)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth(),
                isError = guardianError != null,
                supportingText = guardianError?.let { { Text(it) } },
            )

            Spacer(Modifier.height(20.dp))
            SectionTitle("Additional Guardian Numbers")
            Text(
                "Save more numbers here, then pick which ones each place notifies " +
                    "(e.g. message two people when you reach a friend's home). Places " +
                    "with none selected fall back to the default guardian above.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(8.dp))
            GuardianNumbersEditor(
                numbers = uiState.guardianNumbers,
                onAdd = onAddGuardianNumber,
                onRemove = onRemoveGuardianNumber,
            )

            Spacer(Modifier.height(20.dp))
            SectionTitle("Places")
            Text(
                "Home and Office are always available; add any other place (friend's home, gym, …). " +
                    "Each place can have its own arrival message and recipients.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(8.dp))

            uiState.places.forEach { place ->
                key(place.id) {
                    PlaceCard(
                        place = place,
                        defaultGuardian = guardianNumber,
                        savedNumbers = uiState.guardianNumbers,
                        onToggleRecipient = { number -> onTogglePlaceRecipient(place.id, number) },
                        onChange = onPlaceChange,
                        onScan = { onScanPlace(place.id) },
                        onRemove = if (Place.isDefaultId(place.id)) null else ({ onRemovePlace(place.id) }),
                    )
                    Spacer(Modifier.height(12.dp))
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

@Composable
private fun PlaceCard(
    place: Place,
    defaultGuardian: String,
    savedNumbers: List<String>,
    onToggleRecipient: (String) -> Unit,
    onChange: (Place) -> Unit,
    onScan: () -> Unit,
    onRemove: (() -> Unit)?,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = place.label,
                    onValueChange = { onChange(place.copy(label = it)) },
                    label = { Text("Place name") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("e.g. Ali's home") },
                )
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
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = place.bssid,
                    onValueChange = {},
                    label = { Text("WiFi BSSID") },
                    readOnly = true, singleLine = true,
                    placeholder = { Text("Tap Scan to select") },
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = onScan) {
                    Icon(Icons.Default.Wifi, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Scan")
                }
            }
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = place.message,
                onValueChange = { if (it.length <= 90) onChange(place.copy(message = it)) },
                label = { Text("Custom arrival message (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Default: \"<your name> arrived at ${place.label.ifBlank { "place" }}\"") },
                supportingText = { Text("${place.message.length}/90") },
            )
            if (savedNumbers.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Notify for this place",
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    if (place.recipients.isEmpty()) {
                        "Default guardian (${defaultGuardian.ifBlank { "not set" }})"
                    } else {
                        "${place.recipients.size} selected"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                Spacer(Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    savedNumbers.forEach { number ->
                        FilterChip(
                            selected = number in place.recipients,
                            onClick = { onToggleRecipient(number) },
                            label = { Text(number) },
                        )
                    }
                }
            }
        }
    }
}

// Add/list/remove the pool of extra guardian numbers a place can be pointed at.
@Composable
private fun GuardianNumbersEditor(
    numbers: List<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    var newNumber by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = newNumber,
            onValueChange = { newNumber = it },
            label = { Text("Add a number (03001234567)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        OutlinedButton(
            onClick = {
                if (newNumber.isNotBlank()) {
                    onAdd(newNumber)
                    newNumber = ""
                }
            },
        ) {
            Icon(Icons.Default.Add, contentDescription = "Add number", Modifier.size(18.dp))
        }
    }
    if (numbers.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        numbers.forEach { number ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(number, style = MaterialTheme.typography.bodyMedium)
                IconButton(onClick = { onRemove(number) }) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Remove $number",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
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
    Place(Place.HOME_ID, "My Home", "AA:BB:CC:DD:EE:01"),
    Place(Place.OFFICE_ID, "Office", "AA:BB:CC:DD:EE:02"),
    Place("place_1", "Ali's home", "AA:BB:CC:DD:EE:03", "Reached Ali's place safely"),
)

@Preview(showBackground = true, name = "Settings — Loading")
@Composable
private fun SettingsLoadingPreview() {
    TextGateTheme {
        SettingsContent(
            uiState = SettingsUiState(isLoading = true),
            guardianNumber = "", onGuardianChange = {},
            onPlaceChange = {}, onAddPlace = {}, onRemovePlace = {}, onScanPlace = {},
            isMonitoring = false, onMonitoringToggle = {},
            onSave = {}, onBack = {},
        )
    }
}

@Preview(showBackground = true, name = "Settings — Places filled")
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
            onPlaceChange = {}, onAddPlace = {}, onRemovePlace = {}, onScanPlace = {},
            isMonitoring = true, onMonitoringToggle = {},
            onSave = {}, onBack = {},
        )
    }
}

@Preview(showBackground = true, name = "Settings — Defaults only")
@Composable
private fun SettingsEmptyPreview() {
    TextGateTheme {
        SettingsContent(
            uiState = SettingsUiState(isLoading = false, places = Place.defaults()),
            guardianNumber = "", onGuardianChange = {},
            onPlaceChange = {}, onAddPlace = {}, onRemovePlace = {}, onScanPlace = {},
            isMonitoring = false, onMonitoringToggle = {},
            onSave = {}, onBack = {},
        )
    }
}
