package com.textgate.app.presentation.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.textgate.app.core.theme.TextGateTheme
import org.koin.androidx.compose.koinViewModel

@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    onNavigateToSignup: () -> Unit,
    viewModel: AuthViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    LaunchedEffect(uiState.success) { if (uiState.success) onLoginSuccess() }
    LoginContent(uiState = uiState, onLogin = viewModel::login, onNavigateToSignup = onNavigateToSignup)
}

@Composable
private fun LoginContent(
    uiState: AuthUiState,
    onLogin: (String, String) -> Unit,
    onNavigateToSignup: () -> Unit,
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("TextGate", style = MaterialTheme.typography.headlineLarge)
        Text(
            "SMS Gateway",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(40.dp))
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        uiState.error?.let { err ->
            Spacer(Modifier.height(8.dp))
            Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { onLogin(email, password) },
            enabled = !uiState.isLoading,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            if (uiState.isLoading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Text("Login")
        }
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onNavigateToSignup) { Text("Don't have an account? Sign Up") }
    }
}

@Preview(showBackground = true, name = "Login — Default")
@Composable
private fun LoginPreview() {
    TextGateTheme { LoginContent(AuthUiState(), { _, _ -> }, {}) }
}

@Preview(showBackground = true, name = "Login — Loading")
@Composable
private fun LoginLoadingPreview() {
    TextGateTheme { LoginContent(AuthUiState(isLoading = true), { _, _ -> }, {}) }
}

@Preview(showBackground = true, name = "Login — Error")
@Composable
private fun LoginErrorPreview() {
    TextGateTheme { LoginContent(AuthUiState(error = "Invalid email or password"), { _, _ -> }, {}) }
}
