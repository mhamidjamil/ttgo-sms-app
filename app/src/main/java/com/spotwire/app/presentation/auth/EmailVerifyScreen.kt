package com.spotwire.app.presentation.auth

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.spotwire.app.core.theme.SpotwireTheme
import org.koin.androidx.compose.koinViewModel

@Composable
fun EmailVerifyScreen(
    onVerified: () -> Unit,
    onSkip: () -> Unit,
    viewModel: EmailVerifyViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(uiState.verified) { if (uiState.verified) onVerified() }
    LaunchedEffect(uiState.toastMessage) {
        uiState.toastMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }
    EmailVerifyContent(
        uiState = uiState,
        onResend = viewModel::resend,
        onCheck = viewModel::check,
        onSkip = onSkip,
    )
}

@Composable
private fun EmailVerifyContent(
    uiState: EmailVerifyUiState,
    onResend: () -> Unit,
    onCheck: () -> Unit,
    onSkip: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Confirm your email", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            uiState.email.ifBlank { "your email address" },
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(20.dp))
        Text(
            "We sent you a link. Open it, then come back and tap the button below.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        // Said plainly and up front, because a mail that landed in spam looks
        // exactly like a mail that was never sent.
        Text(
            "If it is not in your inbox, check your spam or junk folder.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))

        Button(
            onClick = onCheck,
            enabled = !uiState.isChecking && !uiState.isSending,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            if (uiState.isChecking) {
                CircularProgressIndicator(
                    Modifier.size(20.dp), strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text("I have opened the link")
            }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = onResend,
            enabled = uiState.cooldownSeconds == 0 && !uiState.isSending && !uiState.isChecking,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (uiState.cooldownSeconds > 0) "Send it again in ${uiState.cooldownSeconds}s"
                else "Send it again"
            )
        }

        uiState.error?.let {
            Spacer(Modifier.height(12.dp))
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(20.dp))
        Text(
            "Your phone number is confirmed separately, at no extra step: it happens " +
                "by itself when you connect your WhatsApp gateway.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
            Text("Do this later")
        }
    }
}

@Preview(showBackground = true, name = "Email Verify")
@Composable
private fun EmailVerifyPreview() {
    SpotwireTheme {
        EmailVerifyContent(
            uiState = EmailVerifyUiState(email = "someone@example.com", sent = true, cooldownSeconds = 38),
            onResend = {}, onCheck = {}, onSkip = {},
        )
    }
}
