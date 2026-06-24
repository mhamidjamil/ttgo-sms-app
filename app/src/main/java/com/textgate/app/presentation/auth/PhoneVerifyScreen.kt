package com.textgate.app.presentation.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.textgate.app.BuildConfig
import com.textgate.app.core.theme.TextGateTheme
import org.koin.androidx.compose.koinViewModel

@Composable
fun PhoneVerifyScreen(
    onVerified: () -> Unit,
    onSkip: () -> Unit,
    viewModel: PhoneVerifyViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    LaunchedEffect(uiState.success) { if (uiState.success) onVerified() }
    PhoneVerifyContent(
        uiState = uiState,
        onVerify = viewModel::verify,
        onResend = viewModel::resend,
        onSkip = onSkip,
    )
}

@Composable
private fun PhoneVerifyContent(
    uiState: PhoneVerifyUiState,
    onVerify: (String) -> Unit,
    onResend: () -> Unit,
    onSkip: () -> Unit,
) {
    var code by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Verify Phone", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(8.dp))
        val displayPhone = uiState.phoneNumber.ifBlank { "your phone" }
        Text(
            "A 6-digit code was sent to $displayPhone via your SMS gateway.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Verifying unlocks ${BuildConfig.PARTIAL_VERIFIED_QUOTA}+ SMS/day (up to your full quota when email is also verified).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        OutlinedTextField(
            value = code,
            onValueChange = { if (it.length <= 6 && it.all { c -> c.isDigit() }) code = it },
            label = { Text("Verification Code") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("123456") },
        )
        uiState.error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (uiState.resendSuccess) {
            Spacer(Modifier.height(8.dp))
            Text("New code sent!", color = MaterialTheme.colorScheme.secondary,
                style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { onVerify(code) },
            enabled = code.length == 6 && !uiState.isLoading,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            if (uiState.isLoading) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary)
            } else {
                Text("Verify Phone")
            }
        }
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onResend, enabled = !uiState.isLoading) { Text("Resend Code") }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
            Text("Skip — Verify Later (${BuildConfig.UNVERIFIED_QUOTA} SMS/day until verified)")
        }
    }
}

@Preview(showBackground = true, name = "Phone Verify — Default")
@Composable
private fun PhoneVerifyPreview() {
    TextGateTheme {
        PhoneVerifyContent(
            uiState = PhoneVerifyUiState(phoneNumber = "+923001234567"),
            onVerify = {}, onResend = {}, onSkip = {},
        )
    }
}

@Preview(showBackground = true, name = "Phone Verify — Loading")
@Composable
private fun PhoneVerifyLoadingPreview() {
    TextGateTheme {
        PhoneVerifyContent(
            uiState = PhoneVerifyUiState(isLoading = true, phoneNumber = "+923001234567"),
            onVerify = {}, onResend = {}, onSkip = {},
        )
    }
}

@Preview(showBackground = true, name = "Phone Verify — Error")
@Composable
private fun PhoneVerifyErrorPreview() {
    TextGateTheme {
        PhoneVerifyContent(
            uiState = PhoneVerifyUiState(phoneNumber = "+923001234567", error = "Incorrect code. Try again."),
            onVerify = {}, onResend = {}, onSkip = {},
        )
    }
}
