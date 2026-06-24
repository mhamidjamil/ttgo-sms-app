# AGENTS.md

## Project Overview
- TextGate is a single-module Android app (`:app`) written in Kotlin + Jetpack Compose. It queues SMS jobs in Firestore for a TTGO T-Call device; the app does not send GSM SMS directly.
- Architecture is Clean Architecture: `presentation/` Compose screens + ViewModels -> `domain/` pure use cases/repository interfaces -> `data/` Firebase/DataStore DTOs and repository implementations.
- Koin is the DI container. Modules live in `core/di/*Module.kt` and are loaded from `App.kt`; add new dependencies there rather than constructing them in screens.

## Firebase/TTGO Data Flow
- Outgoing SMS writes a Firestore batch in `FirestoreDataSource.enqueueJob`: `Paths.SMS_JOBS/{phone}` for the device and `Paths.USERS/{uid}/history/{autoId}` for the app UI.
- `sms_jobs` doc IDs are normalized phone numbers, so there is one active job per phone number. Preserve `enque_by = "app:{uid}"` and `job_phone_key` because `RefreshJobStatusUseCase` uses them to avoid updating history from another user's overwritten job.
- OTP SMS uses the same TTGO queue but no history/quota: see `SendPhoneOtpUseCase` and `FirestoreDataSource.enqueueOtpSms`, with `enque_by = "app:{uid}:otp"`.
- V2 arrival notifications run in `services/ArrivalService`: foreground service watches WiFi BSSID, applies `RoutineAnalyzer`, calls `RecordArrivalUseCase`, then writes `auto_history`.

## Configuration
- Firestore paths and quotas come from `local.properties` via `app/build.gradle.kts` `buildConfigField`s, then through `core/utils/Constants.kt`. Do not hardcode paths in business logic.
- Current defaults include `SMS_JOBS_PATH=sim_module/sms/sms_jobs`, `USERS_PATH=ttgo_users`, and `DEVICE_DOC_PATH=sim_module/device`.
- Setup requires `app/google-services.json`; use `local.properties.example` as the local config template. Do not commit secrets or machine-local Firebase files.

## Code Patterns
- Domain use cases expose `suspend operator fun invoke(...)` and usually return `Result<T>`; data sources wrap Firebase calls with `runCatching { ... await() }`.
- ViewModels keep immutable `data class *UiState` in `MutableStateFlow`, expose `StateFlow` via `asStateFlow()`, and launch work in `viewModelScope`.
- Firestore DTOs live in `data/model` and map to domain with `toDomain()`. Use `@PropertyName` for snake_case Firestore fields, as in `UserDto` and `HistoryEntryDto`.
- Phone inputs must pass through `PhoneNormalizer`; only Pakistani mobile formats `03...`, `923...`, and `+923...` are accepted and normalized to `+923XXXXXXXXX`.
- Navigation is centralized in `core/navigation/AppNavGraph.kt`; bottom tabs are `Send`, `History`, `Auto`, and `Profile`, while auth/settings routes are outside the bottom bar.

## Developer Workflow
- This repo may not include `gradle/wrapper/gradle-wrapper.jar`; Android Studio can generate/download it, or run `gradle wrapper --gradle-version 8.4` if local Gradle is installed.
- Main build command: `./gradlew assembleDebug` on Unix-like shells or `.\gradlew.bat assembleDebug` on Windows once the wrapper exists.
- There are currently no test source sets in the repo. For verification, build `assembleDebug` and manually test the Firebase checklist in `README.md`/`docs/SETUP.md`.
- Important docs: `docs/ARCHITECTURE.md` for flows and design decisions, `docs/FIREBASE-SCHEMA.md` for Firestore fields, and `docs/V2-ARRIVAL-FEATURE.md` for arrival behavior.
