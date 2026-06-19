package com.spotwire.app.core.di

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.spotwire.app.data.firebase.FirebaseAuthDataSource
import com.spotwire.app.data.firebase.FirestoreDataSource
import org.koin.dsl.module

val firebaseModule = module {
    single { FirebaseAuth.getInstance() }
    single { FirebaseFirestore.getInstance() }
    single { FirebaseAuthDataSource(get()) }
    single { FirestoreDataSource(get()) }
}
