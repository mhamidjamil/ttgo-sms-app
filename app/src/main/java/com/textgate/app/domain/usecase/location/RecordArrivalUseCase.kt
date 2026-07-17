package com.textgate.app.domain.usecase.location

import com.textgate.app.core.utils.DateUtils
import com.textgate.app.domain.model.PlaceContact
import com.textgate.app.domain.repository.AlertRepository
import com.textgate.app.domain.repository.LinkRepository
import com.textgate.app.domain.repository.SmsRepository
import com.textgate.app.domain.repository.UserRepository
import com.textgate.app.domain.repository.WhatsAppRepository
import com.textgate.app.domain.usecase.sms.EnqueueSmsUseCase

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
        const val MANAGE_FOOTER = "\n- To stop alerts, sign up on TextGate"

        // Marks the sender signature as machine-sent. Its counterpart on a
        // hand-pressed location share is SendLocationNowUseCase.MANUAL_SUFFIX.
        const val AUTOMATION_SUFFIX = " (via automation)"
    }

    suspend operator fun invoke(uid: String, placeId: String, routineTriggered: Boolean): Result<Unit> = runCatching {
        val user = userRepo.getCurrentUser() ?: error("User not found")
        val place = user.places.find { it.id == placeId } ?: return@runCatching

        // No day guard here. Whether this visit may alert is decided by the
        // presence state machine in ArrivalService, which knows about departures;
        // a calendar date cannot tell a second visit from a repeat.
        val today = DateUtils.todayString()

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
                userRepo.recordArrival(uid, placeId, today, DateUtils.currentTimeHHmm())
            }
            return@runCatching
        }

        // Capture the ARRIVAL time now — queueing can delay actual delivery by
        // minutes, and the receiver must see when the arrival happened, not
        // when the gateway finally sent the message.
        val arrivalTime = DateUtils.currentTime12h()
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
                )
            } else {
                smsRepo.enqueueAutoArrivalSms(
                    uid = uid,
                    phoneNumber = contact.number,
                    recipientName = contact.name,
                    message = message,
                    location = placeId,
                    locationLabel = label,
                    routineTriggered = routineTriggered,
                ).onFailure { failure ->
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
                        error = failure.message.orEmpty(),
                    )
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
        userRepo.recordArrival(uid, placeId, today, DateUtils.currentTimeHHmm()).getOrThrow()
    }
}
