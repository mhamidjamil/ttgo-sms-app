package com.textgate.app.core.di

import com.textgate.app.data.repository.AlertRepositoryImpl
import com.textgate.app.data.repository.LinkRepositoryImpl
import com.textgate.app.data.repository.SmsRepositoryImpl
import com.textgate.app.data.repository.ThrottleRepositoryImpl
import com.textgate.app.data.repository.UserRepositoryImpl
import com.textgate.app.data.repository.WhatsAppRepositoryImpl
import com.textgate.app.data.whatsapp.WaConfigProvider
import com.textgate.app.data.whatsapp.WhatsAppApi
import com.textgate.app.domain.repository.AlertRepository
import com.textgate.app.domain.repository.LinkRepository
import com.textgate.app.domain.repository.SmsRepository
import com.textgate.app.domain.repository.ThrottleRepository
import com.textgate.app.domain.repository.UserRepository
import com.textgate.app.domain.repository.WhatsAppRepository
import org.koin.dsl.module

val repositoryModule = module {
    single<UserRepository> { UserRepositoryImpl(get(), get(), get()) }
    single<SmsRepository> { SmsRepositoryImpl(get()) }
    single<AlertRepository> { AlertRepositoryImpl(get()) }
    single<LinkRepository> { LinkRepositoryImpl(get()) }
    single { WaConfigProvider(get()) }
    single { WhatsAppApi(get()) }
    single<WhatsAppRepository> { WhatsAppRepositoryImpl(get(), get(), get(), get()) }
    single<ThrottleRepository> { ThrottleRepositoryImpl(get()) }
}
