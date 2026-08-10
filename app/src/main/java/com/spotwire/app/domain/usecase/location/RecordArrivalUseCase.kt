package com.spotwire.app.domain.usecase.location

import android.util.Log
import com.spotwire.app.core.utils.DateUtils
import com.spotwire.app.domain.model.EnqueueResult
import com.spotwire.app.domain.model.PlaceContact
import com.spotwire.app.domain.model.User
import com.spotwire.app.domain.repository.AlertRepository
import com.spotwire.app.domain.repository.LinkRepository
import com.spotwire.app.domain.repository.SmsRepository
import com.spotwire.app.domain.repository.UserRepository
import com.spotwire.app.domain.repository.WhatsAppRepository
import com.spotwire.app.domain.usecase.sms.EnqueueSmsUseCase

/**
 * What became of one arrival, recipient by recipient. A single "it worked" was
 * printed even when every job had failed, so the monitoring log could not tell
 * a delivered alert from one that never left the phone.
 */
data class ArrivalOutcome(
    val whatsAppSent: Int = 0,
    val enqueued: Int = 0,
    val queuedOnDevice: Int = 0,
    val failed: Int = 0,
    val firstFailure: String = "",
    val routineHistorySaved: Boolean = true,
) {
    val total: Int get() = whatsAppSent + enqueued + queuedOnDevice + failed
}

class RecordArrivalUseCase(
    private val userRepo: UserRepository,
    private val smsRepo: SmsRepository,
    private val waRepo: WhatsAppRepository,
    private val alertRepo: AlertRepository,
    private val linkRepo: LinkRepository,
) {
    companion object {
        // Recipients can turn these alerts off themselves, but only from their own
        // account, so the line has to say where to go and not just that it is
        // possible. Plain ASCII only, or the whole SMS switches to UCS-2.
        const val MANAGE_FOOTER = "\n- To stop alerts, sign up on Spotwire"

        // Marks the sender signature as machine-sent. Its counterpart on a
        // hand-pressed location share is SendLocationNowUseCase.MANUAL_SUFFIX.
        const val AUTOMATION_SUFFIX = " (via automation)"

        private const val TAG = "SpotwireArrival"
    }

    // How the arrival was established. The defaults are what the WiFi engine has
    // always done, so a caller that knows nothing about geofences is unchanged.
    suspend operator fun invoke(
        uid: String,
        placeId: String,
        routineTriggered: Boolean,
        visitStartedAt: Long,
        detectionMethod: String = "wifi",
        wifiMatch: Boolean = true,
        fallbackUser: User? = null,
    ): Result<ArrivalOutcome> = runCatching {
        // The fresh read fails at exactly the moments arrivals happen: the phone
        // is mid-handover onto the place's own WiFi, which may be portal-gated
        // or dead. The caller hands over the user its detection was based on,
        // so a read that cannot be made never costs the alert itself.
        val user = userRepo.getCurrentUser() ?: fallbackUser
            ?: error("Could not read the account from the server or the cache")
        val place = user.places.find { it.id == placeId } ?: return@runCatching ArrivalOutcome()

        // No day guard here. Whether this visit may alert is decided by the
        // presence state machine in ArrivalService, which knows about departures;
        // a calendar date cannot tell a second visit from a repeat.
        val today = DateUtils.todayString()
        // The routine learner is supposed to learn ARRIVAL times. It was being
        // fed the clock at the end of the dwell wait, so every sample it kept
        // was 5 to 20 minutes after the person actually got there.
        val arrivedHHmm = if (visitStartedAt > 0) {
            DateUtils.timeHHmm(visitStartedAt)
        } else {
            DateUtils.currentTimeHHmm()
        }

        // The default guardian is ALWAYS notified, plus every contact configured
        // for this place, plus any linked account granted automatic updates. One
        // SMS job per recipient, because the TTGO module sends one at a time.
        // Named entries come first so a number that appears twice keeps its name.
        val linked = linkRepo.activeLinks(uid)
            .filter { it.permissions.autoLocationUpdates }
            .map { PlaceContact(name = it.otherName, number = it.otherPhone) }
        val candidates = (place.contacts + linked + PlaceContact(name = "", number = user.guardianNumber))
            .filter { it.number.isNotBlank() }
            .distinctBy { it.number }
        // Anyone who unsubscribed in their own copy of the app is skipped.
        val recipients = candidates.filter { alertRepo.isAllowed(it.number, uid) }
        if (recipients.isEmpty()) {
            // Everyone opted out: record the day anyway so the service stops
            // re-checking this place until tomorrow.
            if (candidates.isNotEmpty()) {
                userRepo.recordArrival(uid, placeId, today, arrivedHHmm)
            }
            return@runCatching ArrivalOutcome()
        }

        // The receiver must see when the visit BEGAN, not when the dwell wait
        // finished and not when the gateway got round to sending. Only a visit
        // the service could not date falls back to the clock right now.
        val arrivalTime = if (visitStartedAt > 0) {
            DateUtils.time12h(visitStartedAt)
        } else {
            DateUtils.currentTime12h()
        }
        val label = place.label.ifBlank { placeId }
        // Per-place custom message, or the default arrival line — the event
        // timestamp is ALWAYS appended. WhatsApp can carry its own text.
        val defaultLine = "${user.name} arrived at $label"
        // Same accountability signature as a manual send: the SMS gateway number
        // is shared between users, so an arrival alert has to name the verified
        // sender it came from. Skipped only when there is no verified number yet.
        // The trailing marker separates an alert nobody pressed send on from the
        // location the user shared by hand.
        val signature = if (user.phoneNumber.isBlank()) {
            ""
        } else {
            EnqueueSmsUseCase.signature(user.phoneNumber) + AUTOMATION_SUFFIX
        }
        val suffix = " at $arrivalTime" + signature + MANAGE_FOOTER
        val message = place.message.ifBlank { defaultLine } + suffix
        val waText = place.waMessage.ifBlank { place.message }.ifBlank { defaultLine } + suffix

        // WhatsApp first when a gateway account is linked (free, no SMS quota);
        // fall back to the SMS gateway per recipient when unlinked or the send
        // fails. A failure for one recipient must not block the others.
        val waLinked = waRepo.isLinked()
        var whatsAppSent = 0
        var enqueued = 0
        var queuedOnDevice = 0
        var failed = 0
        var firstFailure = ""
        recipients.forEach { contact ->
            // recipientName personalizes for the RECEIVER (gateway anti-ban).
            val sentViaWhatsApp = waLinked &&
                waRepo.sendMessage(contact.number, waText, contact.name.ifBlank { null }).isSuccess
            if (sentViaWhatsApp) {
                // Record WhatsApp deliveries too, or the Auto page never shows them.
                smsRepo.logAutoWhatsAppArrival(
                    uid = uid,
                    phoneNumber = contact.number,
                    recipientName = contact.name,
                    message = waText,
                    location = placeId,
                    locationLabel = label,
                    routineTriggered = routineTriggered,
                    detectedAt = visitStartedAt,
                    detectionMethod = detectionMethod,
                    wifiMatch = wifiMatch,
                    latitude = place.latitude,
                    longitude = place.longitude,
                    radiusMeters = if (place.hasGeofence) place.radiusMeters else 0,
                )
                whatsAppSent += 1
            } else {
                smsRepo.enqueueAutoArrivalSms(
                    uid = uid,
                    phoneNumber = contact.number,
                    recipientName = contact.name,
                    message = message,
                    location = placeId,
                    locationLabel = label,
                    routineTriggered = routineTriggered,
                    detectedAt = visitStartedAt,
                    detectionMethod = detectionMethod,
                    wifiMatch = wifiMatch,
                    latitude = place.latitude,
                    longitude = place.longitude,
                    radiusMeters = if (place.hasGeofence) place.radiusMeters else 0,
                ).onSuccess { result ->
                    if (result == EnqueueResult.QUEUED_ON_DEVICE) queuedOnDevice += 1 else enqueued += 1
                }.onFailure { failure ->
                    // Leave a failed row for THIS recipient so the Auto page can
                    // show them and retry just them. Without it a recipient the
                    // gateway rejected is lost with no trace.
                    smsRepo.logAutoArrivalFailure(
                        uid = uid,
                        phoneNumber = contact.number,
                        recipientName = contact.name,
                        message = message,
                        location = placeId,
                        locationLabel = label,
                        routineTriggered = routineTriggered,
                        detectedAt = visitStartedAt,
                        error = failure.message.orEmpty(),
                        detectionMethod = detectionMethod,
                        wifiMatch = wifiMatch,
                        latitude = place.latitude,
                        longitude = place.longitude,
                        radiusMeters = if (place.hasGeofence) place.radiusMeters else 0,
                    )
                    failed += 1
                    if (firstFailure.isBlank()) firstFailure = failure.message.orEmpty()
                    Log.w(TAG, "$placeId: could not queue the alert for ${contact.number}", failure)
                }
            }
            // Leaves a trail the recipient can find when they install the app,
            // even if that is months from now.
            alertRepo.recordSubscription(
                recipientPhone = contact.number,
                senderUid = uid,
                senderName = user.name,
                senderPhone = user.phoneNumber,
            )
        }
        // The arrival itself is recorded whatever the gateway did, because it
        // happened. Delivery is now tracked per recipient, so a failure is a row
        // that can be retried on its own rather than a reason to replay the whole
        // fan-out and double-message everyone who was already reached.
        // Losing this row only costs the routine learner one sample, so it is
        // reported and not thrown: failing the whole send over it would have
        // turned a slow network into "no alert went out".
        val historySaved = userRepo.recordArrival(uid, placeId, today, arrivedHHmm)
            .onFailure { Log.w(TAG, "$placeId: arrival time not added to the routine history", it) }
            .isSuccess
        ArrivalOutcome(
            whatsAppSent = whatsAppSent,
            enqueued = enqueued,
            queuedOnDevice = queuedOnDevice,
            failed = failed,
            firstFailure = firstFailure,
            routineHistorySaved = historySaved,
        )
    }
}
