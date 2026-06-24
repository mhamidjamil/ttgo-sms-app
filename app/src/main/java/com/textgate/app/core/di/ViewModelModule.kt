package com.textgate.app.core.di

import com.textgate.app.presentation.auth.AuthViewModel
import com.textgate.app.presentation.auth.PhoneVerifyViewModel
import com.textgate.app.presentation.auto.AutoViewModel
import com.textgate.app.presentation.history.HistoryViewModel
import com.textgate.app.presentation.profile.ProfileViewModel
import com.textgate.app.presentation.send.SendViewModel
import com.textgate.app.presentation.settings.SettingsViewModel
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

val viewModelModule = module {
    viewModel { AuthViewModel(get(), get(), get(), get(), get()) }
    viewModel { SendViewModel(get(), get(), get(), get(), get()) }
    viewModel { HistoryViewModel(get(), get(), get()) }
    viewModel { ProfileViewModel(get(), get(), get()) }
    viewModel { PhoneVerifyViewModel(get(), get(), get()) }
    // V2
    viewModel { AutoViewModel(get(), get()) }
    viewModel { SettingsViewModel(get(), get()) }
}
