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

    suspend fun sendVerificationEmail(): Result<Unit> = runCatching {
        val user = auth.currentUser ?: error("No authenticated user")
        user.sendEmailVerification().await()
    }

    fun signOut() = auth.signOut()

    fun currentUser(): FirebaseUser? = auth.currentUser

    fun isLoggedIn(): Boolean = auth.currentUser != null
}
