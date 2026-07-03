# WhatsApp Integration

TextGate can deliver **automatic arrival notifications via WhatsApp** instead of
SMS — free, no SMS quota — through a self-hosted baileys gateway service.

## How it works

The gateway is a separate multi-user service (Fastify + @whiskeysockets/baileys).
Each user registers **on the service's own dashboard** — not in this app — links
their WhatsApp by QR, and gets a **personal API key** (`wa_` + 48 hex chars).
The app only stores that key and calls the service with it.

```
TextGate app ──x-api-key──► baileys gateway ──WhatsApp Web──► recipient
```

## User flow (guided on the in-app WhatsApp screen)

1. Verify your **email** in TextGate (gate for WhatsApp settings).
2. Open the service URL in a browser, sign up (email + password), verify with
   the 6-digit emailed code.
3. On the dashboard: create a session (any name, e.g. `myphone`) and scan the QR
   with WhatsApp → Linked Devices.
4. Copy your API key and paste it (plus the session name) into
   **Profile → WhatsApp Settings**.
5. `Check Status` should show **connected**; `Send Test` messages your own
   verified number.

## Where WhatsApp is used

- **Arrival notifications**: when a key is linked, arrival messages to the
  guardian go via WhatsApp first and **fall back to the SMS gateway** if the
  send fails (unlinked, disconnected session, service down).
- Manual sends from the Send tab remain SMS-only.

## Technical notes

- Base URL: `WHATSAPP_SERVICE_URL` in `local.properties`
  (default `https://ww.innovorix.com`).
  **TODO(@dev): not permanent — load dynamically from Firebase (RTDB runtime
  settings or Remote Config) so URL changes don't require an app rebuild.**
- Auth header: `x-api-key: <key>` (no Bearer prefix).
- Send: `POST /v1/messages/{sessionId}/send` with
  `{"phoneNumber":"923001234567","message":"…","recipientName":"…"}` —
  digits only, **no `+`**. A `202` means *queued*: the gateway paces deliveries
  5–15 s apart (its own anti-ban) and provides no per-message receipt.
- Status: `GET /v1/sessions/{sessionId}/status` → `connecting | qr_ready |
  connected | disconnected`. Only `connected` is sendable.
- Error mapping: 401 invalid/rotated key · 403 session owned by another account ·
  404 session never linked · 503 session not connected.
- The API key is stored in local DataStore only — never in Firestore.
