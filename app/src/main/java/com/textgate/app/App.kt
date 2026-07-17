package com.textgate.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
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
        watchForeground()
    }

    // The arrival service used to read the user document from the server every
    // two minutes, around seven hundred times a day, which kept the mobile radio
    // awake far more than the WiFi scanning did. It now reads settings only while
    // someone is actually in the app, so this is the flag it asks.
    private fun watchForeground() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var started = 0

            override fun onActivityStarted(activity: Activity) {
                started++
                isInForeground = true
            }

            override fun onActivityStopped(activity: Activity) {
                started--
                if (started <= 0) isInForeground = false
            }

            override fun onActivityCreated(activity: Activity, bundle: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, bundle: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    companion object {
        @Volatile
        var isInForeground: Boolean = false
            private set
    }
}
