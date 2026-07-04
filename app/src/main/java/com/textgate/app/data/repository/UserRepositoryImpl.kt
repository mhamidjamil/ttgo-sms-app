package com.textgate.app.data.repository

import com.google.firebase.auth.FirebaseUser
import com.textgate.app.data.firebase.FirebaseAuthDataSource
import com.textgate.app.data.firebase.FirestoreDataSource
import com.textgate.app.data.local.PreferencesDataSource
import com.textgate.app.domain.model.Place
import com.textgate.app.domain.model.User
import com.textgate.app.domain.repository.UserRepository

class UserRepositoryImpl(
    private val auth: FirebaseAuthDataSource,
    private val firestore: FirestoreDataSource,
    private val prefs: PreferencesDataSource,
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
            val quota = firestore.getDeviceFreeSmsQuota().getOrDefault(10)
            firestore.createUser(user.uid, email, name, quota)
            prefs.setCachedUid(user.uid)
        }
        return authResult
    }

    override suspend fun signOut() {
        auth.signOut()
        prefs.clearAll()
    }

    override suspend fun getCurrentUser(): User? {
        val fbUser = auth.currentUser() ?: return null
        val existing = firestore.getUser(fbUser.uid).getOrNull()
        if (existing != null) return existing.toDomain()
        // Auto-heal: create the doc if it was never written (e.g. signed up before V1.5)
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

    override suspend fun syncEmailVerified(uid: String, verified: Boolean) =
        firestore.syncEmailVerified(uid, verified)

    override suspend fun refreshEmailVerified(): Result<Boolean> = runCatching {
        // Reload the cached Firebase user so a legacy verification link clicked
        // AFTER sign-in is noticed without a re-login. Only ever upgrades the
        // Firestore flag — the in-app OTP flow writes it independently.
        auth.reload().getOrThrow()
        val fbUser = auth.currentUser() ?: error("No authenticated user")
        if (fbUser.isEmailVerified) {
            firestore.syncEmailVerified(fbUser.uid, true).getOrThrow()
            return@runCatching true
        }
        firestore.getUser(fbUser.uid).getOrNull()?.emailVerified ?: false
    }

    override fun isLoggedIn() = auth.isLoggedIn()

    override fun currentFirebaseUser() = auth.currentUser()

    // ── Phone verification (V1.5) ─────────────────────────────────────────────

    override suspend fun savePhoneNumber(uid: String, phoneNumber: String) =
        firestore.savePhoneNumber(uid, phoneNumber)

    override suspend fun savePhoneOtp(uid: String, otp: String) =
        firestore.savePhoneOtp(uid, otp)

    override suspend fun getPhoneOtp(uid: String) =
        firestore.getPhoneOtp(uid)

    override suspend fun markPhoneVerified(uid: String) =
        firestore.markPhoneVerified(uid)

    // ── Email verification (OTP) ──────────────────────────────────────────────

    override suspend fun saveEmailOtp(uid: String, otp: String) =
        firestore.saveEmailOtp(uid, otp)

    override suspend fun getEmailOtp(uid: String) =
        firestore.getEmailOtp(uid)

    override suspend fun markEmailVerified(uid: String) =
        firestore.markEmailVerified(uid)

    // ── Arrival monitoring (V2) ───────────────────────────────────────────────

    override suspend fun savePlacesSettings(uid: String, guardianNumber: String, places: List<Place>) =
        firestore.savePlacesSettings(uid, guardianNumber, places)

    override suspend fun recordArrival(uid: String, placeId: String, date: String, currentTime: String) =
        firestore.recordArrival(uid, placeId, date, currentTime)
}
