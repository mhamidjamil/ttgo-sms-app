package com.textgate.app.data.repository

import com.google.firebase.auth.FirebaseUser
import com.textgate.app.data.firebase.FirebaseAuthDataSource
import com.textgate.app.data.firebase.FirestoreDataSource
import com.textgate.app.data.local.PreferencesDataSource
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
            firestore.syncEmailVerified(user.uid, user.isEmailVerified)
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

    override suspend fun sendVerificationEmail(): Result<Unit> = auth.sendVerificationEmail()

    override suspend fun signOut() {
        auth.signOut()
        prefs.clearAll()
    }

    override suspend fun getCurrentUser(): User? {
        val fbUser = auth.currentUser() ?: return null
        return firestore.getUser(fbUser.uid).getOrNull()?.toDomain()
    }

    override suspend fun updateQuotaReset(uid: String, remainingQuota: Int, resetDate: String) =
        firestore.updateQuotaReset(uid, remainingQuota, resetDate)

    override suspend fun decrementRemainingQuota(uid: String) =
        firestore.decrementRemainingQuota(uid)

    override suspend fun syncEmailVerified(uid: String, verified: Boolean) =
        firestore.syncEmailVerified(uid, verified)

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

    // ── Arrival monitoring (V2) ───────────────────────────────────────────────

    override suspend fun saveLocationSettings(
        uid: String,
        guardianNumber: String,
        homeBssid: String,
        homeLabel: String,
        officeBssid: String,
        officeLabel: String,
    ) = firestore.saveLocationSettings(uid, guardianNumber, homeBssid, homeLabel, officeBssid, officeLabel)

    override suspend fun recordArrival(uid: String, location: String, date: String, currentTime: String) =
        firestore.recordArrival(uid, location, date, currentTime)
}
