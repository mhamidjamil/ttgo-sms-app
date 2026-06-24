package com.textgate.app.core.di

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.textgate.app.data.firebase.FirebaseAuthDataSource
import com.textgate.app.data.firebase.FirestoreDataSource
import org.koin.dsl.module

val firebaseModule = module {
    single { FirebaseAuth.getInstance() }
    single { FirebaseFirestore.getInstance() }
    single { FirebaseAuthDataSource(get()) }
    single { FirestoreDataSource(get()) }
}
