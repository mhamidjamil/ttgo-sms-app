# Spotwire

**SMS Gateway Client for Android** — queue SMS messages through your TTGO T-Call GSM device via Firebase Firestore.

[![Min SDK](https://img.shields.io/badge/minSdk-26-blue)](https://developer.android.com/about/versions/oreo/android-8.0)
[![Target SDK](https://img.shields.io/badge/targetSdk-36-green)](https://developer.android.com/about/versions/16)
[![Version](https://img.shields.io/badge/version-1.7.0-informational)](docs/PLAY-RELEASE.md)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.22-purple)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/Compose-2024.02.00-blueviolet)](https://developer.android.com/jetpack/compose)

---

## Features

### V1 — Core SMS Queue Client

- **Firebase Auth** — email + password sign-up and sign-in
- **Email verification flow** — required for WhatsApp linking and admin contact (does not affect SMS quota)
- **Phone number collection** — Pakistani mobile number (03XX format) collected at sign-up
- **Pakistani-number-only enforcement** — app accepts `03XXXXXXXXX`, `923XXXXXXXXX`, or `+923XXXXXXXXX`; auto-normalizes to E.164 (`+923XXXXXXXXX`) before sending to Firestore; displays a clear error for any other format
- **Daily SMS quota** with automatic midnight reset — quota sourced from `sim_module/device/free_sms_quota` (no hardcoded values; change it in Firebase without a new app release)
- **Per-user SMS history** — stored in `ttgo_users/{uid}/history/`, independent of the shared `sms_jobs` collection so two users sending to the same number never overwrite each other's history
- **Live status polling** — history screen auto-refreshes pending/in-progress jobs every 10 s (configurable); per-item manual refresh button
- **Status chips** — color-coded: pending (amber), in-progress (blue), sent (green), failed (red), blocked (orange)
- **Configurable Firestore paths** — all collection paths in `local.properties` so a schema change is a one-line edit, not a code change
- **Quota guard** — send button disabled when daily quota is exhausted; clear progress bar shows remaining SMS

### V1.5 — Phone Number Verification + Phone-Gated Quota

- **Phone-gated daily quota**:
  - Phone verified → full assigned quota (default 10/day from the device doc)
  - Phone NOT verified → **0 SMS/day** (sending disabled — sender identity must be verified)
  - Email verification does not affect the SMS quota (needed for WhatsApp linking + admin contact)
- **Phone OTP verification** — enter your number on the verify screen and request a 6-digit code; it is queued as a priority SMS job (`kind: "otp"`) that the TTGO device sends before all other jobs
- **1-hour OTP expiry** — codes expire 60 minutes after being sent; request a new one any time
- **Message signature** — every manual SMS gets `- Sent by <verified number> via Spotwire` appended automatically for accountability on the shared gateway number; user text is capped at 90 chars so the total stays inside one 160-char SMS segment
- **Request more SMS** — one tap emails the admin (SMTP) with your account details to request a quota increase
- **Profile page verification banners** — separate banners for unverified email and unverified phone, each with an action button
- **Skip option** — phone verify can be skipped; sending stays disabled until verified from Profile

### WhatsApp Integration

- **Arrival notifications via WhatsApp** — link a personal API key from the self-hosted baileys gateway (register on the service dashboard, scan QR, paste key) and arrival messages go out over WhatsApp for free, falling back to SMS automatically
- **Email-verified gate** — WhatsApp settings unlock after email verification
- **In-app guide + status + test send** — Profile → WhatsApp Settings; see [`docs/WHATSAPP.md`](docs/WHATSAPP.md)
- Service URL configurable via `WHATSAPP_SERVICE_URL` (TODO(@dev): make dynamic via Firebase so URL changes don't need a rebuild)

### V2 — Automated Arrival Notifications (WiFi)

- **Dynamic places** — Home and Office are seeded; add any number of custom places, each with its own saved WiFi networks, arrival message, contacts and quiet hours. Every place is deletable.
- **BSSID, not SSID** — a place is recognised by the access-point hardware addresses captured there, so renaming a phone hotspot "Office" cannot spoof it.
- **In range, not connected** — someone on mobile data all day still walks past their own router, so detection never required joining the network.
- **One decision per check** — a home above a shop and an office across the road can both be audible from one spot, so the loudest place wins and only by a clear margin. A tie sends nothing rather than guessing.
- **Per-place sensitivity and closeness** — how long the phone must settle before alerting (Quick / Balanced / Careful, or an exact number of minutes) and how strong the signal must be (Any signal / Nearby / Inside only).
- **One alert per visit**, cleared only by an *observed* departure, so a second trip to the office the same day alerts again while a flickering signal does not alert twice.
- **The wait counts still time only**, measured from the step counter, so a twelve-minute visit alerts while driving past does not.
- **Quiet hours per place** — suppress the message without disturbing the detection itself.
- **Recipients** — the default guardian, plus per-place contacts, plus any linked account granted automatic updates. Recipients can opt out from their own copy of the app.

### V3 — Geofence + WiFi Hybrid Detection

The WiFi engine alone could not survive Android's background limits, so Android's own geofence watcher became the trigger and WiFi became the confirmation.

- **Geofences are the primary trigger** — give a place a position and a radius, and Android wakes the app at the moment you cross in, with the app closed and the process dead. Between events the app runs nothing at all.
- **WiFi confirms the crossing** — a fence says "something crossed a line", the saved networks say "and it is really this place". A place with no saved networks falls back to a single location fix instead.
- **The resident service stops itself** when every alerting place is covered by a fence, which is the whole battery win. Places without coordinates keep the always-on WiFi sweeps, so nothing regressed.
- **Continuous location capture** — capturing a place's position keeps refining and shows the accuracy improving live, the way a map app tightens its circle. You stop when you are happy with it, and a fix worse than 25 m warns you before it is saved.
- **A fence at the wrong spot can no longer blind a place** — every 15 minutes, and on app open, a free read of the cached WiFi scan starts a normal check if a fenced place is audible while the app thinks you are away.
- **Fence wobble is ignored** — a fence that fires exit and re-entry while you sit still, which phones do at night, no longer clears the visit and re-alerts.
- **Sends survive the shutdown** — an alert handed off for delivery keeps the process alive until it lands, and falls back to the on-device account copy if the server cannot be read at that moment.
- **Honest timestamps** — the message carries the moment the visit began, not the moment the dwell wait finished or the gateway got round to sending.

### Diagnostics

- **Monitoring log** — 72 hours of what every check saw, decided and sent, on the phone, with the per-place signal readings behind each row. Exportable to a text file through the Android share sheet.
- **Test detection here** — proves the whole chain in one tap: signed in, account readable from the server, then per place how many networks were heard, how strong, and whether it would alert.
- **Where am I?** — answers with the same matching rule the detector uses, so the screen and the engine can never disagree.
- **Status notice with a clock** — "At Office, checked 2:41 PM" or "Confirming Hostel, checked ...", never a vague "just now" that hides a frozen loop.

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
    sms_jobs/{autoId}/               # one document per queued message
      phone_number, message, status, enque_by, created_at

ttgo_users/{uid}/                    # top level, one doc per Firebase Auth user
  email, name
  email_verified, phone_number, phone_verified, phone_otp
  assigned_quota, remaining_quota, last_quota_reset_date
  places[]                           # each with its WiFi networks, position and radius
  created_at

  history/{autoId}/                  # per-user sent-message history
    phone_number, message, status
    enqueued_at, job_id, enque_by

  auto_history/{autoId}/             # arrival-triggered jobs, one per recipient
    location, sent_at, detected_at, status, message
    detection_method, wifi_match, latitude, longitude, radius_m
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
   | `SMTP_*` / `ADMIN_EMAIL` | Optional mailer config for "Request more SMS" admin emails |
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
| `USERS_PATH` | `ttgo_users` | Firestore collection for user documents. |
| `DEVICE_DOC_PATH` | `sim_module/device` | Firestore path to the device config document. |
| `SMTP_HOST/PORT/SECURE/USER/PASS/FROM_EMAIL/SENDER_NAME` | blank | SMTP mailer for admin "request more" emails (blank = disabled; values are compiled into the APK — use a low-privilege account). |
| `ADMIN_EMAIL` | blank | Recipient of quota-increase requests. |
| `HISTORY_POLL_INTERVAL_SECONDS` | `10` | How often the History screen polls pending jobs. |
| `WIFI_STABILITY_MINUTES` | `10` | V2: minutes of stable WiFi connection before arrival SMS fires. |
| `MIN_WIFI_STABILITY_MINUTES` | `5` | V2: minimum wait the routine-learning algorithm can reduce to. |

---

## Running & Testing

1. Sign up with a valid Pakistani number (03XXXXXXXXX) and an email address.
2. Confirm the `ttgo_users/{uid}` document was created in Firebase console.
3. Check your phone for the OTP SMS (delivered via the TTGO device — it must be online).
4. Send a test SMS — verify `sim_module/sms/sms_jobs/{normalizedNumber}` appears with `status: "pending"`.
5. Open History — confirm the entry appears and status updates as the TTGO processes the job.
6. Confirm an account with an unverified phone cannot send at all (0 SMS/day).
7. Verify the phone — confirm quota rises to `assigned_quota` (default 10/day).

---

## Known Limitations

- **Background location is required for geofencing.** Declining it drops every place to the always-on WiFi mode, which still works and costs more battery. Google Play requires a declaration form and a demo video for this permission.
- **OTP delivery depends on the TTGO device** — if it is offline, the verification SMS waits until it comes back.
- **Geofence accuracy is the phone's, not the app's.** A position captured from a poor fix places the circle badly; the capture screen shows the accuracy for exactly this reason, and the WiFi rescue check exists as the net under it.
- **A queued gateway message cannot be withdrawn.** Account deletion erases everything else, but a job already handed to the device stays in its queue until the hardware finishes with it.
- **No crash reporting yet.** See [`docs/PLAY-RELEASE.md`](docs/PLAY-RELEASE.md).

---

## Release

- Publishing checklist and console steps: [`docs/PLAY-RELEASE.md`](docs/PLAY-RELEASE.md)
- Readiness audit and what is still open: [`docs/PLAY-READINESS.md`](docs/PLAY-READINESS.md)
- Hybrid detection design and the diagnosis behind it: [`docs/V4-GEOFENCE-HYBRID.md`](docs/V4-GEOFENCE-HYBRID.md)
- Privacy policy source: [`policy/public/index.html`](policy/public/index.html), published at <https://spotsire-policy.innovorix.com/>

---

## Contributing

- Branch naming: `feat/`, `fix/`, `docs/` prefixes
- Commit style: `feat(scope): short description`
- Schema changes → update `docs/FIREBASE-SCHEMA.md` in the same commit
- All Firestore paths → `local.properties` + `Constants.kt`, never hardcoded in business logic
