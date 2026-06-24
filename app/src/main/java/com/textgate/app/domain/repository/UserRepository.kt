package com.textgate.app.domain.repository

import com.google.firebase.auth.FirebaseUser
import com.textgate.app.domain.model.User

interface UserRepository {
    suspend fun signIn(email: String, password: String): Result<FirebaseUser>
    suspend fun signUp(email: String, password: String, name: String): Result<FirebaseUser>
    suspend fun sendVerificationEmail(): Result<Unit>
    suspend fun signOut()
    suspend fun getCurrentUser(): User?
    suspend fun updateQuotaReset(uid: String, remainingQuota: Int, resetDate: String): Result<Unit>
    suspend fun decrementRemainingQuota(uid: String): Result<Unit>
    suspend fun syncEmailVerified(uid: String, verified: Boolean): Result<Unit>
    fun isLoggedIn(): Boolean
    fun currentFirebaseUser(): FirebaseUser?

    // Phone verification (V1.5)
    suspend fun savePhoneNumber(uid: String, phoneNumber: String): Result<Unit>
    suspend fun savePhoneOtp(uid: String, otp: String): Result<Unit>
    suspend fun getPhoneOtp(uid: String): Result<String?>
    suspend fun markPhoneVerified(uid: String): Result<Unit>
}
