package com.textgate.app.data.firebase

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
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

    // Re-fetches the Firebase user so isEmailVerified reflects a legacy
    // verification link clicked AFTER sign-in (the cached user object never
    // updates on its own). New accounts verify via the in-app email OTP.
    suspend fun reload(): Result<Unit> = runCatching {
        val user = auth.currentUser ?: error("No authenticated user")
        user.reload().await()
    }

    fun signOut() = auth.signOut()

    fun currentUser(): FirebaseUser? = auth.currentUser

    fun isLoggedIn(): Boolean = auth.currentUser != null
}
