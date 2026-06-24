package com.textgate.app.core.utils

/**
 * Normalizes Pakistani mobile numbers to canonical E.164 format (+923XXXXXXXXX).
 * Only Pakistani mobile numbers (+92 country code, 03XX prefix) are accepted.
 *
 * Valid input forms:
 *   03XXXXXXXXX   — 11 digits, local trunk format (e.g. 03001234567)
 *   923XXXXXXXXX  — 12 digits, no leading +
 *   +923XXXXXXXXX — already E.164
 *
 * All other formats (international numbers, short codes, landlines) return null.
 * Mirrors TTGO device-side normalization so Firestore doc IDs match what the device looks up.
 */
class PhoneNormalizer {

    // Pakistani mobile E.164: +92 followed by 3 then 9 more digits (03XX subscriber format)
    private val pakMobileRegex = Regex("""\+923\d{9}""")

    fun normalize(raw: String): String? {
        val digits = raw.filter { it.isDigit() }
        val e164 = when {
            // 03XXXXXXXXX — 11 digits, trunk-0 format
            digits.length == 11 && digits.startsWith("0") ->
                "+92${digits.substring(1)}"
            // 923XXXXXXXXX — 12 digits, no +
            digits.length == 12 && digits.startsWith("92") ->
                "+$digits"
            // +923XXXXXXXXX — already E.164
            raw.startsWith("+") && digits.length == 12 && digits.startsWith("92") ->
                "+$digits"
            else -> null
        }
        return e164?.takeIf { pakMobileRegex.matches(it) }
    }

    fun isValid(raw: String): Boolean = normalize(raw) != null

    fun validationError(raw: String): String? {
        if (raw.isBlank()) return null
        return if (!isValid(raw)) "Enter a valid Pakistani number (e.g. 03001234567)" else null
    }
}
