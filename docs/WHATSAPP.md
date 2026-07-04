# WhatsApp Integration (SSO)

TextGate delivers arrival notifications via WhatsApp through a self-hosted baileys
gateway — with **zero manual setup** for the user.

## How accounts work (SSO)

The user never registers on the gateway. Once they verify their **phone** and
**email** in TextGate, the app automatically:

1. Health-checks the gateway (`GET /health`). If it is down/5xx the flow stops with
   *"WhatsApp service is currently under maintenance. Please try again in a few hours."*
2. Calls `POST /sso/provision` (authenticated with the rotatable `x-service-key`
   secret) — the gateway creates a **pre-verified** account and claims a session id
   equal to the user's **verified phone digits** (`923001234567`).
3. Stores the returned personal API key + session id on the Firestore user doc
   (`wa_api_key`, `wa_session_id` — owner-only via security rules) with a local
   DataStore cache.

This runs in the background right after either verification completes, and again
whenever the WhatsApp screen opens.

## Two sending modes (`wa_mode`)

| Mode | What it means | Setup |
|---|---|---|
| `shared` (**default**) | Messages come from the app's shared WhatsApp number | None — works immediately |
| `own` | Messages come from the user's own WhatsApp | One-time QR scan, done **inside the app** |

**"Use My WhatsApp" flow:** the app starts the phone-derived session, polls
`GET /v1/sessions/{id}/qr`, renders the QR **in-app** (base64 PNG), and polls status
until `connected`. No dashboard, no session names, no key pasting.

Arrival notifications route through the active mode and **fall back to SMS**
automatically when WhatsApp is unavailable.

## Configuration & rotation

| Value | Compile-time default | Runtime override (no rebuild) |
|---|---|---|
| Gateway base URL | `WHATSAPP_SERVICE_URL` in `local.properties` | `wa_service_url` on `sim_module/device` |
| SSO secret | `WHATSAPP_SSO_SECRET` in `local.properties` | `wa_sso_secret` on `sim_module/device` |

To rotate the secret: change `SSO_SERVICE_SECRET` in the gateway `.env` **and** the
Firestore `wa_sso_secret` field. Installed apps pick it up within ~5 minutes.

## Technical notes

- Auth header for user calls: `x-api-key` (personal `wa_` key). Provisioning uses
  `x-service-key` (the SSO secret) — never a user key.
- Shared sends: `POST /v1/messages/shared/send` (+ `GET /v1/messages/shared/status`).
  Own sends: `POST /v1/messages/{sessionId}/send`.
- Recipient numbers are digits-only incl. country code, **no `+`**.
- `202` = queued; the gateway paces deliveries 5–15 s apart (anti-ban); no
  per-message receipt.
- The admin links the shared number once: gateway dashboard → log in with the
  service API key → connect session `shared` → scan QR.
- Gateway-side reference: `sendoso_test/docs/baileys-integration-guide.md`.
