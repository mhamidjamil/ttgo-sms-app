package com.spotwire.app.core.di

import com.spotwire.app.presentation.alerts.AlertSourcesViewModel
import com.spotwire.app.presentation.auth.AuthViewModel
import com.spotwire.app.presentation.auth.PhoneVerifyViewModel
import com.spotwire.app.presentation.auto.AutoViewModel
import com.spotwire.app.presentation.history.HistoryViewModel
import com.spotwire.app.presentation.links.LinkedAccountsViewModel
import com.spotwire.app.presentation.monitor.MonitorLogViewModel
import com.spotwire.app.presentation.profile.ProfileViewModel
import com.spotwire.app.presentation.send.SendViewModel
import com.spotwire.app.presentation.settings.SettingsHistoryViewModel
import com.spotwire.app.presentation.settings.SettingsViewModel
import com.spotwire.app.presentation.whatsapp.WhatsAppViewModel
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

val viewModelModule = module {
    viewModel { AuthViewModel(get(), get(), get(), get(), get(), get()) }
    viewModel { SendViewModel(get(), get(), get(), get(), get(), get(), get()) }
    viewModel { HistoryViewModel(get(), get(), get(), get()) }
    viewModel { ProfileViewModel(get(), get(), get(), get(), get(), get(), get(), get()) }
    viewModel { PhoneVerifyViewModel(get(), get(), get(), get(), get(), get()) }
    // V2
    viewModel { AutoViewModel(get(), get(), get(), get()) }
    viewModel { SettingsViewModel(get(), get(), get(), get(), get(), get(), get()) }
    viewModel { SettingsHistoryViewModel(get()) }
    viewModel { MonitorLogViewModel(get(), get(), get()) }
    viewModel { WhatsAppViewModel(get(), get()) }
    // Sharing: who may see my location, and who is alerting me
    viewModel { AlertSourcesViewModel(get(), get(), get()) }
    viewModel { LinkedAccountsViewModel(get(), get(), get()) }
}
