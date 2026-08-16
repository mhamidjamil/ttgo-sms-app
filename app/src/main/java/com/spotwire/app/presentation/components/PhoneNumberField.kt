package com.spotwire.app.presentation.components

import android.content.Context
import android.telephony.TelephonyManager
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import com.spotwire.app.core.utils.PhoneNormalizer
import java.util.Locale

/**
 * A phone number field, and nothing else.
 *
 * There is no country picker: a local number like 03001234567 is read against
 * the country this phone is in, and anybody entering a number from somewhere
 * else writes it with its country code (+447700900123). That covers both cases
 * without making every person answer a question they already answered by
 * holding the phone they are holding.
 */
@Composable
fun PhoneNumberField(
    number: String,
    onNumberChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    supportingText: String? = null,
) {
    val normalizer = remember { PhoneNormalizer() }
    val country = rememberDefaultCountry()
    val error = normalizer.validationError(number, country)
    val normalized = normalizer.normalize(number, country)

    OutlinedTextField(
        value = number,
        onValueChange = onNumberChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        placeholder = { Text("03001234567 or +447700900123") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        isError = error != null,
        supportingText = {
            // One line, saying the most useful thing available: what is wrong,
            // or what the number will actually be saved as.
            val line = error ?: normalized ?: supportingText
            if (line != null) Text(line)
        },
        modifier = modifier,
    )
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

/** The country a typed local number is read against. */
@Composable
fun rememberDefaultCountry(): String {
    val context = LocalContext.current
    return remember { defaultCountryIso(context) }
}
