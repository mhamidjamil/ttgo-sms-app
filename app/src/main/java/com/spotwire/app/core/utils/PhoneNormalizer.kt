package com.spotwire.app.core.utils

import android.telephony.PhoneNumberUtils
import java.util.Locale

/**
 * Turns a typed number into canonical E.164 (+923001234567) for the country it
 * was typed in, so "0300 1234567" picked as Pakistan and "07700 900123" picked
 * as the United Kingdom both resolve. A number already carrying its country code
 * resolves whatever country is passed, which is why stored numbers can be
 * re-normalized with no country at hand.
 *
 * The heavy lifting is the platform's own libphonenumber copy, so the per-country
 * rules stay correct without shipping a second copy of that table.
 *
 * SMS is a separate question: it goes through the one TTGO device, whose SIM is
 * Pakistani, so only a Pakistani mobile can ever be texted. That is what
 * isPakistaniMobile answers, and it is a fact about the hardware rather than a
 * rule about who may register.
 */
class PhoneNormalizer {

    private val pakistaniMobile = Regex("""\+923\d{9}""")

    fun normalize(raw: String, countryIso: String = ""): String? {
        if (raw.isBlank()) return null
        val region = countryIso.uppercase(Locale.US).ifBlank { UNKNOWN_REGION }
        return PhoneNumberUtils.formatNumberToE164(raw.trim(), region)
    }

    fun isValid(raw: String, countryIso: String = ""): Boolean = normalize(raw, countryIso) != null

    fun validationError(raw: String, countryIso: String = ""): String? {
        if (raw.isBlank()) return null
        return if (isValid(raw, countryIso)) null
        else "Enter a valid mobile number for the country selected"
    }

    fun isPakistaniMobile(e164: String): Boolean = pakistaniMobile.matches(e164)

    private companion object {
        // libphonenumber's own code for "no country to fall back on", which is
        // all a number written with its country code needs.
        const val UNKNOWN_REGION = "ZZ"
    }
}
