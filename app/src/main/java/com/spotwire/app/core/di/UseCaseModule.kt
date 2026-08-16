package com.spotwire.app.core.di

import com.spotwire.app.domain.usecase.auth.SendEmailVerificationUseCase
import com.spotwire.app.domain.usecase.auth.SendPhoneOtpUseCase
import com.spotwire.app.domain.usecase.auth.SignInUseCase
import com.spotwire.app.domain.usecase.auth.SignUpUseCase
import com.spotwire.app.domain.usecase.auth.ConfirmEmailVerifiedUseCase
import com.spotwire.app.domain.usecase.auth.VerifyPhoneOtpUseCase
import com.spotwire.app.domain.usecase.alerts.UnsubscribeFromSenderUseCase
import com.spotwire.app.domain.usecase.auto.GetAutoHistoryUseCase
import com.spotwire.app.domain.usecase.auto.RefreshAutoJobStatusUseCase
import com.spotwire.app.domain.usecase.auto.RetryAutoArrivalUseCase
import com.spotwire.app.domain.usecase.links.AnswerLocationRequestsUseCase
import com.spotwire.app.domain.usecase.links.InviteLinkUseCase
import com.spotwire.app.domain.usecase.location.GetPlaceRecipientsUseCase
import com.spotwire.app.domain.usecase.location.RecordArrivalUseCase
import com.spotwire.app.domain.usecase.location.SavePlacesUseCase
import com.spotwire.app.domain.usecase.location.SendLocationNowUseCase
import com.spotwire.app.domain.usecase.quota.CheckAndResetQuotaUseCase
import com.spotwire.app.domain.usecase.quota.DecrementQuotaUseCase
import com.spotwire.app.domain.usecase.quota.GetEffectiveQuotaUseCase
import com.spotwire.app.domain.usecase.quota.RequestMoreSmsUseCase
import com.spotwire.app.domain.usecase.sms.EnqueueSmsUseCase
import com.spotwire.app.domain.usecase.sms.SendWhatsAppUseCase
import com.spotwire.app.domain.usecase.sms.GetHistoryUseCase
import com.spotwire.app.domain.usecase.sms.RefreshJobStatusUseCase
import org.koin.dsl.module

val useCaseModule = module {
    factory { SignInUseCase(get()) }
    factory { SignUpUseCase(get()) }
    factory { SendEmailVerificationUseCase(get(), get()) }
    factory { ConfirmEmailVerifiedUseCase(get()) }
    factory { CheckAndResetQuotaUseCase(get()) }
    factory { GetEffectiveQuotaUseCase(get()) }
    factory { DecrementQuotaUseCase(get()) }
    factory { RequestMoreSmsUseCase(get(), get()) }
    factory { EnqueueSmsUseCase(get(), get()) }
    factory { SendWhatsAppUseCase(get(), get(), get()) }
    factory { GetHistoryUseCase(get()) }
    factory { RefreshJobStatusUseCase(get(), get()) }
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
