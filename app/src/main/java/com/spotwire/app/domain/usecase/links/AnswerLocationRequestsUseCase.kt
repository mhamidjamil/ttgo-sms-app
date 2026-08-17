package com.spotwire.app.domain.usecase.links

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.spotwire.app.core.utils.placeInRange
import com.spotwire.app.core.utils.visibleNetworks
import com.spotwire.app.domain.model.LocationRequest
import com.spotwire.app.domain.repository.LinkRepository
import com.spotwire.app.domain.repository.UserRepository
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Answers one incoming "where are you?" request.
 *
 * A PLACE ask gets the place LABEL and nothing else: no hardware id, no
 * coordinates. If no saved place is within WiFi range the answer stays vague on
 * purpose ("not at a saved place").
 *
 * A PRECISE ask gets the coordinates, how accurate the fix was, and the wireless
 * networks the phone can hear, and only if this exact account was given that
 * permission separately. It is the answer to somebody being somewhere that was
 * never saved: the network names usually name the shop or the house when a pair
 * of numbers does not.
 *
 * A requester without the permission is denied rather than silently ignored, so
 * their screen stops waiting.
 */
class AnswerLocationRequestsUseCase(
    private val userRepo: UserRepository,
    private val linkRepo: LinkRepository,
) {
    suspend operator fun invoke(
        uid: String,
        request: LocationRequest,
        visibleBssids: Set<String>,
        context: Context? = null,
    ): Result<Unit> {
        val link = linkRepo.activeLinks(uid).firstOrNull { it.otherUid == request.requesterUid }
        if (link == null || !link.permissions.requestLocation) {
            return linkRepo.answerRequest(uid, request.id, LocationRequest.DENIED, "")
        }
        val user = userRepo.getCurrentUser()
            ?: return linkRepo.answerRequest(uid, request.id, LocationRequest.DENIED, "")
        val place = placeInRange(user.places, visibleBssids)
        val placeLabel = place?.let { it.label.ifBlank { it.id } }.orEmpty()

        if (request.mode == LocationRequest.PRECISE) {
            if (!link.permissions.preciseLocation || context == null) {
                return linkRepo.answerRequest(
                    uid, request.id, LocationRequest.DENIED,
                    "They have not allowed their exact position to be shared.",
                )
            }
            // A request the asker called off, or one that has outlived its
            // ceiling, is closed rather than answered again. The ceiling is what
            // stops a request the asker's phone never came back to from
            // following somebody forever.
            val startedAt = request.createdAt?.time ?: 0L
            if (request.stopRequested ||
                (startedAt > 0L && System.currentTimeMillis() - startedAt > LIVE_REQUEST_CEILING_MILLIS)
            ) {
                return linkRepo.answerRequest(
                    uid, request.id, LocationRequest.ANSWERED,
                    "Live location stopped.",
                )
            }
            return answerPrecisely(uid, request, context, placeLabel)
        }

        val answer = if (place == null) {
            "${user.name} is not at a saved place right now."
        } else {
            "${user.name} is at $placeLabel."
        }
        return linkRepo.answerRequest(uid, request.id, LocationRequest.ANSWERED, answer)
    }

    /**
     * One fix plus what the phone can hear, appended to the request's own run of
     * answers. The request is deliberately left pending: that is what keeps the
     * readings coming until the asker stops it or the ceiling above closes it.
     */
    @SuppressLint("MissingPermission")
    private suspend fun answerPrecisely(
        uid: String,
        request: LocationRequest,
        context: Context,
        placeLabel: String,
    ): Result<Unit> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return linkRepo.answerRequest(
                uid, request.id, LocationRequest.DENIED,
                "Their phone no longer allows precise location.",
            )
        }
        // The one-shot fix, never a stream: location that outlives the screen is
        // the exact background use Play removes apps for. Highest accuracy here
        // because a fix good to half a kilometre answers nothing.
        val fix = withTimeoutOrNull(FIX_TIMEOUT_MILLIS) {
            runCatching {
                LocationServices.getFusedLocationProviderClient(context)
                    .getCurrentLocation(
                        Priority.PRIORITY_HIGH_ACCURACY,
                        CancellationTokenSource().token,
                    ).await()
            }.onFailure { Log.w(TAG, "Could not take a fix for a live request", it) }.getOrNull()
        }
        // The networks are worth sending even when the satellites are not
        // answering: indoors they are often the only thing that says anything.
        val networks = visibleNetworks(context).map { "${it.ssid}|${it.bssid}|${it.level}" }
        if (fix == null && networks.isEmpty()) {
            Log.w(TAG, "Live request ${request.id}: no fix and nothing audible, nothing to send")
            return Result.success(Unit)
        }
        return linkRepo.appendAnswer(
            uid = uid,
            requestId = request.id,
            latitude = fix?.latitude ?: 0.0,
            longitude = fix?.longitude ?: 0.0,
            accuracyMeters = fix?.accuracy ?: 0f,
            placeLabel = placeLabel,
            networks = networks,
        )
    }

    companion object {
        private const val TAG = "SpotwireLocation"
        // Indoors a fix can wait for satellites that never arrive, and the
        // answer cannot sit there with the asker watching a spinner.
        private const val FIX_TIMEOUT_MILLIS = 20_000L
        // A request nobody closed is a tracker. Half an hour is longer than any
        // real "where has he got to", and after it the phone stops answering
        // whether or not the asker ever comes back.
        private const val LIVE_REQUEST_CEILING_MILLIS = 30 * 60 * 1000L
    }
}
