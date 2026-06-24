package com.textgate.app.presentation.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.textgate.app.core.utils.PhoneNormalizer
import com.textgate.app.services.ArrivalService
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
    val phoneNormalizer = remember { PhoneNormalizer() }

    var guardianNumber by remember(uiState.guardianNumber) { mutableStateOf(uiState.guardianNumber) }
    var homeBssid by remember(uiState.homeBssid) { mutableStateOf(uiState.homeBssid) }
    var homeLabel by remember(uiState.homeLabel) { mutableStateOf(uiState.homeLabel) }
    var officeBssid by remember(uiState.officeBssid) { mutableStateOf(uiState.officeBssid) }
    var officeLabel by remember(uiState.officeLabel) { mutableStateOf(uiState.officeLabel) }

    var scanResults by remember { mutableStateOf<List<ScanResult>>(emptyList()) }
    var showScanDialog by remember { mutableStateOf(false) }
    var scanTarget by remember { mutableStateOf("home") } // "home" or "office"
    var isMonitoring by remember { mutableStateOf(ArrivalService.isRunning) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            scanResults = scanWifi(context)
            showScanDialog = true
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

    Scaffold(
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
            Box(Modifier.fillMaxSize().padding(padding)) {
                CircularProgressIndicator(Modifier.align(androidx.compose.ui.Alignment.Center))
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            // Guardian number
            SectionTitle("Guardian Contact")
            Text(
                "SMS will be sent to this number on arrival",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(8.dp))
            val guardianError = phoneNormalizer.validationError(guardianNumber)
            OutlinedTextField(
                value = guardianNumber,
                onValueChange = { guardianNumber = it },
                label = { Text("Guardian Phone (Pakistani, e.g. 03001234567)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth(),
                isError = guardianError != null,
                supportingText = guardianError?.let { { Text(it) } },
            )

            Spacer(Modifier.height(20.dp))

            // Home WiFi
            SectionTitle("Home WiFi")
            BssidRow(
                bssid = homeBssid,
                label = homeLabel,
                onLabelChange = { homeLabel = it },
                onScan = {
                    scanTarget = "home"
                    requestScanOrLaunch(context, permissionLauncher) {
                        scanResults = scanWifi(context)
                        showScanDialog = true
                    }
                },
            )

            Spacer(Modifier.height(20.dp))

            // Office WiFi
            SectionTitle("Office WiFi")
            BssidRow(
                bssid = officeBssid,
                label = officeLabel,
                onLabelChange = { officeLabel = it },
                onScan = {
                    scanTarget = "office"
                    requestScanOrLaunch(context, permissionLauncher) {
                        scanResults = scanWifi(context)
                        showScanDialog = true
                    }
                },
            )

            Spacer(Modifier.height(20.dp))

            // Monitoring toggle
            SectionTitle("Monitoring")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
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
                Switch(
                    checked = isMonitoring,
                    onCheckedChange = { enabled ->
                        isMonitoring = enabled
                        if (enabled) ArrivalService.start(context) else ArrivalService.stop(context)
                    },
                )
            }

            uiState.error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(28.dp))

            val canSave = guardianError == null && !uiState.isSaving
            Button(
                onClick = {
                    val normalized = phoneNormalizer.normalize(guardianNumber) ?: guardianNumber
                    viewModel.saveSettings(normalized, homeBssid, homeLabel, officeBssid, officeLabel)
                },
                enabled = canSave,
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
    Text(text, style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary)
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
        value = label,
        onValueChange = onLabelChange,
        label = { Text("Location label (e.g. My Home)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(6.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = bssid,
            onValueChange = {},
            label = { Text("BSSID (MAC)") },
            readOnly = true,
            singleLine = true,
            placeholder = { Text("Tap Scan to select") },
            modifier = Modifier.weight(1f),
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
                        TextButton(
                            onClick = { onSelect(result) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(result.SSID.ifBlank { "(Hidden network)" },
                                    style = MaterialTheme.typography.bodyMedium)
                                Text(result.BSSID,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
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
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        == PackageManager.PERMISSION_GRANTED
    ) {
        onGranted()
    } else {
        launcher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}
