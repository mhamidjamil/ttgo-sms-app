# TextGate

**SMS Gateway Client for Android** — queue SMS messages through your TTGO T-Call GSM device via Firebase Firestore.

[![Min SDK](https://img.shields.io/badge/minSdk-26-blue)](https://developer.android.com/about/versions/oreo/android-8.0)
[![Target SDK](https://img.shields.io/badge/targetSdk-34-green)](https://developer.android.com/about/versions/14)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.22-purple)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/Compose-2024.02.00-blueviolet)](https://developer.android.com/jetpack/compose)

---

## Features

### V1 — Core SMS Queue Client

- **Firebase Auth** — email + password sign-up and sign-in
- **Email verification flow** — unverified accounts have a reduced daily quota
- **Phone number collection** — Pakistani mobile number (03XX format) collected at sign-up
- **Pakistani-number-only enforcement** — app accepts `03XXXXXXXXX`, `923XXXXXXXXX`, or `+923XXXXXXXXX`; auto-normalizes to E.164 (`+923XXXXXXXXX`) before sending to Firestore; displays a clear error for any other format
- **Daily SMS quota** with automatic midnight reset — quota sourced from `sim_module/device/free_sms_quota` (no hardcoded values; change it in Firebase without a new app release)
- **Per-user SMS history** — stored in `sim_module/users/{uid}/history/`, independent of the shared `sms_jobs` collection so two users sending to the same number never overwrite each other's history
- **Live status polling** — history screen auto-refreshes pending/in-progress jobs every 10 s (configurable); per-item manual refresh button
- **Status chips** — color-coded: pending (amber), in-progress (blue), sent (green), failed (red), blocked (orange)
- **Configurable Firestore paths** — all collection paths in `local.properties` so a schema change is a one-line edit, not a code change
- **Quota guard** — send button disabled when daily quota is exhausted; clear progress bar shows remaining SMS

### V1.5 — Phone Number Verification + 3-Tier Quota

- **3-tier daily quota**:
  - Both email + phone verified → full assigned quota (default 10)
  - Either email or phone verified → partial quota (default 4)
  - Neither verified → minimum quota (default 2)
- **Phone OTP verification** — after sign-up, a 6-digit code is queued as an SMS job to the user's own number via the TTGO gateway; user enters the code in-app to verify
- **No OTP expiry** — the code stays valid until used (intentional design)
- **Resend code** — user can request a new code any time from the verify screen or from Profile
- **Profile page verification banners** — separate banners for unverified email and unverified phone, each with an action button
- **Skip option** — phone verify can be skipped; verified later from the Profile screen

### V2 — Automated Arrival Notifications (WiFi-Based)

- **BSSID-based location detection** — stores home and office WiFi BSSIDs (MAC addresses), not SSIDs, to prevent spoofing
- **Stability timer** — must stay connected to the target BSSID for N minutes (configurable) before triggering; a quick pass-through does not fire
- **One notification per day per location** — daily cooldown guard prevents multiple alerts
- **Guardian SMS** — queues a message `"{name} arrived at {label} N minutes ago"` to a saved guardian phone number
- **Routine learning** — stores the last 30 arrival times per location; if ≥ 5 samples exist, uses μ ± σ of HH:mm values to detect "expected arrival" and reduces the stability wait to `max(MIN_STABILITY_MINUTES, stability_minutes / 2)` when within the routine window
- **Foreground service** — `ArrivalService` registers a `NetworkCallback` and keeps BSSID access alive in the background (Android 10+ requires `ACCESS_BACKGROUND_LOCATION` — rationale screen explains why)
- **Auto-jobs tab** — fourth bottom-nav tab streams `users/{uid}/auto_history` with the same status-chip UI as the regular history

---

## Architecture

```
Presentation (Jetpack Compose + ViewModel)
    │
Domain (pure Kotlin use cases + interfaces)
    │
Data (Firebase Auth/Firestore, DataStore, Repository impls)
```

Dependency injection: **Koin 3.5**. Navigation: **Jetpack Navigation Compose**. All Firebase calls use Kotlin coroutines (`.await()`).

Full diagram → [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)

---

## Firebase Schema

```
sim_module/                          # existing TTGO device collection
  device/
    free_sms_quota: 10               # app reads at sign-up

  sms/
    sms_jobs/{phone_number}/         # ONE active job per number (device constraint)
      message, status, enque_by

  users/{uid}/                       # NEW — one doc per Firebase Auth user
    email, name
    email_verified, phone_number, phone_verified, phone_otp
    assigned_quota, remaining_quota, last_quota_reset_date
    created_at

  users/{uid}/history/{autoId}/      # per-user sent-message history
    phone_number, message, status
    enqueued_at, job_phone_key, enque_by

  users/{uid}/auto_history/{autoId}/ # V2 — arrival-triggered jobs
    location, sent_at, status, message, routine_triggered
```

Full field reference → [`docs/FIREBASE-SCHEMA.md`](docs/FIREBASE-SCHEMA.md)

---

## Prerequisites

- Android Studio Hedgehog (2023.1.1) or later
- JDK 17
- A Firebase project with **Firestore** and **Authentication (email/password)** enabled
- The TTGO T-Call device running the v8 firmware — the app only queues jobs; the device handles GSM delivery

---

## Setup

1. **Clone the repo**
   ```bash
   git clone https://github.com/your-org/textgate.git
   cd textgate
   ```

2. **Copy the config template**
   ```bash
   cp local.properties.example local.properties
   ```

3. **Fill in `local.properties`**

   | Key | Description |
   |-----|-------------|
   | `FIREBASE_PROJECT_ID` | Your Firebase project ID (from console) |
   | `SMS_JOBS_PATH` | Firestore path to sms_jobs collection |
   | `USERS_PATH` | Firestore path to users collection |
   | `DEVICE_DOC_PATH` | Firestore path to the device document |
   | `UNVERIFIED_QUOTA` | SMS/day for accounts with neither verified (default 2) |
   | `PARTIAL_VERIFIED_QUOTA` | SMS/day for accounts with one verified (default 4) |
   | `HISTORY_POLL_INTERVAL_SECONDS` | Auto-poll interval in seconds (default 10) |
   | `WIFI_STABILITY_MINUTES` | V2: minutes before arrival trigger (default 10) |
   | `MIN_WIFI_STABILITY_MINUTES` | V2: minimum adaptive wait in minutes (default 5) |

4. **Add `google-services.json`**

   Download from Firebase console → *Project Settings → Your apps → google-services.json*
   Place it at `app/google-services.json`.

5. **Build**
   ```bash
   ./gradlew assembleDebug
   ```
   Install on device:
   ```bash
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

Detailed Firebase console steps → [`docs/SETUP.md`](docs/SETUP.md)

---

## local.properties Reference

| Key | Default | Description |
|-----|---------|-------------|
| `FIREBASE_PROJECT_ID` | — | Required. Firebase project ID. |
| `SMS_JOBS_PATH` | `sim_module/sms/sms_jobs` | Firestore collection for outgoing SMS jobs. |
| `USERS_PATH` | `sim_module/users` | Firestore collection for user documents. |
| `DEVICE_DOC_PATH` | `sim_module/device` | Firestore path to the device config document. |
| `UNVERIFIED_QUOTA` | `2` | Daily SMS cap for accounts with no verifications. |
| `PARTIAL_VERIFIED_QUOTA` | `4` | Daily SMS cap for accounts with one verification. |
| `HISTORY_POLL_INTERVAL_SECONDS` | `10` | How often the History screen polls pending jobs. |
| `WIFI_STABILITY_MINUTES` | `10` | V2: minutes of stable WiFi connection before arrival SMS fires. |
| `MIN_WIFI_STABILITY_MINUTES` | `5` | V2: minimum wait the routine-learning algorithm can reduce to. |

---

## Running & Testing

1. Sign up with a valid Pakistani number (03XXXXXXXXX) and an email address.
2. Confirm the `sim_module/users/{uid}` document was created in Firebase console.
3. Check your phone for the OTP SMS (delivered via the TTGO device — it must be online).
4. Send a test SMS — verify `sim_module/sms/sms_jobs/{normalizedNumber}` appears with `status: "pending"`.
5. Open History — confirm the entry appears and status updates as the TTGO processes the job.
6. Confirm an unverified account is capped at `UNVERIFIED_QUOTA` sends/day.
7. Verify email and phone — confirm quota rises to `assigned_quota`.

---

## Known Limitations

- **One active SMS job per phone number** — `sms_jobs` doc ID = the phone number (device constraint). If two users send to the same number simultaneously, the second write overwrites the job. Each user's history record is independent (stored in `users/{uid}/history/`), and `enque_by` is used to detect overwrites during status polling.
- **OTP delivery depends on TTGO** — if the device is offline, the OTP SMS will not arrive until it comes back online.
- **V2 requires `ACCESS_BACKGROUND_LOCATION`** — Android 10+ shows a permission dialog that requires a rationale; the V2 setup screen includes this explanation.

---

## Contributing

- Branch naming: `feat/`, `fix/`, `docs/` prefixes
- Commit style: `feat(scope): short description`
- Schema changes → update `docs/FIREBASE-SCHEMA.md` in the same commit
- All Firestore paths → `local.properties` + `Constants.kt`, never hardcoded in business logic
