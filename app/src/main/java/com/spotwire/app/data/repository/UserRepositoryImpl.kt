package com.spotwire.app.data.repository

import android.util.Log
import com.google.firebase.auth.FirebaseUser
import com.spotwire.app.data.firebase.FirebaseAuthDataSource
import com.spotwire.app.data.firebase.FirestoreDataSource
import com.spotwire.app.data.local.MonitorLogStore
import com.spotwire.app.data.local.PreferencesDataSource
import com.spotwire.app.domain.model.Place
import com.spotwire.app.domain.model.SettingsChange
import com.spotwire.app.domain.model.User
import com.spotwire.app.domain.repository.UserRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val TAG = "UserRepository"

class UserRepositoryImpl(
    private val auth: FirebaseAuthDataSource,
    private val firestore: FirestoreDataSource,
    private val prefs: PreferencesDataSource,
    private val monitorLog: MonitorLogStore,
) : UserRepository {

    override suspend fun signIn(email: String, password: String): Result<FirebaseUser> {
        val result = auth.signIn(email, password)
        result.onSuccess { user ->
            prefs.setCachedUid(user.uid)
            // Legacy link-verified accounts upgrade the flag; never write false —
            // that would clobber an in-app OTP verification (Firestore is the
            // source of truth for email_verified).
            if (user.isEmailVerified) firestore.syncEmailVerified(user.uid, true)
        }
        return result
    }

    override suspend fun signUp(email: String, password: String, name: String): Result<FirebaseUser> {
        val authResult = auth.signUp(email, password)
        authResult.onSuccess { user ->
            // Auth displayName was never set before, so the auto-heal path
            // reconstructed names from the email prefix ("muhammad.tauseeq").
            auth.updateDisplayName(name)
            val quota = firestore.getDeviceFreeSmsQuota().getOrDefault(10)
            // The doc write used to be fire-and-forget — a failure silently
            // lost the user's name. Retry once before giving up.
            firestore.createUser(user.uid, email, name, quota).onFailure {
                firestore.createUser(user.uid, email, name, quota)
            }
            prefs.setCachedUid(user.uid)
        }
        return authResult
    }

    override suspend fun signOut() {
        auth.signOut()
        prefs.clearAll()
        // The activity log names the places this person visited and when, so it
        // belongs to the account, not to the phone.
        monitorLog.clear()
    }

    override suspend fun getCurrentUser(): User? {
        val fbUser = auth.currentUser() ?: return null
        // A FAILED read must never fall through to the auto-heal write below.
        // getOrNull() used to flatten "read failed" and "doc missing" into null,
        // so any offline/timeout read (the arrival service does one on every WiFi
        // connect) re-created the document and wiped the saved settings.
        val existing = firestore.getUser(fbUser.uid).getOrElse { error ->
            Log.w(TAG, "user doc read failed for ${fbUser.uid}, skipping auto-heal", error)
            return null
        }
        if (existing != null) return existing.toDomain()
        // Auto-heal: create the doc if it was never written (e.g. signed up before V1.5)
        Log.i(TAG, "user doc missing for ${fbUser.uid}, creating it")
        val quota = firestore.getDeviceFreeSmsQuota().getOrDefault(10)
        firestore.createUser(
            uid = fbUser.uid,
            email = fbUser.email ?: "",
            name = fbUser.displayName ?: fbUser.email?.substringBefore("@") ?: "",
            quota = quota,
        )
        return firestore.getUser(fbUser.uid).getOrNull()?.toDomain()
    }

    override suspend fun updateQuotaReset(uid: String, remainingQuota: Int, resetDate: String) =
        firestore.updateQuotaReset(uid, remainingQuota, resetDate)

    override suspend fun decrementRemainingQuota(uid: String) =
        firestore.decrementRemainingQuota(uid)

    override suspend fun updateName(uid: String, name: String): Result<Unit> = runCatching {
        firestore.updateName(uid, name).getOrThrow()
        auth.updateDisplayName(name)   // best-effort; Firestore is the display source
    }

    override suspend fun syncEmailVerified(uid: String, verified: Boolean) =
        firestore.syncEmailVerified(uid, verified)

    override suspend fun refreshEmailVerified(): Result<Boolean> = runCatching {
        // Reload the cached Firebase user so a verification link clicked AFTER
        // sign-in is noticed without a re-login. Only ever upgrades the
        // Firestore flag: an account verified before this changed keeps its
        // flag even though Firebase never saw a link click.
        auth.reload().getOrThrow()
        val fbUser = auth.currentUser() ?: error("No authenticated user")
        if (fbUser.isEmailVerified) {
            markEmailVerified(fbUser.uid).getOrThrow()
            return@runCatching true
        }
        firestore.getUser(fbUser.uid).getOrNull()?.emailVerified ?: false
    }

    override suspend fun sendEmailVerification(): Result<Unit> = auth.sendEmailVerification()

    override suspend fun sendPasswordReset(email: String): Result<Unit> =
        auth.sendPasswordReset(email)

    override fun isLoggedIn() = auth.isLoggedIn()

    override fun currentFirebaseUser() = auth.currentUser()

    // ── Phone verification (V1.5) ─────────────────────────────────────────────

    // Saving a number also clears phone_verified, so it is audited: this is the
    // one legitimate path that can un-verify a phone, and the audit line tells
    // the two cases apart when a user reports "my number un-verified itself".
    override suspend fun savePhoneNumber(uid: String, phoneNumber: String): Result<Unit> {
        val previous = firestore.getUser(uid).getOrNull()
        val result = firestore.savePhoneNumber(uid, phoneNumber)
        if (result.isSuccess && previous?.phoneNumber != phoneNumber) {
            logChange(uid, "Phone number", previous?.phoneNumber.orEmpty(), phoneNumber)
        }
        return result
    }

    override suspend fun savePhoneOtp(uid: String, otp: String) =
        firestore.savePhoneOtp(uid, otp)

    override suspend fun getPhoneOtp(uid: String) =
        firestore.getPhoneOtp(uid)

    override suspend fun markPhoneVerified(uid: String): Result<Unit> =
        firestore.markPhoneVerified(uid).onSuccess {
            logChange(uid, "Phone verified", "no", "yes")
        }

    // ── Email verification ────────────────────────────────────────────────────

    // Idempotent: the confirm button can be tapped repeatedly, so only the
    // transition from unverified is worth an audit line.
    override suspend fun markEmailVerified(uid: String): Result<Unit> {
        if (firestore.getUser(uid).getOrNull()?.emailVerified == true) return Result.success(Unit)
        return firestore.markEmailVerified(uid).onSuccess {
            logChange(uid, "Email verified", "no", "yes")
        }
    }

    // Stored data first, sign-in account last: the Firestore rules only let a
    // signed-in owner erase their own rows, so deleting the account first would
    // strand every one of them with nobody left who is allowed to remove it.
    override suspend fun deleteAccount(): Result<Unit> = runCatching {
        val fbUser = auth.currentUser() ?: error("No authenticated user")
        val phoneNumber = firestore.getUser(fbUser.uid).getOrNull()?.phoneNumber.orEmpty()
        firestore.deleteUserData(fbUser.uid, phoneNumber).getOrThrow()
        prefs.clearAll()
        monitorLog.clear()
        auth.deleteAccount().getOrThrow()
    }

    override suspend fun requestMoreSms(uid: String, note: String, currentQuota: Int): Result<Unit> =
        firestore.saveQuotaRequest(uid, note, currentQuota).onSuccess {
            logChange(uid, "Quota increase requested", currentQuota.toString(), note.ifBlank { "(no message)" })
        }

    private suspend fun logChange(uid: String, field: String, old: String, new: String) {
        firestore.logSettingsChanges(uid, listOf(SettingsChange(field = field, oldValue = old, newValue = new)))
    }

    // ── Arrival monitoring (V2) ───────────────────────────────────────────────

    override suspend fun savePlacesSettings(
        uid: String,
        guardianNumber: String,
        places: List<Place>,
    ) = firestore.savePlacesSettings(uid, guardianNumber, places)

    override suspend fun savePlaces(uid: String, places: List<Place>) =
        firestore.savePlaces(uid, places)

    override suspend fun recordArrival(uid: String, placeId: String, date: String, currentTime: String) =
        firestore.recordArrival(uid, placeId, date, currentTime)

    override suspend fun logSettingsChanges(uid: String, changes: List<SettingsChange>) =
        firestore.logSettingsChanges(uid, changes)

    override fun getSettingsHistory(uid: String): Flow<List<SettingsChange>> =
        firestore.getSettingsHistory(uid).map { list -> list.map { it.toDomain() } }
}
