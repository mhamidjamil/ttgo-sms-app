package com.spotwire.app.core.di

import com.spotwire.app.data.repository.AlertRepositoryImpl
import com.spotwire.app.data.repository.LinkRepositoryImpl
import com.spotwire.app.data.repository.SmsRepositoryImpl
import com.spotwire.app.data.repository.ThrottleRepositoryImpl
import com.spotwire.app.data.repository.UserRepositoryImpl
import com.spotwire.app.data.repository.WhatsAppRepositoryImpl
import com.spotwire.app.data.whatsapp.WaConfigProvider
import com.spotwire.app.data.whatsapp.WhatsAppApi
import com.spotwire.app.domain.repository.AlertRepository
import com.spotwire.app.domain.repository.LinkRepository
import com.spotwire.app.domain.repository.SmsRepository
import com.spotwire.app.domain.repository.ThrottleRepository
import com.spotwire.app.domain.repository.UserRepository
import com.spotwire.app.domain.repository.WhatsAppRepository
import org.koin.dsl.module

val repositoryModule = module {
    single<UserRepository> { UserRepositoryImpl(get(), get(), get()) }
    single<SmsRepository> { SmsRepositoryImpl(get()) }
    single<AlertRepository> { AlertRepositoryImpl(get()) }
    single<LinkRepository> { LinkRepositoryImpl(get()) }
    single { WaConfigProvider(get()) }
    single { WhatsAppApi(get()) }
    single<WhatsAppRepository> { WhatsAppRepositoryImpl(get(), get(), get(), get(), get()) }
    single<ThrottleRepository> { ThrottleRepositoryImpl(get()) }
}
