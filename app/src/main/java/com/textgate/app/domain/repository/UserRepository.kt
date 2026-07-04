package com.textgate.app.domain.repository

import com.google.firebase.auth.FirebaseUser
import com.textgate.app.domain.model.Place
import com.textgate.app.domain.model.User

interface UserRepository {
    suspend fun signIn(email: String, password: String): Result<FirebaseUser>
    suspend fun signUp(email: String, password: String, name: String): Result<FirebaseUser>
    suspend fun signOut()
    suspend fun getCurrentUser(): User?
    suspend fun updateQuotaReset(uid: String, remainingQuota: Int, resetDate: String): Result<Unit>
    suspend fun decrementRemainingQuota(uid: String): Result<Unit>
    suspend fun syncEmailVerified(uid: String, verified: Boolean): Result<Unit>
    // Reloads the Firebase user; a legacy link-verified account upgrades the
    // Firestore flag to true. Never downgrades an OTP-verified flag. Returns
    // the effective (Firestore) verification status.
    suspend fun refreshEmailVerified(): Result<Boolean>
    fun isLoggedIn(): Boolean
    fun currentFirebaseUser(): FirebaseUser?

    // Phone verification (V1.5)
    suspend fun savePhoneNumber(uid: String, phoneNumber: String): Result<Unit>
    suspend fun savePhoneOtp(uid: String, otp: String): Result<Unit>
    // Returns (code, createdAtMillis) or null when no code is pending.
    suspend fun getPhoneOtp(uid: String): Result<Pair<String, Long>?>
    suspend fun markPhoneVerified(uid: String): Result<Unit>

    // Email verification — 6-digit OTP over SMTP, mirroring the phone flow.
    suspend fun saveEmailOtp(uid: String, otp: String): Result<Unit>
    suspend fun getEmailOtp(uid: String): Result<Pair<String, Long>?>
    suspend fun markEmailVerified(uid: String): Result<Unit>

    // Arrival monitoring (V2) — dynamic place list
    suspend fun savePlacesSettings(
        uid: String,
        guardianNumber: String,
        places: List<Place>,
    ): Result<Unit>
    suspend fun recordArrival(uid: String, placeId: String, date: String, currentTime: String): Result<Unit>
}
