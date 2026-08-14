# WhatsApp Integration

Spotwire talks to a self-hosted baileys gateway for two separate things, and they
use two separate credentials on purpose.

## 1. Proving a phone number, before the user has anything of their own

A brand new account has no gateway credential, so the app carries one that the
gateway scopes to verification alone. It lives in Firestore at
`app_config/whatsapp` (`verify_key_id`, `verify_key_secret`), read at run time by
a signed-in user, so it can be rotated from the console without a release and is
never compiled into the installed file.

WhatsApp does not let a business message a stranger first, so the person messages
us and that message is the permission:

1. `GET /v1/verify/target` gives the app the number to message, the exact words to
   send, and a `wa.me` link with both already filled in. Nothing about the wording
   is hardcoded in the app.
2. The app opens WhatsApp on that link. The person presses send.
3. `POST /v1/verify/opt-in` is polled every three seconds for two minutes, and
   answers a plain yes or no. It never returns the message text.
4. `POST /v1/verify/send-code` sends a six digit code, and refuses when the number
   has not opted in. That refusal is what makes an extracted copy of this
   credential worthless: it can only reach somebody who just asked to be reached.
5. `POST /v1/verify/check-code` checks the typed code. The code itself never
   touches this app or its database; the gateway keeps only a hash and counts the
   wrong guesses.

**If the gateway does not answer**, the screen says so and offers a Pakistani
number the old code by text through the TTGO device. Everyone else confirms the
account by email instead, and gets their number proven for free at step 2 below.

## 2. Sending, on the user's own gateway key

Every message a user sends leaves from a WhatsApp number that user linked
themselves. There is no shared sender any more.

1. Sign up at the portal with an email and confirm the emailed code.
2. Link a WhatsApp number there by QR.
3. Create an API key. The portal shows a **key id** (`wak_` + 12 hex) and a
   **secret** (`was_` + 48 hex), the secret once only.
4. Paste both into the app and tap Connect.

The app **validates before it saves**: it calls `GET /v1/sessions` with the pasted
pair and stores nothing unless the gateway accepts it and names a WhatsApp number.
A typo can never leave the app configured but broken.

Saved to the Firestore user document as `wa_key_id`, `wa_key_secret` and
`wa_session_id`, owner-only under the security rules, so the link follows the user
to a new phone.

**Connecting a key also proves the account's phone number.** Linking a number on
the portal means scanning a QR code with the WhatsApp on that handset, so when the
gateway reports the linked number back and it matches the number on the account,
that number is marked verified. This is how an account outside Pakistan gets a
verified number without a code ever being sent.

## Authentication

Both credentials are a pair of headers, `x-key-id` and `x-key-secret`. The older
single `x-api-key` scheme went with the shared sender. A `x-key-secret-expires`
response header means a rotated key's old secret is still being accepted and is
about to stop; the app logs it.

## Sending

- Sends go to `POST /v1/messages/send` with no session id, because a portal key is
  bound to one number. A `400` means the key has no binding, and the app retries
  against `POST /v1/messages/{sessionId}/send`.
- Recipient numbers are digits only including the country code, no `+`.
- `202` means queued, not delivered; the gateway paces sends to avoid a ban.
- Arrival notifications fall back to SMS when WhatsApp fails **and** the recipient
  is a Pakistani mobile, because that fallback runs on the one TTGO device.

## Health

`GET /health` needs no credential, which is what lets the app ask "is the service
even up" before an account has anything to authenticate with, and tell an outage
apart from a bad key. It is checked in the background when the verify screen opens
and from the Check gateway button on the WhatsApp settings page. A gateway that
also reports its WhatsApp link state has that shown too.

## Configuration

| Value | Compile-time default | Runtime override (no rebuild) |
|---|---|---|
| Gateway base URL | `WHATSAPP_SERVICE_URL` in `local.properties` | `wa_service_url` on `sim_module/device` |
| Portal address | `WHATSAPP_PORTAL_URL` in `local.properties` | `wa_portal_url` on `sim_module/device` |

Both are public addresses, resolved by `WaConfigProvider` with a five-minute
cache: edit the Firestore field and every installed app follows within minutes,
with no release. The compiled value is only the offline first-run fallback.

**No gateway credential is compiled in.** The verification credential is fetched
from `app_config/whatsapp`, which every signed-in user can read, so nothing with
wider power may ever be put in that document. The old `wa_sso_secret` field on
`sim_module/device` must be deleted from the console and rotated on the gateway.
