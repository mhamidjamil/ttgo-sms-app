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
import androidx.compose.material.icons.filled.ArrowBack
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
    var homeBssid by remember(uiState.homeBssid) { mutableStateOf(uiState.homeBssid) }
    var homeLabel by remember(uiState.homeLabel) { mutableStateOf(uiState.homeLabel) }
    var officeBssid by remember(uiState.officeBssid) { mutableStateOf(uiState.officeBssid) }
    var officeLabel by remember(uiState.officeLabel) { mutableStateOf(uiState.officeLabel) }

    var scanResults by remember { mutableStateOf<List<ScanResult>>(emptyList()) }
    var showScanDialog by remember { mutableStateOf(false) }
    var scanTarget by remember { mutableStateOf("home") }
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
                if (scanTarget == "home") {
                    homeBssid = result.BSSID
                    if (homeLabel.isBlank()) homeLabel = result.SSID.ifBlank { result.BSSID }
                } else {
                    officeBssid = result.BSSID
                    if (officeLabel.isBlank()) officeLabel = result.SSID.ifBlank { result.BSSID }
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
        homeBssid = homeBssid,
        homeLabel = homeLabel,
        onHomeLabelChange = { homeLabel = it },
        officeBssid = officeBssid,
        officeLabel = officeLabel,
        onOfficeLabelChange = { officeLabel = it },
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
        onScanHome = {
            scanTarget = "home"
            requestScanOrLaunch(context, permissionLauncher) {
                scanResults = scanWifi(context)
                showScanDialog = true
            }
        },
        onScanOffice = {
            scanTarget = "office"
            requestScanOrLaunch(context, permissionLauncher) {
                scanResults = scanWifi(context)
                showScanDialog = true
            }
        },
        onSave = { guardian, hBssid, hLabel, oBssid, oLabel ->
            viewModel.saveSettings(guardian, hBssid, hLabel, oBssid, oLabel)
        },
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
    homeBssid: String,
    homeLabel: String,
    onHomeLabelChange: (String) -> Unit,
    officeBssid: String,
    officeLabel: String,
    onOfficeLabelChange: (String) -> Unit,
    isMonitoring: Boolean,
    onMonitoringToggle: (Boolean) -> Unit,
    onScanHome: () -> Unit,
    onScanOffice: () -> Unit,
    onSave: (String, String, String, String, String) -> Unit,
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
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
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
                "SMS will be sent to this number on arrival",
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
            SectionTitle("Home WiFi")
            BssidRow(
                bssid = homeBssid, label = homeLabel, onLabelChange = onHomeLabelChange,
                onScan = onScanHome,
            )

            Spacer(Modifier.height(20.dp))
            SectionTitle("Office WiFi")
            BssidRow(
                bssid = officeBssid, label = officeLabel, onLabelChange = onOfficeLabelChange,
                onScan = onScanOffice,
            )

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
                    onSave(normalized, homeBssid, homeLabel, officeBssid, officeLabel)
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
private fun BssidRow(
    bssid: String,
    label: String,
    onLabelChange: (String) -> Unit,
    onScan: () -> Unit,
) {
    OutlinedTextField(
        value = label, onValueChange = onLabelChange,
        label = { Text("Location label (e.g. My Home)") }, singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(6.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = bssid, onValueChange = {}, label = { Text("BSSID (MAC)") },
            readOnly = true, singleLine = true,
            placeholder = { Text("Tap Scan to select") }, modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = onScan) {
            Icon(Icons.Default.Wifi, contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("Scan")
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

@Preview(showBackground = true, name = "Settings — Loading")
@Composable
private fun SettingsLoadingPreview() {
    TextGateTheme {
        SettingsContent(
            uiState = SettingsUiState(isLoading = true),
            guardianNumber = "", onGuardianChange = {},
            homeBssid = "", homeLabel = "", onHomeLabelChange = {},
            officeBssid = "", officeLabel = "", onOfficeLabelChange = {},
            isMonitoring = false, onMonitoringToggle = {},
            onScanHome = {}, onScanOffice = {},
            onSave = { _, _, _, _, _ -> }, onBack = {},
        )
    }
}

@Preview(showBackground = true, name = "Settings — Form filled")
@Composable
private fun SettingsFilledPreview() {
    TextGateTheme {
        SettingsContent(
            uiState = SettingsUiState(
                guardianNumber = "03001234567", homeBssid = "AA:BB:CC:DD:EE:01",
                homeLabel = "My Home", officeBssid = "AA:BB:CC:DD:EE:02", officeLabel = "Office",
            ),
            guardianNumber = "03001234567", onGuardianChange = {},
            homeBssid = "AA:BB:CC:DD:EE:01", homeLabel = "My Home", onHomeLabelChange = {},
            officeBssid = "AA:BB:CC:DD:EE:02", officeLabel = "Office", onOfficeLabelChange = {},
            isMonitoring = true, onMonitoringToggle = {},
            onScanHome = {}, onScanOffice = {},
            onSave = { _, _, _, _, _ -> }, onBack = {},
        )
    }
}

@Preview(showBackground = true, name = "Settings — Monitoring off / empty")
@Composable
private fun SettingsEmptyPreview() {
    TextGateTheme {
        SettingsContent(
            uiState = SettingsUiState(),
            guardianNumber = "", onGuardianChange = {},
            homeBssid = "", homeLabel = "", onHomeLabelChange = {},
            officeBssid = "", officeLabel = "", onOfficeLabelChange = {},
            isMonitoring = false, onMonitoringToggle = {},
            onScanHome = {}, onScanOffice = {},
            onSave = { _, _, _, _, _ -> }, onBack = {},
        )
    }
}
