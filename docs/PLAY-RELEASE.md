# Publishing Spotwire to Google Play

What the repository now does for you, and what only you can do in the console.

## Build the upload artifact

```
./run-build.sh bundleRelease
# -> app/build/outputs/bundle/release/app-release.aab
```

The bundle is signed with the upload key at `~/keystores/textgate-upload.jks`,
configured through the gitignored `keystore.properties` at the repository root.

**Back up both of those off this machine.** With Play App Signing a lost upload
key can be reset through Play support, but it costs days.

Upload key fingerprints (this is the key you sign with, not the one Google
serves to users):

- SHA-1 `5C:58:CD:44:4F:17:7A:56:01:E2:A1:C1:09:11:D6:64:C8:E2:E8:81`
- SHA-256 `DA:7B:18:72:EC:9F:42:C9:F5:51:E1:CD:81:F9:21:1F:DA:33:20:4E:66:B0:DD:70:9F:3F:BC:20:21:49:30:7F`

## Already handled in the repository

| Requirement | State |
|---|---|
| Target API level 36 | `app/build.gradle.kts`, ahead of the 31 Aug 2026 cutoff |
| Signed App Bundle | `signingConfigs`, keystore outside the repository |
| R8 keep rules | `app/proguard-rules.pro`, verified against the minified output |
| No credentials in the artifact | Mailer and SSO secret removed; artifact grepped |
| Database locked down | `firestore.rules`, deployed, 47 emulator assertions |
| Background location | **Declared and used.** Geofencing needs it, so the console declaration form and a demo video are required on every submission. See `docs/PLAY-READINESS.md` |
| Prominent disclosure | Shown before the location prompt, Settings screen |
| In-app account deletion | Profile, with a real erase of the stored data |
| Privacy policy | `docs/privacy-policy.html`, linked from Profile |

## What you have to do in the console

1. **Verify the developer account identity.** One-time, account level. Required
   before 30 September 2026.
2. **Create the app** in Play Console. The package name `com.spotwire.app` is
   registered automatically at creation, and Play App Signing registers the key,
   so the developer-verification email needs no separate action for this app.
3. **Host the privacy policy.** Enable GitHub Pages on this repository, serving
   from `main` and the `/docs` folder, which publishes
   `https://mhamidjamil.github.io/ttgo-sms-app/privacy-policy.html`. That exact
   URL is already linked inside the app, so if you host it anywhere else, change
   `PRIVACY_POLICY_URL` in `ProfileScreen.kt` to match. Play requires a live,
   public, non-PDF page.
4. **App content declarations**, all mandatory even when the answer is "none":
   privacy policy URL, Data safety, content rating questionnaire, health apps,
   financial features, and the target audience.
5. **Foreground service type**, declared in the console as `location`, with its
   own short video, and a short description: arrival detection reads nearby WiFi
   networks and confirms a geofence crossing to tell when the user reaches a
   saved place, and a permanent notification is shown while it runs.
6. **Background location declaration**, separate from the above and with its own
   demo video. The video must show, in the production build, the in-app
   disclosure BEFORE any system dialog, the accept, the system "Allow all the
   time" grant, and an arrival alert produced by a fence crossing.
7. **Account deletion URL.** Play wants a web address as well as the in-app
   path, because people who uninstalled cannot use the in-app one. The privacy
   policy page carries the instructions and the contact address.

## Data safety answers

Collected and linked to the user's identity: name, email address, phone number,
SMS message content and recipient numbers, and **precise location**. Answer
precise location as COLLECTED: a saved place stores the coordinates and radius
the user set for it, and every arrival record for a geofenced place carries
them. Answer "app functionality" as the purpose, and say the data is not shared
and not used for advertising.

Also collected as location: the WiFi identifiers the user captured at each saved
place, which Android treats as location data. They are stored on the user's own
account record.

Not collected: contacts, photos, files, browsing history, and any advertising or
analytics identifier. There are no third-party analytics or advertising
libraries in the build, and Firebase Analytics is deliberately not included.

Shared with third parties: message content and recipient numbers reach the
gateway that transmits them. Everything else stays in Firebase.

Say yes to encryption in transit, and yes to a way for users to request deletion.

## Still open, and deliberately so

- **The TTGO gateway signs in anonymously**, so the rules can only separate it
  from app traffic, not prove it is really the device. Anyone holding the public
  Firebase API key can mint a token with the same access. Closing it means
  giving the firmware a dedicated account: set `FIREBASE_USE_ANONYMOUS_DEFAULT`
  to 0 with an email and password in `secrets.h`, reflash, then put that uid in
  `deviceUids()` in `firestore.rules` and drop the anonymous clause. Note
  `ConfigManager.cpp` lets a saved config override the compile-time default, so
  clear it rather than trusting the flag alone.
- **The academia app shares this Firebase project** and its four collections are
  still world readable and writable, exactly as they were before. That is
  unchanged rather than newly broken, and fixing it belongs to that app.
- **The old `wa_sso_secret` field on `sim_module/device`** should be deleted from
  the console, and the secret rotated on the gateway. It was readable by every
  signed-in user for as long as it sat there.
- **No crash reporting.** A first Play release with no Crashlytics means day-one
  crashes surface as one-star reviews rather than stack traces.
