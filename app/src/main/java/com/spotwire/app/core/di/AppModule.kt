package com.spotwire.app.core.di

import com.spotwire.app.data.local.MonitorLogStore
import com.spotwire.app.data.local.PreferencesDataSource
import com.spotwire.app.data.local.VisitLogStore
import com.spotwire.app.core.utils.PhoneNormalizer
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val appModule = module {
    single { PreferencesDataSource(androidContext()) }
    single { MonitorLogStore(androidContext()) }
    single { VisitLogStore(androidContext()) }
    single { PhoneNormalizer() }
}
