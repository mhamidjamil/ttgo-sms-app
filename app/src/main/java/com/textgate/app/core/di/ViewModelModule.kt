package com.textgate.app.core.di

import com.textgate.app.presentation.auth.AuthViewModel
import com.textgate.app.presentation.auth.PhoneVerifyViewModel
import com.textgate.app.presentation.history.HistoryViewModel
import com.textgate.app.presentation.profile.ProfileViewModel
import com.textgate.app.presentation.send.SendViewModel
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

val viewModelModule = module {
    viewModel { AuthViewModel(get(), get(), get(), get(), get()) }
    viewModel { SendViewModel(get(), get(), get(), get(), get()) }
    viewModel { HistoryViewModel(get(), get()) }
    viewModel { ProfileViewModel(get(), get(), get()) }
    viewModel { PhoneVerifyViewModel(get(), get(), get()) }
}
