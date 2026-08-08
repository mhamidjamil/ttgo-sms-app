# AGENTS.md

<!-- CLAUDE-KNOWLEDGE-BLOCK:START (managed by ~/.claude/knowledge - do not edit by hand) -->
## Central knowledge (read this first)

This project does not hold its own copy of the working rules or the lessons
learned elsewhere. Those live in one place, shared by every project:

**`~/.claude/knowledge/`** (private repository `hamidjamil0420/claude-knowledge`)

Read before starting work here:

1. `~/.claude/knowledge/rules/portable-working-rules.md` - how to work with
   Hamid: communication, git and commits, code quality, testing, database.
2. `~/.claude/knowledge/learnings/cross-project.md` - dated lessons from every
   project, each with the reason it matters.
3. `~/.claude/knowledge/playbooks/mobile-app.md` - what is already known about
   building this kind of project.
4. `~/.claude/knowledge/projects/index.md` - what this project shares with the
   others, and therefore what a change here can break somewhere else.

Before publishing or releasing anything, walk
`~/.claude/knowledge/checklists/ship-readiness.md`. It is short, and skipping it
is how a real credential reached a published application once already.

**Write back the moment something is learned.** Do not leave it in this
conversation and do not batch it for the end. Decide where by asking whether it
would have helped a different project:

- Useful to any project: `~/.claude/knowledge/learnings/cross-project.md` and
  the Obsidian note `Claude - Learnings`.
- Useful to projects of this kind: the playbook above.
- Only true here: this file, below this block.

Announce it with the up arrow emoji when recording and the down arrow emoji when
applying, so the learning is visible and not just the result.

If `~/.claude/knowledge/` is missing on this machine, clone it:
`git clone https://github.com/hamidjamil0420/claude-knowledge.git ~/.claude/knowledge`
<!-- CLAUDE-KNOWLEDGE-BLOCK:END -->

## Project Overview
- TextGate is a single-module Android app (`:app`) written in Kotlin + Jetpack Compose. It queues SMS jobs in Firestore for a TTGO T-Call device; the app does not send GSM SMS directly.
- Architecture is Clean Architecture: `presentation/` Compose screens + ViewModels -> `domain/` pure use cases/repository interfaces -> `data/` Firebase/DataStore DTOs and repository implementations.
- Koin is the DI container. Modules live in `core/di/*Module.kt` and are loaded from `App.kt`; add new dependencies there rather than constructing them in screens.

## Firebase/TTGO Data Flow
- Outgoing SMS writes a Firestore batch in `FirestoreDataSource.enqueueJob`: `Paths.SMS_JOBS/{phone}` for the device and `Paths.USERS/{uid}/history/{autoId}` for the app UI.
- `sms_jobs` doc IDs are auto-generated, one document per queued message, so two messages to the same number can no longer overwrite each other. History rows carry `job_id`; rows written before this fall back to `job_phone_key`, which is what those older job documents were keyed by. The device finds work by querying `status == "pending"`, never by building an ID from a phone number.
- OTP SMS uses the same TTGO queue but no history/quota: see `SendPhoneOtpUseCase` and `FirestoreDataSource.enqueueOtpSms`, with `enque_by = "app:{uid}:otp"`.
- V2 arrival notifications run in `services/ArrivalService`. It holds a four-state presence machine per place (AWAY / APPROACHING / HERE / BLIND) persisted in `PreferencesDataSource`, NOT in Firestore: the Firestore user document falls back to an offline cache that can be hours stale, and no alert may depend on that.
- V4 makes Android geofences the primary trigger (`services/GeofenceManager` + `services/GeofenceEventReceiver`): a fence ENTER opens a bounded wake-locked validation session in `ArrivalService`, WiFi confirms through the same presence machine, and a place with no saved networks falls back to one fused-location fix. When every alerting place is fence-covered the resident service stops itself; places without coordinates keep the old always-on WiFi sweeps. Design and diagnosis: `docs/V4-GEOFENCE-HYBRID.md`.
- A place is reached when enough of its saved BSSIDs are audible (`Place.isPresentIn`) and the loudest clears its closeness floor. Only ONE place can win a sweep; ties send nothing.
- The alert guard is one per VISIT, cleared only by an OBSERVED departure (the place unheard AND the surroundings no longer overlapping what was heard there). A gap in observation is never a departure — that is what made switching the radios on in the morning look like arriving home.
- The wait counts only time the phone spent still, measured from the step counter. Without that sensor it degrades to plain elapsed time rather than stopping.
- Settings are read from the server only while the app is on screen (`App.isInForeground`) plus a six-hour safety refresh. Do not reintroduce a per-sweep Firestore read; it was the largest battery cost in the feature.
- `services/ArrivalWatchdogReceiver` restarts monitoring after a reboot, an app update, or an OEM battery-manager kill.

## Configuration
- Firestore paths and quotas come from `local.properties` via `app/build.gradle.kts` `buildConfigField`s, then through `core/utils/Constants.kt`. Do not hardcode paths in business logic.
- Current defaults include `SMS_JOBS_PATH=sim_module/sms/sms_jobs`, `USERS_PATH=ttgo_users`, and `DEVICE_DOC_PATH=sim_module/device`.
- Setup requires `app/google-services.json`; use `local.properties.example` as the local config template. Do not commit secrets or machine-local Firebase files.
- **No credential may ever be a `buildConfigField`.** They compile to `public static final String`, so the literal is inlined before R8 runs and `strings` reads it straight out of the published APK. The SMTP mailer and the WhatsApp SSO secret were removed for exactly this reason; email verification now goes through Firebase, which holds its own credentials server-side.
- Release signing reads the gitignored `keystore.properties` at the repo root, pointing at a keystore kept outside the repository. Without it the release variant still builds, just unsigned, so a fresh clone works.
- Firebase project is the dedicated `textgate-344f2` (since August 2026; the old shared `myacademiaapp` project still holds the pre-migration data and serves academia). Firestore rules live in `firestore.rules`, Realtime Database rules in `database.rules.json`, deployed with `firebase deploy --only firestore:rules,database`. The TTGO firmware shares this database, so read the firmware repo before changing `sim_module` paths.

## Code Patterns
- Domain use cases expose `suspend operator fun invoke(...)` and usually return `Result<T>`; data sources wrap Firebase calls with `runCatching { ... await() }`.
- ViewModels keep immutable `data class *UiState` in `MutableStateFlow`, expose `StateFlow` via `asStateFlow()`, and launch work in `viewModelScope`.
- Firestore DTOs live in `data/model` and map to domain with `toDomain()`. Use `@PropertyName` for snake_case Firestore fields, as in `UserDto` and `HistoryEntryDto`.
- Phone inputs must pass through `PhoneNormalizer`; only Pakistani mobile formats `03...`, `923...`, and `+923...` are accepted and normalized to `+923XXXXXXXXX`.
- Navigation is centralized in `core/navigation/AppNavGraph.kt`; bottom tabs are `Send`, `History`, `Arrival`, and `Profile`, while auth and the detail screens (settings history, WhatsApp, incoming alerts, linked accounts, monitoring log) are outside the bottom bar. `History` carries a Manual/Automated filter rather than a separate Auto tab.
- Arrival monitoring writes a 72-hour on-device activity log through `MonitorLogStore` (JSON lines in app storage), shown on the Monitoring Log page reached from the Arrival tab and exportable through the share sheet from that page. New sweep-level decisions should log there as well as to Logcat, with repeat-per-sweep conditions written once when they appear.

## Versioning
- App version is in `app/build.gradle.kts`: `versionCode` (integer, bumped for each release) and `versionName` (semver string, e.g. `1.0.1`).
- **Whenever you make any solid changes or bug fixes, always bump `versionCode` by 1 and update `versionName` appropriately** (patch bump for fixes, minor for features). The version is displayed at the bottom of the Send screen via `BuildConfig.VERSION_NAME`.
- Current version: `1.6.0` (versionCode=9).

## Developer Workflow
- Toolchain is AGP 8.9.1 / Gradle 8.11.1 / JDK 17, compiling against SDK 36. `targetSdk` must stay at 36 or above: Play refuses a lower target for new apps and updates from 31 August 2026.
- Main build command: `./run-build.sh assembleDebug` (it pins JAVA_HOME/ANDROID_HOME for this repo only). `./run-build.sh bundleRelease` produces the signed Play bundle.
- There are no Kotlin test source sets. The Firestore rules DO have tests: they run on the emulator, which needs JDK 21 or newer, unlike the app build.
- `docs/PLAY-RELEASE.md` is the release checklist and the record of what is still deliberately open.
- Important docs: `docs/ARCHITECTURE.md` for flows and design decisions, `docs/FIREBASE-SCHEMA.md` for Firestore fields, and `docs/V2-ARRIVAL-FEATURE.md` for arrival behavior.
