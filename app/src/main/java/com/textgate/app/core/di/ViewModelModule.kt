package com.textgate.app.core.di

import com.textgate.app.presentation.alerts.AlertSourcesViewModel
import com.textgate.app.presentation.auth.AuthViewModel
import com.textgate.app.presentation.auth.PhoneVerifyViewModel
import com.textgate.app.presentation.auto.AutoViewModel
import com.textgate.app.presentation.history.HistoryViewModel
import com.textgate.app.presentation.links.LinkedAccountsViewModel
import com.textgate.app.presentation.profile.ProfileViewModel
import com.textgate.app.presentation.send.SendViewModel
import com.textgate.app.presentation.settings.SettingsHistoryViewModel
import com.textgate.app.presentation.settings.SettingsViewModel
import com.textgate.app.presentation.whatsapp.WhatsAppViewModel
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

val viewModelModule = module {
    viewModel { AuthViewModel(get(), get(), get(), get(), get()) }
    viewModel { SendViewModel(get(), get(), get(), get(), get(), get(), get()) }
    viewModel { HistoryViewModel(get(), get(), get(), get()) }
    viewModel { ProfileViewModel(get(), get(), get(), get(), get(), get(), get()) }
    viewModel { PhoneVerifyViewModel(get(), get(), get(), get(), get()) }
    // V2
    viewModel { AutoViewModel(get(), get(), get(), get()) }
    viewModel { SettingsViewModel(get(), get(), get(), get(), get(), get()) }
    viewModel { SettingsHistoryViewModel(get()) }
    viewModel { WhatsAppViewModel(get(), get()) }
    // Sharing: who may see my location, and who is alerting me
    viewModel { AlertSourcesViewModel(get(), get(), get()) }
    viewModel { LinkedAccountsViewModel(get(), get(), get()) }
}
