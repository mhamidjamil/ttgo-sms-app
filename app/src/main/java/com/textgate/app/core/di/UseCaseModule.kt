package com.textgate.app.core.di

import com.textgate.app.domain.usecase.auth.SendPhoneOtpUseCase
import com.textgate.app.domain.usecase.auth.SendVerificationEmailUseCase
import com.textgate.app.domain.usecase.auth.SignInUseCase
import com.textgate.app.domain.usecase.auth.SignUpUseCase
import com.textgate.app.domain.usecase.auth.VerifyPhoneOtpUseCase
import com.textgate.app.domain.usecase.quota.CheckAndResetQuotaUseCase
import com.textgate.app.domain.usecase.quota.DecrementQuotaUseCase
import com.textgate.app.domain.usecase.quota.GetEffectiveQuotaUseCase
import com.textgate.app.domain.usecase.sms.EnqueueSmsUseCase
import com.textgate.app.domain.usecase.sms.GetHistoryUseCase
import com.textgate.app.domain.usecase.sms.RefreshJobStatusUseCase
import org.koin.dsl.module

val useCaseModule = module {
    factory { SignInUseCase(get()) }
    factory { SignUpUseCase(get()) }
    factory { SendVerificationEmailUseCase(get()) }
    factory { CheckAndResetQuotaUseCase(get()) }
    factory { GetEffectiveQuotaUseCase() }
    factory { DecrementQuotaUseCase(get()) }
    factory { EnqueueSmsUseCase(get(), get()) }
    factory { GetHistoryUseCase(get()) }
    factory { RefreshJobStatusUseCase(get()) }
    factory { SendPhoneOtpUseCase(get(), get()) }
    factory { VerifyPhoneOtpUseCase(get()) }
}
