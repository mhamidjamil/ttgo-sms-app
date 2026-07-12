package com.textgate.app.core.di

import com.textgate.app.domain.usecase.auth.SendEmailVerificationUseCase
import com.textgate.app.domain.usecase.auth.SendPhoneOtpUseCase
import com.textgate.app.domain.usecase.auth.SignInUseCase
import com.textgate.app.domain.usecase.auth.SignUpUseCase
import com.textgate.app.domain.usecase.auth.ConfirmEmailVerifiedUseCase
import com.textgate.app.domain.usecase.auth.VerifyPhoneOtpUseCase
import com.textgate.app.domain.usecase.alerts.UnsubscribeFromSenderUseCase
import com.textgate.app.domain.usecase.auto.GetAutoHistoryUseCase
import com.textgate.app.domain.usecase.auto.RefreshAutoJobStatusUseCase
import com.textgate.app.domain.usecase.auto.RetryAutoArrivalUseCase
import com.textgate.app.domain.usecase.links.AnswerLocationRequestsUseCase
import com.textgate.app.domain.usecase.links.InviteLinkUseCase
import com.textgate.app.domain.usecase.location.GetPlaceRecipientsUseCase
import com.textgate.app.domain.usecase.location.RecordArrivalUseCase
import com.textgate.app.domain.usecase.location.SavePlacesUseCase
import com.textgate.app.domain.usecase.location.SendLocationNowUseCase
import com.textgate.app.domain.usecase.quota.CheckAndResetQuotaUseCase
import com.textgate.app.domain.usecase.quota.DecrementQuotaUseCase
import com.textgate.app.domain.usecase.quota.GetEffectiveQuotaUseCase
import com.textgate.app.domain.usecase.quota.RequestMoreSmsUseCase
import com.textgate.app.domain.usecase.sms.EnqueueSmsUseCase
import com.textgate.app.domain.usecase.sms.GetHistoryUseCase
import com.textgate.app.domain.usecase.sms.RefreshJobStatusUseCase
import org.koin.dsl.module

val useCaseModule = module {
    factory { SignInUseCase(get()) }
    factory { SignUpUseCase(get()) }
    factory { SendEmailVerificationUseCase(get(), get()) }
    factory { ConfirmEmailVerifiedUseCase(get()) }
    factory { CheckAndResetQuotaUseCase(get()) }
    factory { GetEffectiveQuotaUseCase() }
    factory { DecrementQuotaUseCase(get()) }
    factory { RequestMoreSmsUseCase(get(), get()) }
    factory { EnqueueSmsUseCase(get(), get()) }
    factory { GetHistoryUseCase(get()) }
    factory { RefreshJobStatusUseCase(get()) }
    factory { SendPhoneOtpUseCase(get(), get(), get(), get()) }
    factory { VerifyPhoneOtpUseCase(get(), get()) }
    // V2
    factory { SavePlacesUseCase(get()) }
    factory { RecordArrivalUseCase(get(), get(), get(), get(), get()) }
    factory { GetPlaceRecipientsUseCase(get(), get()) }
    factory { SendLocationNowUseCase(get(), get(), get(), get()) }
    factory { GetAutoHistoryUseCase(get()) }
    factory { RefreshAutoJobStatusUseCase(get()) }
    factory { RetryAutoArrivalUseCase(get()) }
    // Alert subscriptions + linked accounts
    factory { UnsubscribeFromSenderUseCase(get()) }
    factory { InviteLinkUseCase(get(), get(), get()) }
    factory { AnswerLocationRequestsUseCase(get(), get()) }
}
