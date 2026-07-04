package com.textgate.app.data.whatsapp

import com.textgate.app.BuildConfig
import com.textgate.app.data.firebase.FirestoreDataSource

data class WaGatewayConfig(
    val serviceUrl: String,
    val ssoSecret: String,
)

/**
 * Resolves the WhatsApp gateway base URL + SSO secret with a remote-first
 * strategy: fields on the Firestore device doc (`wa_service_url`,
 * `wa_sso_secret`) override the compile-time BuildConfig values. That makes
 * both rotatable with a config edit only — change the Firestore field (and the
 * service .env) and every installed app picks it up within [CACHE_TTL_MS],
 * no rebuild. Falls back to BuildConfig when offline or the fields are unset.
 */
class WaConfigProvider(private val firestore: FirestoreDataSource) {

    @Volatile private var cached: WaGatewayConfig? = null
    @Volatile private var cachedAtMs: Long = 0L

    suspend fun get(): WaGatewayConfig {
        val now = System.currentTimeMillis()
        cached?.let { if (now - cachedAtMs < CACHE_TTL_MS) return it }

        val overrides = firestore.getWaGatewayOverrides().getOrNull()
        val config = WaGatewayConfig(
            serviceUrl = overrides?.first.orEmpty()
                .ifBlank { BuildConfig.WHATSAPP_SERVICE_URL }
                .trimEnd('/'),
            ssoSecret = overrides?.second.orEmpty()
                .ifBlank { BuildConfig.WHATSAPP_SSO_SECRET },
        )
        cached = config
        cachedAtMs = now
        return config
    }

    companion object {
        private const val CACHE_TTL_MS = 5L * 60L * 1000L
    }
}
