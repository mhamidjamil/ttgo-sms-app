package com.spotwire.app.domain.repository

import com.spotwire.app.domain.model.AccountLink
import com.spotwire.app.domain.model.IncomingAlert
import com.spotwire.app.domain.model.LinkPermissions
import com.spotwire.app.domain.model.LinkState
import com.spotwire.app.domain.model.LocationAnswer
import com.spotwire.app.domain.model.LocationRequest
import com.spotwire.app.domain.model.PlaceVisit
import kotlinx.coroutines.flow.Flow

interface LinkRepository {
    // Publishes uid + name against a verified number so invites can find this
    // account. Called once the number is verified.
    suspend fun publishDirectoryEntry(phoneNumber: String, uid: String, name: String): Result<Unit>
    // (uid, name) of whoever verified this number, or null when nobody has.
    suspend fun lookupByPhone(phoneNumber: String): Result<Pair<String, String>?>

    suspend fun invite(
        myUid: String,
        myName: String,
        myPhone: String,
        otherUid: String,
        otherName: String,
        otherPhone: String,
        permissions: LinkPermissions,
    ): Result<Unit>

    suspend fun setState(
        myUid: String,
        otherUid: String,
        state: LinkState,
        alsoUpdateOtherSide: Boolean,
    ): Result<Unit>

    suspend fun setPermissions(myUid: String, otherUid: String, permissions: LinkPermissions): Result<Unit>
    suspend fun remove(myUid: String, otherUid: String): Result<Unit>
    fun getLinks(uid: String): Flow<List<AccountLink>>
    // One-shot read of my approved links, for callers with no screen to keep a
    // flow alive (the arrival service, the location-request answerer).
    suspend fun activeLinks(uid: String): List<AccountLink>

    // ── Alerts delivered inside the app ───────────────────────────────────────

    /** Puts one alert on a linked person's own account. */
    suspend fun deliverInAppAlert(
        recipientUid: String,
        senderUid: String,
        senderName: String,
        senderPhone: String,
        message: String,
        placeLabel: String,
    ): Result<Unit>

    fun incomingAlerts(uid: String): Flow<List<IncomingAlert>>
    suspend fun unseenIncomingAlerts(uid: String): Result<List<IncomingAlert>>
    suspend fun markAlertSeen(uid: String, alertId: String): Result<Unit>

    // On-demand location: the asker creates a request in the target's own
    // subcollection and watches that one document for the answer.
    suspend fun requestLocation(
        targetUid: String,
        requesterUid: String,
        requesterName: String,
        mode: String = LocationRequest.PLACE,
    ): Result<String>
    fun watchLocationRequest(targetUid: String, requestId: String): Flow<LocationRequest?>
    fun watchPendingRequests(uid: String): Flow<List<LocationRequest>>
    suspend fun answerRequest(uid: String, requestId: String, status: String, answer: String): Result<Unit>

    // A live request collects a run of readings rather than one answer, so the
    // asker watches the position settle. Stopping is the asker's to do.
    suspend fun appendAnswer(
        uid: String,
        requestId: String,
        latitude: Double,
        longitude: Double,
        accuracyMeters: Float,
        placeLabel: String,
        networks: List<String>,
    ): Result<Unit>
    fun watchAnswers(targetUid: String, requestId: String): Flow<List<LocationAnswer>>
    suspend fun stopRequest(targetUid: String, requestId: String): Result<Unit>

    /**
     * Somebody else's stays. A place id narrows it to one place, which is the
     * only shape a per-place contact's grant can satisfy: the rules are checked
     * against every row a query could return, so a query without the filter is
     * refused outright rather than trimmed.
     */
    fun visitsOf(otherUid: String, sinceMillis: Long, placeId: String?): Flow<List<PlaceVisit>>
}
