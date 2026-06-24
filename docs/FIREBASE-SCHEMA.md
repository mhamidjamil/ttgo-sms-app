# Firebase Schema

All collections live inside the `sim_module/` root, shared with the TTGO T-Call v8 firmware.

---

## Firestore

### `sim_module/device` (existing — app reads only)

| Field | Type | Written by | Description |
|-------|------|-----------|-------------|
| `free_sms_quota` | int | TTGO dashboard | Default assigned quota for new users |
| `active` | bool | TTGO dashboard | Master send switch |
| `blocked*` | array | TTGO dashboard | Block lists |

The app reads `free_sms_quota` once at sign-up and stores the value in the user doc. It never writes to this document.

---

### `sim_module/sms/sms_jobs/{phone_number}` (shared with TTGO)

Doc ID = E.164 phone number. One active job per number (device constraint).

| Field | Type | Written by | Description |
|-------|------|-----------|-------------|
| `message` | string | app | SMS body |
| `status` | string | both | `pending` → `in_progress` → `sent` / `failed` / `blocked` |
| `enque_by` | string | app | `"app:{uid}"` for regular SMS; `"app:{uid}:otp"` for verification |

The app also reads `status` during history polling to update `users/{uid}/history/`.

---

### `sim_module/users/{uid}` (app writes)

One document per Firebase Auth user, keyed by UID.

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `email` | string | — | Email address from Auth |
| `name` | string | — | Display name from sign-up |
| `email_verified` | bool | `false` | Synced from Firebase Auth on each login |
| `phone_number` | string | `""` | Pakistani mobile in E.164 (`+923XXXXXXXXX`) |
| `phone_verified` | bool | `false` | Set to `true` after OTP confirmation |
| `phone_otp` | string | (deleted on verify) | Stored 6-digit OTP; field removed once verified |
| `assigned_quota` | int | from device | Full daily SMS allowance (copied from `free_sms_quota`) |
| `remaining_quota` | int | = assigned | Decremented per send; reset to `assigned_quota` each new day |
| `last_quota_reset_date` | string | today | `"YYYY-MM-DD"` — compared to today on app open to detect a new day |
| `created_at` | timestamp | — | Server timestamp set at sign-up |

**Effective quota** (computed in app, never stored):
- `email_verified && phone_verified` → `assigned_quota`
- `email_verified || phone_verified` → `PARTIAL_VERIFIED_QUOTA` (env, default 4)
- neither → `UNVERIFIED_QUOTA` (env, default 2)

---

### `sim_module/users/{uid}/history/{autoId}` (app writes)

Per-user history of sent messages. Doc ID is Firestore auto-ID.

| Field | Type | Description |
|-------|------|-------------|
| `phone_number` | string | Normalized E.164 recipient |
| `message` | string | SMS body |
| `status` | string | Mirrors `sms_jobs` status; updated by app polling |
| `enqueued_at` | timestamp | When the job was enqueued |
| `job_phone_key` | string | = `phone_number`; used to look up the job in `sms_jobs` |
| `enque_by` | string | `"app:{uid}"` — cross-checked during polling to detect job overwrites |

**Job-collision handling:** If another user sends to the same number, `sms_jobs/{phone}` is overwritten. When the app polls and finds `enque_by != "app:{uid}"`, it marks the history entry as `failed` (or leaves it `pending` until the device processes the new job). History is never overwritten — it is always the user's own record.

---

### `sim_module/users/{uid}/auto_history/{autoId}` (V2)

Arrival-triggered jobs.

| Field | Type | Description |
|-------|------|-------------|
| `location` | string | `"office"` or `"home"` |
| `sent_at` | timestamp | When the arrival SMS was enqueued |
| `status` | string | Same status values as history |
| `job_phone_key` | string | Phone number of the guardian |
| `message` | string | `"{name} arrived at {label} N minutes ago"` |
| `routine_triggered` | bool | `true` if routine learning reduced the wait |

---

### V2 user doc additions

| Field | Type | Description |
|-------|------|-------------|
| `guardian_number` | string | E.164 guardian phone for arrival alerts |
| `home_bssid` | string | `"AA:BB:CC:DD:EE:FF"` — WiFi MAC for home |
| `home_label` | string | Human label shown in the alert (e.g. `"Home"`) |
| `office_bssid` | string | WiFi MAC for office |
| `office_label` | string | Human label (e.g. `"Office"`) |
| `wifi_stability_minutes` | int | Per-user override of the stability timer |
| `arrival_home_times` | string[] | Last 30 arrival HH:mm strings (oldest rotated out) |
| `arrival_office_times` | string[] | Same for office |
| `last_home_arrival_date` | string | `"YYYY-MM-DD"` cooldown guard |
| `last_office_arrival_date` | string | Same for office |

---

## Realtime Database (RTDB)

The app does **not** write to RTDB. RTDB is used exclusively by the TTGO firmware for rate-limit counters, telemetry, and runtime settings (`/ttgo_tcall/settings/runtime`).

---

## Security Rules (recommended)

```js
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    // Users can only read/write their own document
    match /sim_module/users/{uid}/{document=**} {
      allow read, write: if request.auth != null && request.auth.uid == uid;
    }
    // SMS jobs: any authenticated user can write (to enqueue); reads allowed too
    match /sim_module/sms/sms_jobs/{phone} {
      allow read, write: if request.auth != null;
    }
    // Device doc: read-only for app users
    match /sim_module/device {
      allow read: if request.auth != null;
      allow write: if false; // TTGO service account only
    }
  }
}
```
