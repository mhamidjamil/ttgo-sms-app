package com.textgate.app

import android.app.Application
import com.textgate.app.core.di.appModule
import com.textgate.app.core.di.firebaseModule
import com.textgate.app.core.di.repositoryModule
import com.textgate.app.core.di.useCaseModule
import com.textgate.app.core.di.viewModelModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@App)
            modules(
                appModule,
                firebaseModule,
                repositoryModule,
                useCaseModule,
                viewModelModule,
            )
        }
    }
}
