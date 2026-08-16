# WhatsApp Integration

Every user connects their own WhatsApp gateway. The app ships no gateway
credential of any kind, and there is no shared sender.

## Connecting

1. Sign up at the portal with an email and confirm the emailed code.
2. Link a WhatsApp number there by QR.
3. Create an API key. The portal shows a **key id** (`wak_` + 12 hex) and a
   **secret** (`was_` + 48 hex), the secret once only.
4. Paste both into the app and tap Connect.

The app **validates before it saves**: it calls `GET /v1/sessions` with the
pasted pair and stores nothing unless the gateway accepts it and names a WhatsApp
number. A typo can never leave the app configured but broken.

Saved to the Firestore user document as `wa_key_id`, `wa_key_secret` and
`wa_session_id`, owner-only under the security rules, so the link follows the
user to a new phone. The secret is masked in the field and excluded from cloud
backup.

## What verification does NOT use

Phone numbers are verified by a code sent as a text through the TTGO device, and
nothing else. The gateway plays no part in it. An earlier design had the app
carry a shared verification credential; that is gone, and no document in Firestore
holds a gateway credential that is not the user's own.

## Authentication

A pair of headers, `x-key-id` and `x-key-secret`. The older single `x-api-key`
scheme went with the shared sender. A `x-key-secret-expires` response header
means a rotated key's old secret is still being accepted and is about to stop;
the app logs it.

## Sending

- Sends go to `POST /v1/messages/send` with no session id, because a portal key is
  bound to one number. A `400` means the key has no binding, and the app retries
  against `POST /v1/messages/{sessionId}/send`.
- Recipient numbers are digits only including the country code, no `+`.
- `202` means queued, not delivered. The app keeps the returned message id and
  reads the real status back from `GET /v1/messages/recent`, so a queued message
  that later failed is shown as failed rather than as sent.
- Arrival notifications fall back to SMS when WhatsApp fails **and** the recipient
  is a Pakistani mobile, because that fallback runs on the one TTGO device.

## Health

`GET /health` needs no credential, so the app can tell "the service is down" apart
from "your key is wrong". It is behind the Check gateway button on the WhatsApp
settings page.

## Configuration

| Value | Compile-time default | Runtime override (no rebuild) |
|---|---|---|
| Gateway base URL | `WHATSAPP_SERVICE_URL` in `local.properties` | `wa_service_url` on `sim_module/device` |
| Portal address | `WHATSAPP_PORTAL_URL` in `local.properties` | `wa_portal_url` on `sim_module/device` |
| Invite link | `APP_SHARE_URL` in `local.properties` | `app_share_url` on `sim_module/device` |

All three are public addresses, resolved by `WaConfigProvider` with a five-minute
cache: edit the Firestore field and every installed app follows within minutes,
with no release. The compiled value is only the offline first-run fallback.

The old `wa_sso_secret` field on `sim_module/device` should still be deleted from
the console and rotated on the gateway; nothing reads it.
