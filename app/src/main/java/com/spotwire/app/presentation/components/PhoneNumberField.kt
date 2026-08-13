package com.spotwire.app.presentation.components

import android.content.Context
import android.telephony.TelephonyManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.spotwire.app.core.utils.PhoneNormalizer
import java.util.Locale

/**
 * A phone number and the country it belongs to, which have to be entered
 * together: "0300 1234567" is only a number once you know where it was dialled.
 * Used by every screen that takes a number, so the rules are the same wherever
 * one is typed.
 */
@Composable
fun PhoneNumberField(
    number: String,
    onNumberChange: (String) -> Unit,
    country: String,
    onCountryChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    supportingText: String? = null,
) {
    val normalizer = remember { PhoneNormalizer() }
    var pickerOpen by remember { mutableStateOf(false) }
    val error = normalizer.validationError(number, country)
    val normalized = normalizer.normalize(number, country)

    Row(modifier = modifier, verticalAlignment = Alignment.Top) {
        OutlinedButton(
            onClick = { pickerOpen = true },
            enabled = enabled,
            modifier = Modifier.height(56.dp),
        ) {
            Text("${flagOf(country)} $country")
        }
        Spacer(Modifier.width(8.dp))
        OutlinedTextField(
            value = number,
            onValueChange = onNumberChange,
            label = { Text(label) },
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            isError = error != null,
            supportingText = {
                // One line, and it says the most useful thing available: what is
                // wrong, or what the number will actually be saved as, or
                // whatever the screen wanted to add.
                val line = error ?: normalized ?: supportingText
                if (line != null) Text(line)
            },
            modifier = Modifier.weight(1f),
        )
    }

    if (pickerOpen) {
        CountryPickerDialog(
            selected = country,
            onPick = { onCountryChange(it); pickerOpen = false },
            onDismiss = { pickerOpen = false },
        )
    }
}

@Composable
private fun CountryPickerDialog(
    selected: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var search by remember { mutableStateOf("") }
    val all = remember { allCountries() }
    val shown = remember(search) {
        if (search.isBlank()) all
        else all.filter { it.name.contains(search, ignoreCase = true) || it.iso.contains(search, ignoreCase = true) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Country") },
        text = {
            Column {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    label = { Text("Search") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(shown) { entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(entry.iso) }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("${flagOf(entry.iso)}  ${entry.name}", modifier = Modifier.weight(1f))
                            if (entry.iso == selected) Text("✓", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        },
    )
}

private data class Country(val iso: String, val name: String)

private fun allCountries(): List<Country> =
    Locale.getISOCountries()
        .map { Country(it, Locale.Builder().setRegion(it).build().getDisplayCountry(Locale.US)) }
        // A code the platform has no name for would show as its own two letters,
        // which tells nobody anything.
        .filter { it.name != it.iso }
        .sortedBy { it.name }

/** Two regional indicator letters, which every phone renders as that flag. */
private fun flagOf(iso: String): String {
    if (iso.length != 2) return ""
    val base = 0x1F1E6 - 'A'.code
    return String(Character.toChars(base + iso[0].uppercaseChar().code)) +
        String(Character.toChars(base + iso[1].uppercaseChar().code))
}

/**
 * Where this phone thinks it is: the SIM first because that is the number the
 * person is most likely typing, then the network, then the language they chose.
 */
fun defaultCountryIso(context: Context): String {
    val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
    val fromSim = telephony?.simCountryIso.orEmpty().ifBlank { telephony?.networkCountryIso.orEmpty() }
    return fromSim.ifBlank { Locale.getDefault().country }.uppercase(Locale.US).ifBlank { "PK" }
}

/** The country a field should start on when nothing is saved for the account yet. */
@Composable
fun rememberDefaultCountry(): String {
    val context = LocalContext.current
    return remember { defaultCountryIso(context) }
}
