package com.textgate.app.core.di

import com.textgate.app.data.local.PreferencesDataSource
import com.textgate.app.core.utils.PhoneNormalizer
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val appModule = module {
    single { PreferencesDataSource(androidContext()) }
    single { PhoneNormalizer() }
}
