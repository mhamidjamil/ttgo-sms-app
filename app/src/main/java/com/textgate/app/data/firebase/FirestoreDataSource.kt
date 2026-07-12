package com.textgate.app.data.firebase

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.snapshots
import com.textgate.app.core.utils.DateUtils
import com.textgate.app.core.utils.Paths
import com.textgate.app.domain.model.Place
import com.textgate.app.domain.model.LinkPermissions
import com.textgate.app.domain.model.LinkState
import com.textgate.app.domain.model.LocationRequest
import com.textgate.app.domain.model.SettingsChange
import com.textgate.app.data.model.AccountLinkDto
import com.textgate.app.data.model.AlertSubscriptionDto
import com.textgate.app.data.model.AutoHistoryEntryDto
import com.textgate.app.data.model.HistoryEntryDto
import com.textgate.app.data.model.LocationRequestDto
import com.textgate.app.data.model.SettingsChangeDto
import com.textgate.app.data.model.SmsJobDto
import com.textgate.app.data.model.UserDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

class FirestoreDataSource(private val db: FirebaseFirestore) {

    // ── User ─────────────────────────────────────────────────────────────────

    // Writes the document only when it does not exist yet. This used to be a
    // plain set(), so every auto-heal run against an existing account replaced
    // the whole document and wiped guardian_number, places, and the phone/email
    // verification flags. The existence check and the write have to be in one
    // transaction, or two devices signing in at once can still clobber it.
    suspend fun createUser(
        uid: String,
        email: String,
        name: String,
        quota: Int,
    ): Result<Unit> = runCatching {
        val dto = mapOf(
            "email" to email,
            "name" to name,
            "email_verified" to false,
            "phone_number" to "",
            "phone_verified" to false,
            "assigned_quota" to quota,
            "remaining_quota" to quota,
            "last_quota_reset_date" to DateUtils.todayString(),
            "created_at" to Timestamp.now(),
        )
        val ref = db.collection(Paths.USERS).document(uid)
        db.runTransaction { tx ->
            if (!tx.get(ref).exists()) tx.set(ref, dto)
        }.await()
    }

    // Success + null means the read worked and the document genuinely is not
    // there. A read that FAILED comes back as a failed Result — callers must not
    // collapse the two, because "missing" triggers a create and "failed" must not.
    suspend fun getUser(uid: String): Result<UserDto?> = runCatching {
        val snap = db.collection(Paths.USERS).document(uid).get().await()
        if (snap.exists()) snap.toObject(UserDto::class.java)?.copy(uid = snap.id) else null
    }

    suspend fun updateQuotaReset(uid: String, remaining: Int, date: String): Result<Unit> = runCatching {
        db.collection(Paths.USERS).document(uid).update(
            mapOf("remaining_quota" to remaining, "last_quota_reset_date" to date)
        ).await()
    }

    suspend fun decrementRemainingQuota(uid: String): Result<Unit> = runCatching {
        val ref = db.collection(Paths.USERS).document(uid)
        db.runTransaction { tx ->
            val snap = tx.get(ref)
            val current = snap.getLong("remaining_quota")?.toInt() ?: 0
            if (current > 0) tx.update(ref, "remaining_quota", current - 1)
        }.await()
    }

    suspend fun updateName(uid: String, name: String): Result<Unit> = runCatching {
        db.collection(Paths.USERS).document(uid).update("name", name).await()
    }

    suspend fun syncEmailVerified(uid: String, verified: Boolean): Result<Unit> = runCatching {
        db.collection(Paths.USERS).document(uid)
            .update("email_verified", verified).await()
    }

    suspend fun getDeviceFreeSmsQuota(): Result<Int> = runCatching {
        val snap = db.document(Paths.DEVICE_DOC).get().await()
        snap.getLong(Paths.FREE_SMS_QUOTA_FIELD)?.toInt() ?: 10
    }

    // WhatsApp gateway overrides on the device doc. Any field may be null/blank —
    // the caller falls back to BuildConfig. Kept in Firestore so the gateway URL
    // and the portal address change with a console edit, not an app rebuild.
    suspend fun getWaGatewayOverrides(): Result<Map<String, String>> = runCatching {
        val snap = db.document(Paths.DEVICE_DOC).get().await()
        listOf("wa_service_url", "wa_portal_url")
            .mapNotNull { field -> snap.getString(field)?.takeIf { it.isNotBlank() }?.let { field to it } }
            .toMap()
    }

    // ── Phone verification (V1.5) ─────────────────────────────────────────────

    suspend fun savePhoneNumber(uid: String, phoneNumber: String): Result<Unit> = runCatching {
        db.collection(Paths.USERS).document(uid)
            .update("phone_number", phoneNumber, "phone_verified", false).await()
    }

    suspend fun savePhoneOtp(uid: String, otp: String): Result<Unit> = runCatching {
        db.collection(Paths.USERS).document(uid)
            .update("phone_otp", otp, "phone_otp_created_at", Timestamp.now()).await()
    }

    // Returns code + creation time (millis) so the caller can enforce expiry.
    suspend fun getPhoneOtp(uid: String): Result<Pair<String, Long>?> = runCatching {
        val snap = db.collection(Paths.USERS).document(uid).get().await()
        val code = snap.getString("phone_otp") ?: return@runCatching null
        val createdAt = snap.getTimestamp("phone_otp_created_at")?.toDate()?.time ?: 0L
        code to createdAt
    }

    suspend fun markPhoneVerified(uid: String): Result<Unit> = runCatching {
        db.collection(Paths.USERS).document(uid).update(
            mapOf(
                "phone_verified" to true,
                "phone_otp" to FieldValue.delete(),
                "phone_otp_created_at" to FieldValue.delete(),
            )
        ).await()
    }

    // ── Email verification (OTP over SMTP — mirrors the phone_otp fields) ────

    suspend fun saveEmailOtp(uid: String, otp: String): Result<Unit> = runCatching {
        db.collection(Paths.USERS).document(uid)
            .update("email_otp", otp, "email_otp_created_at", Timestamp.now()).await()
    }

    suspend fun getEmailOtp(uid: String): Result<Pair<String, Long>?> = runCatching {
        val snap = db.collection(Paths.USERS).document(uid).get().await()
        val code = snap.getString("email_otp") ?: return@runCatching null
        val createdAt = snap.getTimestamp("email_otp_created_at")?.toDate()?.time ?: 0L
        code to createdAt
    }

    suspend fun markEmailVerified(uid: String): Result<Unit> = runCatching {
        db.collection(Paths.USERS).document(uid).update(
            mapOf(
                "email_verified" to true,
                "email_otp" to FieldValue.delete(),
                "email_otp_created_at" to FieldValue.delete(),
            )
        ).await()
    }

    // Enqueues an OTP verification SMS directly — no history entry, no quota change.
    // kind:"otp" makes the TTGO device process it BEFORE regular jobs, send it
    // immediately (no anti-ban gap), and bypass the SIM-package-expired gate.
    suspend fun enqueueOtpSms(phoneNumber: String, message: String, enqueBy: String): Result<Unit> = runCatching {
        val jobDto = mapOf(
            "phone_number" to phoneNumber,
            "message" to message,
            "status" to "pending",
            "enque_by" to enqueBy,
            "kind" to "otp",
            "created_at" to Timestamp.now(),
        )
        db.collection(Paths.SMS_JOBS).document().set(jobDto).await()
    }

    // ── WhatsApp gateway link (SSO) ───────────────────────────────────────────

    suspend fun saveWaLink(uid: String, apiKey: String, sessionId: String): Result<Unit> = runCatching {
        db.collection(Paths.USERS).document(uid)
            .update("wa_api_key", apiKey, "wa_session_id", sessionId).await()
    }

    suspend fun saveWaMode(uid: String, mode: String): Result<Unit> = runCatching {
        db.collection(Paths.USERS).document(uid).update("wa_mode", mode).await()
    }

    // The gateway key the user minted on the portal themselves. Owner-only via
    // security rules, so it follows them to a new phone without being re-typed.
    suspend fun saveWaOwnKey(
        uid: String,
        keyId: String,
        keySecret: String,
        sessionId: String,
    ): Result<Unit> = runCatching {
        db.collection(Paths.USERS).document(uid)
            .update("wa_key_id", keyId, "wa_key_secret", keySecret, "wa_session_id", sessionId)
            .await()
    }

    // ── Location settings (V2) ────────────────────────────────────────────────

    private fun placeMap(place: Place) = mapOf(
        "id" to place.id, "label" to place.label, "bssid" to place.bssid,
        "message" to place.message,
        "wa_message" to place.waMessage,
        "contacts" to place.contacts.map {
            mapOf("name" to it.name, "number" to it.number)
        },
        "bssids" to place.bssids,
        "min_rssi" to place.minRssi,
        "alerts_enabled" to place.alertsEnabled,
        "sensitivity" to place.sensitivity,
        "dwell_minutes" to place.dwellMinutesOverride,
        "quiet_from" to place.quietFrom,
        "quiet_to" to place.quietTo,
    )

    suspend fun savePlacesSettings(
        uid: String,
        guardianNumber: String,
        places: List<Place>,
    ): Result<Unit> = runCatching {
        db.collection(Paths.USERS).document(uid).set(
            mapOf(
                "guardian_number" to guardianNumber,
                "places" to places.map(::placeMap),
            ),
            SetOptions.merge(),
        ).await()
    }

    // Places-only write, used when the place editor or the WiFi picker commits a
    // single place. It deliberately leaves guardian_number alone: the guardian
    // field is still being edited on screen and writing it here would save a
    // half-typed number over the good one.
    suspend fun savePlaces(uid: String, places: List<Place>): Result<Unit> = runCatching {
        db.collection(Paths.USERS).document(uid)
            .set(mapOf("places" to places.map(::placeMap)), SetOptions.merge()).await()
    }

    // ── Settings change history ───────────────────────────────────────────────

    // Best-effort audit trail. A failure here must never fail the settings save
    // that produced it, so the caller ignores the result.
    suspend fun logSettingsChanges(uid: String, changes: List<SettingsChange>): Result<Unit> = runCatching {
        if (changes.isEmpty()) return@runCatching
        val now = Timestamp.now()
        val col = db.collection(Paths.USERS).document(uid)
            .collection(Paths.SETTINGS_HISTORY_SUB)
        val batch = db.batch()
        changes.forEach { change ->
            batch.set(col.document(), mapOf(
                "field" to change.field,
                "old_value" to change.oldValue,
                "new_value" to change.newValue,
                "changed_at" to now,
            ))
        }
        batch.commit().await()
    }

    fun getSettingsHistory(uid: String): Flow<List<SettingsChangeDto>> =
        db.collection(Paths.USERS).document(uid)
            .collection(Paths.SETTINGS_HISTORY_SUB)
            .orderBy("changed_at", Query.Direction.DESCENDING)
            .limit(200)
            .snapshots()
            .map { snap ->
                snap.documents.mapNotNull { doc ->
                    doc.toObject(SettingsChangeDto::class.java)?.copy(id = doc.id)
                }
            }

    suspend fun recordArrival(
        uid: String,
        placeId: String,
        date: String,
        currentTime: String,
    ): Result<Unit> = runCatching {
        val ref = db.collection(Paths.USERS).document(uid)
        db.runTransaction { tx ->
            val snap = tx.get(ref)
            @Suppress("UNCHECKED_CAST")
            val allTimes = (snap.get("arrival_times") as? Map<String, List<String>>) ?: emptyMap()
            val updated = ((allTimes[placeId] ?: emptyList()) + currentTime).takeLast(30)
            tx.update(ref, mapOf(
                "last_arrival_dates.$placeId" to date,
                "arrival_times.$placeId" to updated,
            ))
        }.await()
    }

    // ── Auto arrival SMS (V2) ─────────────────────────────────────────────────

    suspend fun enqueueAutoArrivalSms(
        uid: String,
        phoneNumber: String,
        recipientName: String,
        message: String,
        location: String,
        locationLabel: String,
        routineTriggered: Boolean,
    ): Result<Unit> = runCatching {
        val enqueBy = "app:$uid:arrival"
        val now = Timestamp.now()
        val jobRef = db.collection(Paths.SMS_JOBS).document()
        val jobDto = mapOf(
            "phone_number" to phoneNumber,
            "message" to message,
            "status" to "pending",
            "enque_by" to enqueBy,
            "created_at" to now,
        )
        val autoDto = mapOf(
            "location" to location,
            "location_label" to locationLabel,
            "channel" to "sms",
            "enque_by" to enqueBy,
            "sent_at" to now,
            "status" to "pending",
            "job_phone_key" to phoneNumber,
            "job_id" to jobRef.id,
            "recipient_name" to recipientName,
            "message" to message,
            "routine_triggered" to routineTriggered,
        )
        val batch = db.batch()
        batch.set(jobRef, jobDto)
        batch.set(
            db.collection(Paths.USERS).document(uid)
                .collection(Paths.AUTO_HISTORY_SUB).document(),
            autoDto,
        )
        batch.commit().await()
    }

    // WhatsApp deliveries don't create a gateway job — record the arrival in
    // auto_history directly so the Auto page shows them too. 202-queued has no
    // delivery receipt, so "sent" is the terminal status.
    suspend fun logAutoWhatsAppArrival(
        uid: String,
        phoneNumber: String,
        recipientName: String,
        message: String,
        location: String,
        locationLabel: String,
        routineTriggered: Boolean,
    ): Result<Unit> = logAutoArrival(
        uid, phoneNumber, recipientName, message, location, locationLabel,
        routineTriggered, channel = "whatsapp", status = "sent", error = "",
    )

    // A recipient the gateway could not be queued for at all. Without this row
    // that person simply disappears: the alert is not sent, nothing shows on the
    // Auto page, and there is nothing to press retry on.
    suspend fun logAutoArrivalFailure(
        uid: String,
        phoneNumber: String,
        recipientName: String,
        message: String,
        location: String,
        locationLabel: String,
        routineTriggered: Boolean,
        error: String,
    ): Result<Unit> = logAutoArrival(
        uid, phoneNumber, recipientName, message, location, locationLabel,
        routineTriggered, channel = "sms", status = "failed", error = error,
    )

    private suspend fun logAutoArrival(
        uid: String,
        phoneNumber: String,
        recipientName: String,
        message: String,
        location: String,
        locationLabel: String,
        routineTriggered: Boolean,
        channel: String,
        status: String,
        error: String,
    ): Result<Unit> = runCatching {
        val autoDto = mapOf(
            "location" to location,
            "location_label" to locationLabel,
            "channel" to channel,
            "enque_by" to "app:$uid:arrival",
            "sent_at" to Timestamp.now(),
            "status" to status,
            "job_phone_key" to phoneNumber,
            "recipient_name" to recipientName,
            "message" to message,
            "routine_triggered" to routineTriggered,
            "error" to error,
        )
        db.collection(Paths.USERS).document(uid)
            .collection(Paths.AUTO_HISTORY_SUB).document().set(autoDto).await()
    }

    // Re-queues an arrival alert that failed. The retry gets its own gateway job
    // doc and the existing auto_history row goes back to pending rather than
    // adding a duplicate, with job_id repointed at the new job so the status
    // refresh follows the retry and not the abandoned original.
    // sent_at is deliberately left alone so the entry keeps its place in the list.
    suspend fun retryAutoArrivalSms(
        uid: String,
        entryId: String,
        phoneNumber: String,
        message: String,
    ): Result<Unit> = runCatching {
        val enqueBy = "app:$uid:arrival"
        val jobRef = db.collection(Paths.SMS_JOBS).document()
        val jobDto = mapOf(
            "phone_number" to phoneNumber,
            "message" to message,
            "status" to "pending",
            "enque_by" to enqueBy,
            "created_at" to Timestamp.now(),
        )
        val batch = db.batch()
        batch.set(jobRef, jobDto)
        batch.update(
            db.collection(Paths.USERS).document(uid)
                .collection(Paths.AUTO_HISTORY_SUB).document(entryId),
            mapOf("status" to "pending", "enque_by" to enqueBy, "job_id" to jobRef.id),
        )
        batch.commit().await()
    }

    suspend fun updateAutoHistoryStatus(
        uid: String,
        entryId: String,
        status: String,
    ): Result<Unit> = runCatching {
        db.collection(Paths.USERS).document(uid)
            .collection(Paths.AUTO_HISTORY_SUB).document(entryId)
            .update("status", status).await()
    }

    fun getAutoHistory(uid: String): Flow<List<AutoHistoryEntryDto>> =
        db.collection(Paths.USERS).document(uid)
            .collection(Paths.AUTO_HISTORY_SUB)
            .orderBy("sent_at", Query.Direction.DESCENDING)
            .snapshots()
            .map { snap ->
                snap.documents.mapNotNull { doc ->
                    doc.toObject(AutoHistoryEntryDto::class.java)?.copy(id = doc.id)
                }
            }

    // ── SMS Jobs + History ────────────────────────────────────────────────────

    suspend fun enqueueJob(
        uid: String,
        phoneNumber: String,
        message: String,
    ): Result<String> = runCatching {
        val enqueBy = "app:$uid"
        val now = Timestamp.now()
        val jobDto = SmsJobDto.from(phoneNumber, message, enqueBy, now)
        val jobRef = db.collection(Paths.SMS_JOBS).document()
        val historyDto = mapOf(
            "phone_number" to phoneNumber,
            "message" to message,
            "status" to "pending",
            "enqueued_at" to now,
            "job_phone_key" to phoneNumber,
            "job_id" to jobRef.id,
            "enque_by" to enqueBy,
        )
        val batch = db.batch()
        val historyRef = db.collection(Paths.USERS).document(uid)
            .collection(Paths.HISTORY_SUB).document()
        batch.set(jobRef, jobDto)
        batch.set(historyRef, historyDto)
        batch.commit().await()
        historyRef.id
    }

    fun getHistory(uid: String): Flow<List<HistoryEntryDto>> =
        db.collection(Paths.USERS).document(uid)
            .collection(Paths.HISTORY_SUB)
            .orderBy("enqueued_at", Query.Direction.DESCENDING)
            .snapshots()
            .map { snap ->
                snap.documents.mapNotNull { doc ->
                    doc.toObject(HistoryEntryDto::class.java)?.copy(id = doc.id)
                }
            }

    // Takes the job document id. Entries written before jobs got their own ids
    // pass the phone number instead, which is exactly what those older documents
    // are still keyed by, so both resolve through the same read.
    suspend fun fetchJobStatus(jobId: String): Result<SmsJobDto> = runCatching {
        val snap = db.collection(Paths.SMS_JOBS).document(jobId).get().await()
        snap.toObject(SmsJobDto::class.java) ?: error("Job not found for $jobId")
    }

    suspend fun updateHistoryStatus(
        uid: String,
        historyId: String,
        status: String,
    ): Result<Unit> = runCatching {
        db.collection(Paths.USERS).document(uid)
            .collection(Paths.HISTORY_SUB).document(historyId)
            .update("status", status).await()
    }

    // Enqueues a message the app generated on the user's behalf (an unsubscribe
    // notice, a location answer). No history entry and no quota change, because
    // the user did not compose it.
    suspend fun enqueueSystemNotice(
        phoneNumber: String,
        message: String,
        enqueBy: String,
    ): Result<Unit> = runCatching {
        db.collection(Paths.SMS_JOBS).document().set(
            mapOf(
                "phone_number" to phoneNumber,
                "message" to message,
                "status" to "pending",
                "enque_by" to enqueBy,
                "created_at" to Timestamp.now(),
            )
        ).await()
    }

    // ── Phone directory ───────────────────────────────────────────────────────

    // A tiny public lookup table so an account can be found by its verified
    // number when someone sends a link invite. It carries only uid and name, so
    // the user documents themselves stay owner-only.
    suspend fun publishPhoneDirectoryEntry(
        phoneNumber: String,
        uid: String,
        name: String,
    ): Result<Unit> = runCatching {
        db.collection(Paths.PHONE_DIRECTORY).document(phoneNumber)
            .set(mapOf("uid" to uid, "name" to name), SetOptions.merge()).await()
    }

    // Returns (uid, name) for a verified number, or null when nobody has claimed it.
    suspend fun lookupPhoneDirectory(phoneNumber: String): Result<Pair<String, String>?> = runCatching {
        val snap = db.collection(Paths.PHONE_DIRECTORY).document(phoneNumber).get().await()
        val uid = snap.getString("uid") ?: return@runCatching null
        uid to (snap.getString("name") ?: "")
    }

    // ── Recipient alert subscriptions ─────────────────────────────────────────

    private fun subscriptionRef(recipientPhone: String, senderUid: String) =
        db.collection(Paths.ALERT_SUBSCRIPTIONS).document(recipientPhone)
            .collection(Paths.SENDERS_SUB).document(senderUid)

    // Written on every arrival alert so the recipient can discover the link
    // whenever they install the app. merge() never overwrites their own choice
    // because `subscribed` is deliberately absent from this write.
    suspend fun recordAlertSubscription(
        recipientPhone: String,
        senderUid: String,
        senderName: String,
        senderPhone: String,
    ): Result<Unit> = runCatching {
        subscriptionRef(recipientPhone, senderUid).set(
            mapOf(
                "sender_name" to senderName,
                "sender_phone" to senderPhone,
                "last_alert_at" to Timestamp.now(),
            ),
            SetOptions.merge(),
        ).await()
    }

    // Absent document or absent field means the recipient never opted out, which
    // is allowed. Only an explicit false blocks delivery.
    suspend fun isAlertAllowed(recipientPhone: String, senderUid: String): Result<Boolean> = runCatching {
        subscriptionRef(recipientPhone, senderUid).get().await().getBoolean("subscribed") ?: true
    }

    suspend fun setAlertSubscribed(
        recipientPhone: String,
        senderUid: String,
        subscribed: Boolean,
    ): Result<Unit> = runCatching {
        subscriptionRef(recipientPhone, senderUid)
            .set(mapOf("subscribed" to subscribed), SetOptions.merge()).await()
    }

    fun getAlertSubscriptions(recipientPhone: String): Flow<List<AlertSubscriptionDto>> =
        db.collection(Paths.ALERT_SUBSCRIPTIONS).document(recipientPhone)
            .collection(Paths.SENDERS_SUB)
            .snapshots()
            .map { snap ->
                snap.documents.mapNotNull { doc ->
                    doc.toObject(AlertSubscriptionDto::class.java)?.copy(senderUid = doc.id)
                }
            }

    // ── Linked accounts ───────────────────────────────────────────────────────

    private fun linkRef(ownerUid: String, otherUid: String) =
        db.collection(Paths.USERS).document(ownerUid)
            .collection(Paths.LINKS_SUB).document(otherUid)

    // Both sides of a pairing are written at once so neither can be left with a
    // dangling invite. My side records what I grant them; their side starts with
    // no permissions until they accept and choose their own.
    suspend fun createLinkInvite(
        myUid: String,
        myName: String,
        myPhone: String,
        otherUid: String,
        otherName: String,
        otherPhone: String,
        permissions: LinkPermissions,
    ): Result<Unit> = runCatching {
        val batch = db.batch()
        batch.set(linkRef(myUid, otherUid), mapOf(
            "other_name" to otherName,
            "other_phone" to otherPhone,
            "state" to LinkState.PENDING_OUTGOING.firestoreValue,
            "perm_auto_updates" to permissions.autoLocationUpdates,
            "perm_request_location" to permissions.requestLocation,
        ), SetOptions.merge())
        batch.set(linkRef(otherUid, myUid), mapOf(
            "other_name" to myName,
            "other_phone" to myPhone,
            "state" to LinkState.PENDING_INCOMING.firestoreValue,
        ), SetOptions.merge())
        batch.commit().await()
    }

    // Accepting flips both sides to active. Declining marks only my own side, so
    // the inviter keeps a record of the outcome on theirs.
    suspend fun setLinkState(
        myUid: String,
        otherUid: String,
        state: LinkState,
        alsoUpdateOtherSide: Boolean,
    ): Result<Unit> = runCatching {
        val batch = db.batch()
        batch.set(linkRef(myUid, otherUid), mapOf("state" to state.firestoreValue), SetOptions.merge())
        if (alsoUpdateOtherSide) {
            batch.set(linkRef(otherUid, myUid), mapOf("state" to state.firestoreValue), SetOptions.merge())
        }
        batch.commit().await()
    }

    suspend fun setLinkPermissions(
        myUid: String,
        otherUid: String,
        permissions: LinkPermissions,
    ): Result<Unit> = runCatching {
        linkRef(myUid, otherUid).set(
            mapOf(
                "perm_auto_updates" to permissions.autoLocationUpdates,
                "perm_request_location" to permissions.requestLocation,
            ),
            SetOptions.merge(),
        ).await()
    }

    // Removing takes out both sides; historical alerts stay untouched.
    suspend fun deleteLink(myUid: String, otherUid: String): Result<Unit> = runCatching {
        val batch = db.batch()
        batch.delete(linkRef(myUid, otherUid))
        batch.delete(linkRef(otherUid, myUid))
        batch.commit().await()
    }

    fun getLinks(uid: String): Flow<List<AccountLinkDto>> =
        db.collection(Paths.USERS).document(uid).collection(Paths.LINKS_SUB)
            .snapshots()
            .map { snap ->
                snap.documents.mapNotNull { doc ->
                    doc.toObject(AccountLinkDto::class.java)?.copy(otherUid = doc.id)
                }
            }

    suspend fun getLinksOnce(uid: String): Result<List<AccountLinkDto>> = runCatching {
        db.collection(Paths.USERS).document(uid).collection(Paths.LINKS_SUB)
            .get().await().documents.mapNotNull { doc ->
                doc.toObject(AccountLinkDto::class.java)?.copy(otherUid = doc.id)
            }
    }

    // ── On-demand location requests ───────────────────────────────────────────

    private fun locationRequests(targetUid: String) =
        db.collection(Paths.USERS).document(targetUid).collection(Paths.LOCATION_REQUESTS_SUB)

    // Returns the new request id so the asker can watch that one document for
    // the answer instead of polling the whole collection.
    suspend fun createLocationRequest(
        targetUid: String,
        requesterUid: String,
        requesterName: String,
    ): Result<String> = runCatching {
        val ref = locationRequests(targetUid).document()
        ref.set(mapOf(
            "requester_uid" to requesterUid,
            "requester_name" to requesterName,
            "status" to LocationRequest.PENDING,
            "answer" to "",
            "created_at" to Timestamp.now(),
        )).await()
        ref.id
    }

    fun watchLocationRequest(targetUid: String, requestId: String): Flow<LocationRequestDto?> =
        locationRequests(targetUid).document(requestId).snapshots()
            .map { snap -> snap.toObject(LocationRequestDto::class.java)?.copy(id = snap.id) }

    fun watchPendingLocationRequests(uid: String): Flow<List<LocationRequestDto>> =
        locationRequests(uid).whereEqualTo("status", LocationRequest.PENDING)
            .snapshots()
            .map { snap ->
                snap.documents.mapNotNull { doc ->
                    doc.toObject(LocationRequestDto::class.java)?.copy(id = doc.id)
                }
            }

    suspend fun answerLocationRequest(
        uid: String,
        requestId: String,
        status: String,
        answer: String,
    ): Result<Unit> = runCatching {
        locationRequests(uid).document(requestId).update(
            mapOf("status" to status, "answer" to answer, "answered_at" to Timestamp.now())
        ).await()
    }
}
