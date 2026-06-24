package com.textgate.app.core.di

import com.textgate.app.data.repository.SmsRepositoryImpl
import com.textgate.app.data.repository.UserRepositoryImpl
import com.textgate.app.domain.repository.SmsRepository
import com.textgate.app.domain.repository.UserRepository
import org.koin.dsl.module

val repositoryModule = module {
    single<UserRepository> { UserRepositoryImpl(get(), get(), get()) }
    single<SmsRepository> { SmsRepositoryImpl(get()) }
}
