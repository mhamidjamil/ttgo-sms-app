# Architecture

TextGate uses **Clean Architecture** with three layers. Dependencies point inward — the domain layer has zero Android or Firebase imports.

---

## Layer Map

```
┌─────────────────────────────────────────────────────┐
│  Presentation (Jetpack Compose + ViewModel)          │
│  Screens: Login, Signup, PhoneVerify, Send,          │
│           History, Profile, (V2) Auto, Settings      │
│  ViewModels: AuthVM, PhoneVerifyVM, SendVM,          │
│              HistoryVM, ProfileVM, (V2) AutoVM       │
└────────────────────┬────────────────────────────────┘
                     │ calls use cases
┌────────────────────▼────────────────────────────────┐
│  Domain (pure Kotlin — no Android / Firebase)        │
│  Models: User, SmsJob, SmsStatus, HistoryEntry       │
│  Repositories: UserRepository, SmsRepository         │
│  Use Cases: SignIn/Up, SendVerificationEmail,         │
│    SendPhoneOtp, VerifyPhoneOtp,                     │
│    CheckAndResetQuota, GetEffectiveQuota,            │
│    DecrementQuota, EnqueueSms, GetHistory,           │
│    RefreshJobStatus                                  │
└────────────────────┬────────────────────────────────┘
                     │ implements interfaces
┌────────────────────▼────────────────────────────────┐
│  Data (Firebase, DataStore, DTOs)                    │
│  FirebaseAuthDataSource                              │
│  FirestoreDataSource                                 │
│  PreferencesDataSource (DataStore)                   │
│  DTOs: UserDto, SmsJobDto, HistoryEntryDto           │
│  Impls: UserRepositoryImpl, SmsRepositoryImpl        │
└─────────────────────────────────────────────────────┘
```

---

## Dependency Injection (Koin)

Five modules, loaded in `App.kt`:

| Module | Contents |
|--------|----------|
| `appModule` | `PreferencesDataSource`, `PhoneNormalizer` |
| `firebaseModule` | `FirebaseAuth`, `FirebaseFirestore`, `FirebaseAuthDataSource`, `FirestoreDataSource` |
| `repositoryModule` | `UserRepository → UserRepositoryImpl`, `SmsRepository → SmsRepositoryImpl` |
| `useCaseModule` | 11 use cases as `factory` (new instance per injection) |
| `viewModelModule` | 5 ViewModels as `viewModel` |

---

## Navigation

```
AppNavGraph (single NavHost, single Activity)
│
├── Login ──────────────────────────────────► Send (main graph)
├── Signup ──────────────────────────────────► PhoneVerify
│                                                   │
│                                          verified / skip
│                                                   │
│                                                   ▼
└──────────────────────────────────────── Main Graph (bottom nav)
                                              Send │ History │ (V2) Auto │ Profile
                                                                              │
                                                                          PhoneVerify
                                                                         (re-verify)
```

Auth screens (`Login`, `Signup`, `PhoneVerify`) hide the bottom navigation bar.

---

## Data Flow: Send SMS

```
SendScreen.send() ──► SendViewModel.send(phone, message)
                            │
                      1. CheckAndResetQuotaUseCase  (reset if new day)
                      2. PhoneNormalizer.normalize() (validate + format)
                      3. EnqueueSmsUseCase
                            │
                      Firestore batch write:
                        sms_jobs/{phone}     ← TTGO device polls this
                        users/{uid}/history/ ← app displays this
                            │
                      4. DecrementQuotaUseCase
```

---

## Data Flow: Phone OTP

```
SignupScreen.register()
      │
AuthViewModel.register(email, pw, name, phone)
      │
1. SignUpUseCase → Firebase Auth + createUser doc
2. SendVerificationEmailUseCase → Firebase Auth
3. SendPhoneOtpUseCase
      │
   generate 6-digit OTP
   save phone_number to users/{uid}
   save phone_otp to users/{uid}
   enqueueOtpSms → sms_jobs/{phone}  (enque_by: "app:{uid}:otp")
      │
→ navigate to PhoneVerifyScreen

PhoneVerifyScreen.verify(code)
      │
VerifyPhoneOtpUseCase(uid, code)
      │
   getPhoneOtp from users/{uid}
   compare → if match: markPhoneVerified (phone_otp field deleted)
```

---

## V2: Arrival Detection

```
ArrivalService (foreground service, persistent notification)
      │
NetworkCallback.onAvailable(network)
      │
   read BSSID (requires ACCESS_BACKGROUND_LOCATION on API 29+)
      │
   match against home_bssid / office_bssid in user doc
      │
   RoutineAnalyzer.effectiveWait(location)
   → if ≥5 arrival times: compute μ ± σ of HH:mm
   → if current time within μ±σ: wait = max(MIN, full/2)
   → else: wait = full stability_minutes
      │
   CountDownTimer(wait minutes)
      │
   on finish: check BSSID still matches + last_*_arrival_date ≠ today
      │
   EnqueueSmsUseCase → guardian_number, message "{name} arrived at {label} N min ago"
   write to users/{uid}/auto_history/
   update last_*_arrival_date
   append arrival time to arrival_*_times (rotate oldest if > 30)
```

---

## Key Design Decisions

| Decision | Reason |
|----------|--------|
| BSSID not SSID for location | SSIDs can be spoofed by renaming a phone hotspot to "Office" |
| History in `users/{uid}/history/` not `sms_jobs` | Device uses phone as doc ID; two users sending to same number would overwrite the job. History must be per-user and never clobbered. |
| OTP via TTGO not Firebase Phone Auth | Same gateway; no additional Firebase product dependency; works without a verified phone plan on the Firebase side |
| OTP no expiry | User explicitly requested this. The phone is registered to one person; a stale code on their device is low-risk. |
| `local.properties` not `.env` | Android build system already treats `local.properties` as the machine-local config file and gitignores it by default. Values are injected into `BuildConfig` at compile time, safe for all build types. |
