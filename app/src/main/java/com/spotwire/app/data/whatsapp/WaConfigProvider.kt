package com.spotwire.app.data.whatsapp

import com.spotwire.app.BuildConfig
import com.spotwire.app.data.firebase.FirestoreDataSource

data class WaGatewayConfig(
    val serviceUrl: String,
    val portalUrl: String,
)

/**
 * Resolves the WhatsApp gateway addresses with a remote-first strategy: fields
 * on the Firestore device doc (`wa_service_url`, `wa_portal_url`) override the
 * compile-time BuildConfig values. That makes both changeable with a console
 * edit — every installed app picks it up within [CACHE_TTL_MS], no rebuild and
 * no release. Falls back to BuildConfig when offline or the fields are unset.
 *
 * Only public addresses resolve here. The gateway credential is per user and
 * lives on that user's own document, never on the shared device doc.
 */
class WaConfigProvider(private val firestore: FirestoreDataSource) {

    @Volatile private var cached: WaGatewayConfig? = null
    @Volatile private var cachedAtMs: Long = 0L

    suspend fun get(): WaGatewayConfig {
        val now = System.currentTimeMillis()
        cached?.let { if (now - cachedAtMs < CACHE_TTL_MS) return it }

        val overrides = firestore.getWaGatewayOverrides().getOrNull().orEmpty()
        val config = WaGatewayConfig(
            serviceUrl = overrides["wa_service_url"].orEmpty()
                .ifBlank { BuildConfig.WHATSAPP_SERVICE_URL }
                .trimEnd('/'),
            portalUrl = overrides["wa_portal_url"].orEmpty()
                .ifBlank { BuildConfig.WHATSAPP_PORTAL_URL }
                .trimEnd('/'),
        )
        cached = config
        cachedAtMs = now
        return config
    }

    companion object {
        private const val CACHE_TTL_MS = 5L * 60L * 1000L
    }
}
