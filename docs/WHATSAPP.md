# WhatsApp Integration

TextGate delivers arrival notifications over WhatsApp through a self-hosted
baileys gateway (`WhatsApp Gateway 3.0.0`). There are two ways to get a
credential, and the app takes whichever it can.

## 1. Automatic (SSO), when the gateway offers it

Once the user has verified **phone** and **email**, the app health-checks the
gateway and calls `POST /sso/provision`, which returns a personal API key and a
session id derived from the verified phone digits. These land on the Firestore
user document (`wa_api_key`, `wa_session_id`).

**This path is currently unavailable.** The gateway answers
`503 {"error":"SSO provisioning is not configured on this server"}` because its
`SSO_SERVICE_SECRET` is unset. The server checks its own configuration *before*
it looks at any header, so the app deliberately sends **no** service secret: it
would not change the answer, and a secret compiled into a public app is a public
secret. The gateway's own wording is shown to the user, with the manual route
offered underneath.

## 2. Manual: the user's own gateway key (the path that works today)

The WhatsApp screen offers **Use your own WhatsApp gateway**, which opens the
portal and takes the two values it issues:

1. Sign up at the portal with an email, confirm the emailed code.
2. Link a WhatsApp number there by QR.
3. Create an API key. The portal shows a **key id** (`wak_` + 12 hex) and a
   **secret** (`was_` + 48 hex). The secret is displayed once and only its
   digest is stored, so it cannot be looked up again; a lost secret means
   rotating the key.
4. Paste both into the app and tap Connect.

The app **validates before it saves**: it calls `GET /v1/sessions` with the
pasted pair, and stores nothing unless the gateway accepts it and names a
WhatsApp number. A typo can never leave the app configured but broken.

Saved to the Firestore user document as `wa_key_id`, `wa_key_secret` and
`wa_session_id`, owner-only under the security rules, so the link follows the
user to a new phone.

## Authentication

| Scheme | Headers | Notes |
|---|---|---|
| Pair (portal key) | `x-key-id` + `x-key-secret` | Bound to one number at creation |
| Legacy single (SSO) | `x-api-key` | Unbound; sunset by the gateway in 2027 |

The gateway consults **only one**: when a key id is present the single key is
never looked at, so the app sends exactly one scheme. A `x-key-secret-expires`
response header means a rotated key's old secret is still being accepted and is
about to stop; the app logs it.

## Sending

- A portal key is tied to a number, so sends go to `POST /v1/messages/send` with
  no session id. A `400` back means the key has no binding, and the app retries
  against `POST /v1/messages/{sessionId}/send`.
- **The shared sender does not work with a portal key.** `/v1/messages/shared/send`
  still runs the key-binding check, and a portal key is bound to the user's own
  number, so it answers `403 This API key may only send from "<session>"`. The
  shared/own mode choice is therefore hidden once a portal key is connected, and
  sending always goes through the user's own number.
- Recipient numbers are digits only including the country code, no `+`.
- `202` means queued, not delivered; the gateway paces sends to avoid a ban.
- Arrival notifications fall back to SMS automatically when WhatsApp fails.

## Configuration

| Value | Compile-time default | Runtime override (no rebuild) |
|---|---|---|
| Gateway base URL | `WHATSAPP_SERVICE_URL` in `local.properties` | `wa_service_url` on `sim_module/device` |
| Portal address | `WHATSAPP_PORTAL_URL` in `local.properties` | `wa_portal_url` on `sim_module/device` |

Both are public addresses, resolved by `WaConfigProvider` with a five-minute
cache: edit the Firestore field and every installed app follows within minutes,
with no release. The compiled value is only the offline first-run fallback.

**No gateway credential is compiled in, and none lives on the shared device
document.** A Firestore read grant is per document and never per field, so
anything on `sim_module/device` is readable by every signed-in user. The old
`wa_sso_secret` field there must be deleted.
