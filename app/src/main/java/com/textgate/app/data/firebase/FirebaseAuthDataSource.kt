package com.textgate.app.data.firebase

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.userProfileChangeRequest
import kotlinx.coroutines.tasks.await

class FirebaseAuthDataSource(private val auth: FirebaseAuth) {

    suspend fun signIn(email: String, password: String): Result<FirebaseUser> = runCatching {
        val result = auth.signInWithEmailAndPassword(email, password).await()
        result.user ?: error("Sign-in returned null user")
    }

    suspend fun signUp(email: String, password: String): Result<FirebaseUser> = runCatching {
        val result = auth.createUserWithEmailAndPassword(email, password).await()
        result.user ?: error("Sign-up returned null user")
    }

    // Keep the Auth profile's displayName in sync — the auto-heal path uses it
    // when the Firestore doc is missing, so it must never be null.
    suspend fun updateDisplayName(name: String): Result<Unit> = runCatching {
        val user = auth.currentUser ?: error("No authenticated user")
        user.updateProfile(userProfileChangeRequest { displayName = name }).await()
    }

    // Re-fetches the Firebase user so isEmailVerified reflects a verification
    // link clicked AFTER sign-in (the cached user object never updates on its
    // own).
    suspend fun reload(): Result<Unit> = runCatching {
        val user = auth.currentUser ?: error("No authenticated user")
        user.reload().await()
    }

    // Firebase sends and checks the verification email itself, so no mail
    // credential has to be shipped inside the app.
    suspend fun sendEmailVerification(): Result<Unit> = runCatching {
        val user = auth.currentUser ?: error("No authenticated user")
        if (user.isEmailVerified) return@runCatching
        user.sendEmailVerification().await()
    }

    fun isEmailVerified(): Boolean = auth.currentUser?.isEmailVerified == true

    /**
     * Removes the Firebase Auth account. Firebase refuses this on a stale
     * session, which is a security feature rather than a bug, so the caller is
     * told to sign in again rather than being left with a half-deleted account.
     */
    suspend fun deleteAccount(): Result<Unit> = runCatching {
        val user = auth.currentUser ?: error("No authenticated user")
        try {
            user.delete().await()
        } catch (e: FirebaseAuthRecentLoginRequiredException) {
            throw IllegalStateException(
                "For your security, sign out and sign in again, then delete your account.", e,
            )
        }
    }

    fun signOut() = auth.signOut()

    fun currentUser(): FirebaseUser? = auth.currentUser

    fun isLoggedIn(): Boolean = auth.currentUser != null
}
