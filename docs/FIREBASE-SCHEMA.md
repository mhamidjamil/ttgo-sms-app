# Firebase Schema

The TTGO firmware uses `sim_module/` in Firestore. The app adds its own top-level collection `ttgo_users` (outside `sim_module`) so the two namespaces never conflict.

---

## Firestore

### `sim_module/device` (existing — app reads only)

| Field | Type | Written by | Description |
|-------|------|-----------|-------------|
| `free_sms_quota` | int | TTGO dashboard | Default assigned quota for new users |
| `wa_service_url` | string | admin | Optional override of the WhatsApp gateway base URL (rotation without app rebuild) |
| `wa_sso_secret` | string | admin | Optional override of the SSO provisioning secret (must match the gateway's `SSO_SERVICE_SECRET`) |
| `active` | bool | TTGO dashboard | Master send switch |
| `blocked*` | array | TTGO dashboard | Block lists |

The app reads `free_sms_quota` once at sign-up and stores the value in the user doc. It never writes to this document.

---

### `sim_module/sms/sms_jobs/{phone_number}` (shared with TTGO)

Doc ID = E.164 phone number. One active job per number (device constraint).

| Field | Type | Written by | Description |
|-------|------|-----------|-------------|
| `phone_number` | string | app | E.164 recipient (same value as the doc ID) |
| `message` | string | app | SMS body |
| `status` | string | both | `pending` → `in_progress` → `sent` / `failed` / `blocked` |
| `enque_by` | string | app | `"app:{uid}"` for regular SMS; `"app:{uid}:otp"` for verification |
| `kind` | string | app | `"otp"` on verification jobs — the device processes these BEFORE regular jobs, sends immediately (no anti-ban gap), and bypasses the SIM-package-expired gate |
| `created_at` | timestamp | app | Server timestamp at enqueue |

The app also reads `status` during history polling to update `ttgo_users/{uid}/history/`.

---

### `ttgo_users/{uid}` (app writes)

Top-level collection — one document per Firebase Auth user, keyed by UID.

> **Why top-level?** Firestore collection paths must have an odd number of segments (1, 3, 5…). A path like `sim_module/ttgo_users` has 2 segments and would be a *document* reference, not a collection. Keeping users at the root as `ttgo_users` is the simplest valid choice and avoids collision with any existing `users` collection.

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `email` | string | — | Email address from Auth |
| `name` | string | — | Display name from sign-up |
| `email_verified` | bool | `false` | Set to `true` after email OTP confirmation (in-app). A legacy Firebase-link verification can also upgrade it to `true`; it is never downgraded |
| `email_otp` | string | (deleted on verify) | Stored 6-digit email OTP (delivered over SMTP); field removed once verified |
| `email_otp_created_at` | timestamp | (deleted on verify) | When the email OTP was issued — codes expire after **1 hour** |
| `phone_number` | string | `""` | Pakistani mobile in E.164 (`+923XXXXXXXXX`) |
| `phone_verified` | bool | `false` | Set to `true` after OTP confirmation |
| `phone_otp` | string | (deleted on verify) | Stored 6-digit OTP; field removed once verified |
| `phone_otp_created_at` | timestamp | (deleted on verify) | When the OTP was issued — codes expire after **1 hour** |
| `assigned_quota` | int | from device | Full daily SMS allowance (copied from `free_sms_quota`) |
| `remaining_quota` | int | = assigned | Decremented per send; reset to `assigned_quota` each new day |
| `last_quota_reset_date` | string | today | `"YYYY-MM-DD"` — compared to today on app open to detect a new day |
| `created_at` | timestamp | — | Server timestamp set at sign-up |
| `wa_api_key` | string | `""` | Personal WhatsApp-gateway API key, auto-provisioned via SSO once phone + email are verified (owner-only via rules) |
| `wa_session_id` | string | `""` | Gateway session id = the user's verified phone digits (e.g. `923001234567`) |
| `wa_mode` | string | `"shared"` | WhatsApp sending mode: `shared` (app's number) or `own` (user-linked WhatsApp) |

**Effective quota** (computed in app, never stored):
- `phone_verified` → `assigned_quota` (default 10/day from the device doc)
- phone NOT verified → **0** (sending disabled — messages carry a "Sent by
  <number>" signature, so an unverified sender identity is never allowed)
- `email_verified` does not affect the SMS quota (needed only for WhatsApp
  linking and admin contact)

**Auto-creation:** If a user logs in but their Firestore doc is missing, the app creates it automatically (quota sourced from `sim_module/device/free_sms_quota`).

**V2 dynamic places** (arrival monitoring):

| Field | Type | Description |
|-------|------|-------------|
| `guardian_number` | string | E.164 default recipient of arrival notifications |
| `places` | array | `[{id, label, bssid, message, wa_message, contacts}]` — `home`/`office` are seeded ids; users add more. `message` is an optional custom arrival text (blank → "<name> arrived at <label>"); `contacts` is `[{name, number}]` — the people notified for that place (empty → the default `guardian_number`); `wa_message` is an optional WhatsApp-specific text (blank → same as `message`). Legacy `recipients` (bare number arrays) and `guardian_numbers` migrate on read |
| `arrival_times` | map | `{placeId: ["HH:mm", …]}` — last 30 arrivals per place (routine learning) |
| `last_arrival_dates` | map | `{placeId: "YYYY-MM-DD"}` — one-notification-per-day guard per place |

Legacy fixed fields (`home_bssid`, `home_label`, `office_bssid`, `office_label`,
`arrival_home_times`, `arrival_office_times`, `last_home_arrival_date`,
`last_office_arrival_date`) are still read for MIGRATION only: when `places` is
absent they seed the home/office entries; the next save writes `places` and the
legacy fields stop mattering.

---

### `ttgo_users/{uid}/history/{autoId}` (app writes)

Per-user history of sent messages. Doc ID is Firestore auto-ID.

| Field | Type | Description |
|-------|------|-------------|
| `phone_number` | string | Normalized E.164 recipient |
| `message` | string | SMS body |
| `status` | string | Mirrors `sms_jobs` status; updated by app polling |
| `enqueued_at` | timestamp | When the job was enqueued |
| `job_phone_key` | string | = `phone_number`; used to look up the job in `sms_jobs` |
| `enque_by` | string | `"app:{uid}"` — cross-checked during polling to detect job overwrites |

**Job-collision handling:** If another user sends to the same number, `sms_jobs/{phone}` is overwritten. When the app polls and finds `enque_by != "app:{uid}"`, it marks the history entry as `failed`. History is never overwritten — it is always the user's own record.

---

### `ttgo_users/{uid}/auto_history/{autoId}` (V2)

Arrival-triggered jobs.

| Field | Type | Description |
|-------|------|-------------|
| `location` | string | Place id (`"home"`, `"office"`, or a custom `place_*` id) |
| `sent_at` | timestamp | When the arrival SMS was enqueued |
| `status` | string | Same status values as history |
| `job_phone_key` | string | Phone number of the guardian |
| `message` | string | `"{name} arrived at {label} N minutes ago"` |
| `routine_triggered` | bool | `true` if routine learning reduced the wait |

---

### `ttgo_users/{uid}/settings_history/{autoId}`

Audit trail of settings edits, so a value that disappears on its own can be
traced to whatever wrote it. Newest 200 entries are shown in the app.

| Field | Type | Description |
|-------|------|-------------|
| `field` | string | Human label, e.g. `"Guardian number"`, `"Home WiFi"`, `"Arrival monitoring"` |
| `old_value` | string | Value before the change (`""` when it was unset) |
| `new_value` | string | Value after the change |
| `changed_at` | timestamp | When the change was written |

Recorded for: the guardian number, per-place name / BSSID / message / contacts,
places added and removed, the arrival-monitoring switch, the phone number, and
the phone/email verification flags.

---

### `ttgo_users/{uid}/links/{otherUid}`

One side of a linked-account pairing. Each user keeps their own document for the
same pairing, so revoking is always a write to your own side.

| Field | Type | Description |
|-------|------|-------------|
| `other_name` | string | Display name of the other account |
| `other_phone` | string | E.164 number of the other account |
| `state` | string | `pending_outgoing` (I invited them) / `pending_incoming` (they invited me) / `active` / `declined` |
| `perm_auto_updates` | bool | They receive MY arrival alerts automatically |
| `perm_request_location` | bool | They may ask where I am right now |

Permissions on my document describe what the other person may do with **my**
location. A link does nothing until both sides are `active`.

---

### `ttgo_users/{uid}/location_requests/{autoId}`

On-demand "where are you?" asks, written by the requester into the **target's**
subcollection and answered by the target's device.

| Field | Type | Description |
|-------|------|-------------|
| `requester_uid` | string | Who is asking |
| `requester_name` | string | Their display name, for the target's records |
| `status` | string | `pending` → `answered` / `denied` |
| `answer` | string | Human-readable place name only, e.g. `"Hamid is at Office."` — never a BSSID, never coordinates |
| `created_at` | timestamp | When the request was made |
| `answered_at` | timestamp | When the target's device replied |

Answered by `ArrivalService` while monitoring is on, and by `MainActivity` when
the app is opened. A request with no permission behind it is set to `denied`
rather than ignored, so the asker's screen stops waiting.

---

### `alert_subscriptions/{recipient_phone}/senders/{senderUid}` (top-level)

Recipient-owned control over automated location alerts. Keyed by the recipient's
E.164 number rather than their uid, so the record can be created long before that
person installs the app and found again as soon as they verify the same number.

| Field | Type | Written by | Description |
|-------|------|-----------|-------------|
| `sender_name` | string | sender | Display name shown to the recipient |
| `sender_phone` | string | sender | Sender's verified number, used for the unsubscribe notice |
| `subscribed` | bool | recipient | Absent until the recipient makes a choice; **absent reads as subscribed** |
| `last_alert_at` | timestamp | sender | Most recent alert sent to this number |

Delivery is skipped only on an explicit `subscribed: false`. On unsubscribe the
recipient's app enqueues a courtesy SMS back to the sender
(`enque_by = "app:{recipientUid}:unsub"`, no history entry, no quota change) and
the record is deactivated rather than deleted.

---

### `phone_directory/{phone_number}` (top-level)

Minimal public lookup table so a link invite can find an account by its verified
number without opening up the user documents.

| Field | Type | Description |
|-------|------|-------------|
| `uid` | string | Account that verified this number |
| `name` | string | Display name |

Written once, when the phone OTP is confirmed. It deliberately carries nothing
else, so `ttgo_users/{uid}` can stay owner-only.

---

## Realtime Database (RTDB)

The app does **not** write to RTDB. RTDB is used exclusively by the TTGO firmware for rate-limit counters, telemetry, and runtime settings (`/ttgo_tcall/settings/runtime`).

---

## Security Rules (recommended)

> **These must be updated in the Firebase console** for linked accounts and
> recipient-managed alerts to work. Linking and on-demand location requests are
> cross-user by design, so the blanket owner-only rule on `ttgo_users/**` blocks
> them. The two carve-outs below are deliberately narrow: the other party can
> only touch the single document that names them.

```js
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    // Linked accounts: my own side is mine, and the person a link names may
    // write ONLY that one document (to accept, decline, or remove the pairing).
    match /ttgo_users/{uid}/links/{otherUid} {
      allow read: if request.auth != null && request.auth.uid == uid;
      allow write: if request.auth != null &&
        (request.auth.uid == uid || request.auth.uid == otherUid);
    }

    // On-demand location requests live in the TARGET's subcollection. The target
    // reads and answers them; a linked account may create one and read back only
    // its own request.
    match /ttgo_users/{uid}/location_requests/{requestId} {
      allow read, update, delete: if request.auth != null &&
        (request.auth.uid == uid || resource.data.requester_uid == request.auth.uid);
      allow create: if request.auth != null &&
        request.resource.data.requester_uid == request.auth.uid;
    }

    // Everything else under a user document stays owner-only. Keep this AFTER
    // the two rules above; Firestore grants access if any rule matches.
    match /ttgo_users/{uid}/{document=**} {
      allow read, write: if request.auth != null && request.auth.uid == uid;
    }

    // Alert subscriptions are keyed by phone number, not uid, because the record
    // is created before the recipient has an account. Any signed-in user may
    // write (senders record the relationship, recipients set `subscribed`).
    match /alert_subscriptions/{phone}/senders/{senderUid} {
      allow read, write: if request.auth != null;
    }

    // Public number-to-account lookup for link invites. Carries only uid + name.
    match /phone_directory/{phone} {
      allow read: if request.auth != null;
      allow write: if request.auth != null &&
        request.resource.data.uid == request.auth.uid;
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

**Known limitation:** `alert_subscriptions` is writable by any signed-in user, so
one user could in principle flip another recipient's flag. Tightening that needs
a verified-phone claim (a Cloud Function setting a custom claim at OTP time),
which this app does not have yet.
