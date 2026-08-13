package com.spotwire.app.presentation.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.spotwire.app.core.theme.SpotwireTheme
import com.spotwire.app.core.utils.PhoneNormalizer
import com.spotwire.app.presentation.components.PhoneNumberField
import com.spotwire.app.presentation.components.rememberDefaultCountry
import org.koin.androidx.compose.koinViewModel

@Composable
fun SignupScreen(
    onSignupSuccess: () -> Unit,
    onSignupWithoutPhoneVerify: () -> Unit,
    onNavigateToLogin: () -> Unit,
    viewModel: AuthViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    LaunchedEffect(uiState.navigateToPhoneVerify) { if (uiState.navigateToPhoneVerify) onSignupSuccess() }
    LaunchedEffect(uiState.navigateWithoutPhoneVerify) {
        if (uiState.navigateWithoutPhoneVerify) onSignupWithoutPhoneVerify()
    }
    SignupContent(
        uiState = uiState,
        onSignup = viewModel::register,
        onNavigateToLogin = onNavigateToLogin,
    )
}

@Composable
private fun SignupContent(
    uiState: AuthUiState,
    onSignup: (String, String, String, String, String) -> Unit,
    onNavigateToLogin: () -> Unit,
) {
    val phoneNormalizer = remember { PhoneNormalizer() }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var confirmPassword by remember { mutableStateOf("") }
    var confirmVisible by remember { mutableStateOf(false) }
    var confirmError by remember { mutableStateOf<String?>(null) }
    var phone by remember { mutableStateOf("") }
    val defaultCountry = rememberDefaultCountry()
    var country by remember { mutableStateOf(defaultCountry) }
    val phoneValid = phoneNormalizer.isValid(phone, country)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Create Account", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(32.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Full Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
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
            onValueChange = { password = it; confirmError = null },
            label = { Text("Password (min 6 chars)") },
            singleLine = true,
            visualTransformation =
                if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        imageVector = if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (passwordVisible) "Hide password" else "Show password",
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = confirmPassword,
            onValueChange = { confirmPassword = it; confirmError = null },
            label = { Text("Confirm Password") },
            singleLine = true,
            visualTransformation =
                if (confirmVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = { confirmVisible = !confirmVisible }) {
                    Icon(
                        imageVector = if (confirmVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (confirmVisible) "Hide password" else "Show password",
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
            isError = confirmError != null,
            supportingText = confirmError?.let { { Text(it) } },
        )
        Spacer(Modifier.height(12.dp))
        PhoneNumberField(
            number = phone,
            onNumberChange = { phone = it },
            country = country,
            onCountryChange = { country = it },
            label = "Mobile Number",
            modifier = Modifier.fillMaxWidth(),
        )
        uiState.error?.let { err ->
            Spacer(Modifier.height(8.dp))
            Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (uiState.verificationSent) {
            Spacer(Modifier.height(8.dp))
            Text(
                "A 6-digit code was emailed to you — verify from Profile → Verify Email.",
                color = MaterialTheme.colorScheme.secondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(24.dp))
        // The mismatch is only checked on tap, never while typing: a confirm
        // field that turns red at the first character is just noise, since it
        // cannot match until the whole password has been retyped.
        Button(
            onClick = {
                if (password != confirmPassword) confirmError = "Passwords do not match"
                else onSignup(email, password, name, phone, country)
            },
            enabled = !uiState.isLoading && phoneValid,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            if (uiState.isLoading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Text("Create Account")
        }
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onNavigateToLogin) { Text("Already have an account? Login") }
    }
}

@Preview(showBackground = true, name = "Signup — Default")
@Composable
private fun SignupPreview() {
    SpotwireTheme { SignupContent(AuthUiState(), { _, _, _, _, _ -> }, {}) }
}

@Preview(showBackground = true, name = "Signup — Loading")
@Composable
private fun SignupLoadingPreview() {
    SpotwireTheme { SignupContent(AuthUiState(isLoading = true), { _, _, _, _, _ -> }, {}) }
}

@Preview(showBackground = true, name = "Signup — Error")
@Composable
private fun SignupErrorPreview() {
    SpotwireTheme { SignupContent(AuthUiState(error = "Email already in use"), { _, _, _, _, _ -> }, {}) }
}
