# Play Store readiness for Spotwire, 11 August 2026

Audit of build 1.6.1 (versionCode 10, `com.spotwire.app`) by four independent
reviewers, every blocking finding then checked by a second reviewer trying to
disprove it. Four claims were disproved and are recorded at the bottom so they
do not get re-raised.

**Verdict: the build is ready, the code is now ready, the paperwork is not.**
A signed bundle comes out clean. Everything that needed a code change was fixed
on 11 August 2026 (see below). What remains is entirely console and hosting
work that only the account holder can do: the privacy policy page is still
offline, and two Play declarations that did not apply before this build now do.

---

## What is already right (verified, not assumed)

- `./run-build.sh bundleRelease` succeeds and produces a genuinely **signed**
  6.65 MB bundle. Signature verified with `jarsigner` and `apksigner`
  (SHA256withRSA, 2048-bit, valid to 2053, well past Play's 2033 floor).
- The artifact carries the rename: `com.spotwire.app`, versionCode 10,
  versionName 1.6.1, label **Spotwire**, targetSdk 36, minSdk 26. API 36 meets
  the floor that arrives on 31 August 2026.
- R8 minification, obfuscation and resource shrinking are all genuinely on, and
  the reflection surfaces survive: every Firestore DTO class, every snake_case
  field string, generic signatures, and the `PropertyName`/`DocumentId`
  annotations are intact in the shipped dex. Verified in the artifact, not from
  the keep rules. The obfuscation mapping is embedded so Play deobfuscates
  crashes automatically.
- **Secret scan clean.** Nothing but the expected Firebase configuration values,
  the public gateway URL and Firestore path names. No password, no SMTP, no
  private key, no `AIza` string outside the resource table.
- No native libraries and no bundled assets, so the 16 KB page-size requirement
  is satisfied for free.
- `lintVitalRelease` reports no issues.

---

## Blockers that needed code changes: ALL FIXED 11 August 2026

Commits `1015723`, `a2e5f46`, `dcc6969`. The Firestore rules change that lets
the deletion flow work was deployed to `textgate-344f2` on the same day. What
each one was, and what it now does, is below.

### 1. FIXED: the privacy policy and the in-app disclosure contradicted the code
The app tells the user, in two places, that WiFi identifiers never leave the
phone and that it does not use GPS coordinates:
- `app/src/main/java/com/spotwire/app/presentation/settings/SettingsScreen.kt:1556-1561`
- `docs/privacy-policy.html:54` and `:80-81`

Both are false as of the geofence work. `FirestoreDataSource.kt:247-264` uploads
`bssid`, `bssids`, `latitude`, `longitude` and `radius_m` to the user's own
Firestore document, and arrival history rows carry the coordinates. The place
editor takes a `PRIORITY_HIGH_ACCURACY` fix.

A prominent disclosure that misstates what is collected fails the
background-location policy by itself, and it will not match the Data safety
form, which is an enforcement mismatch rather than a request for changes.

**Done:** both now state that a saved place stores its WiFi identifiers and, if
set, its position and radius, on the user's own account record, and that arrival
records carry the position. Recipients are told the place name and time only.

### 2. FIXED: data safety answers in `docs/PLAY-RELEASE.md` were wrong
The draft said precise location was not collected. The table now says COLLECTED,
purpose app functionality, not shared, not used for advertising. Fill the console
form from it.

### 3. FIXED: `docs/PLAY-RELEASE.md` said background location was removed
It is back, at `AndroidManifest.xml:28`, and it is load-bearing:
`GeofenceManager.kt:38-41` registers no fence without it. That row will lead to
the console questionnaire being answered incorrectly.

### 4. FIXED: geofences survived sign-out and account deletion
`ProfileScreen.kt:68` (delete) and `:76` (sign out) call only
`ArrivalService.stop`. Fences are registered `NEVER_EXPIRE` and live inside Play
services, so an ex-user crossing a saved place still wakes a location-typed
foreground service. Background location continuing after account deletion is
exactly what the policy forbids.

**Done:** both handlers now go through one teardown that clears the fences,
cancels the watchdog alarm and stops the service.

### 5. FIXED: account deletion did not delete what the policy promised
`privacy-policy.html:113-117` promises the profile, history, places, links and
WhatsApp settings are erased. Four things survive: every `sms_jobs` document
(carrying phone number and message text, undeletable by rule
`firestore.rules:150`), the `alert_subscriptions` sender record, the reciprocal
link document on the other person's account, and the local `monitor_log.jsonl`.

**Done:** deletion now removes the reciprocal link on the other person's record,
every alert-subscription breadcrumb carrying this user's name and number, and the
on-device activity log (which sign-out clears too). The rules were changed so a
sender may delete their own breadcrumb, and deployed. For `sms_jobs` the policy
now states plainly that a message already handed to the gateway stays in its
queue until the device is done with it, because the app must not reach into the
queue the firmware reads.

---

## Blockers only Hamid can clear (console and hosting)

1. **The privacy policy URL is dead.** The app links
   `https://mhamidjamil.github.io/ttgo-sms-app/privacy-policy.html`
   (`ProfileScreen.kt:37`), which returns 404: GitHub Pages has never been
   enabled on that repository. Play rejects a dead policy URL before review, and
   the same page is the account-deletion web address. Enable Pages on branch
   `main`, folder `/docs`.
2. **Background location declaration plus demo video.** In App content →
   Sensitive app permissions → Location. The video must show, in the production
   build: the in-app disclosure *before* any system dialog, the accept, the
   system "Allow all the time" grant, and an arrival alert produced by a fence
   crossing.
3. **Foreground service declaration for the `location` type,** with its own
   short video, separate from the one above.
4. **Create the app in Play Console under `com.spotwire.app`** and upload one
   bundle by hand. The Play API cannot create a package's first release, so no
   automation can run until this is done once.
5. **Closed testing:** a personal developer account needs 12 testers opted in
   for 14 continuous days before production unlocks, then up to 7 days review.
   Budget three weeks.
6. **Service account for the automation** (see below), created fresh for this
   app. PakSehat's key belongs to a different Cloud project and cannot upload
   this package.

---

## Worth doing, not blocking

- The launcher icon is still the TextGate "T" lettermark.
- `allowBackup` is on, so the WhatsApp gateway secret and the monitoring log go
  into Google cloud backup. Exclude them in `backup_rules.xml`.
- `firestore.rules` still ships four world-readable and world-writable
  collections that existed only because academia shared the old Firebase
  project. In `textgate-344f2` they have no consumer at all.
- No store listing assets and no release note exist in the repository yet.
- Old-name debris in a repository that will publicly host the privacy policy.
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`: keep the user-initiated flow the app
  already has and do not add an install-time prompt. It is a normal-protection
  permission, not a restricted one (see disproved claims).

---

## Release automation: what PakSehat has, and porting it

PakSehat uses **fastlane `supply`** (pinned 2.237.0) driven by a GitHub Actions
workflow. Six files: `.github/workflows/release.yml`, `android/Gemfile`,
`Gemfile.lock`, `fastlane/Appfile`, `fastlane/Fastfile`, and one hand-written
changelog per build number under
`fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.

How it runs: a push to `main` **that changes the version** triggers it, tests
must pass, it builds the bundle, checks a hand-written release note exists,
authenticates with a service account JSON held in the
`PLAY_SERVICE_ACCOUNT_JSON` Actions secret, uploads to the production track,
then tags the commit and cuts a GitHub release. The keystore reaches CI as a
base64 Actions secret written to a temp file.

Porting it here is small, roughly six files, with four differences that matter:

1. The trigger must watch `app/build.gradle.kts` for a `versionCode` change,
   since there is no `pubspec.yaml` to grep.
2. This repository has **no** `.github/workflows` directory at all yet.
3. Point the first runs at the **internal** track, not production.
4. Add a hard signing check. This project's build deliberately produces an
   *unsigned* bundle when the keystore is missing rather than failing, so CI
   must assert the keystore file is non-empty and that the output bundle
   actually contains a signature block. PakSehat cannot hit this because its
   build crashes instead.

Note PakSehat's own `docs/RELEASE_AUTOMATION.md:39-42` is stale: it describes
release notes being generated from commit subjects, which commit `96dce3b`
replaced with the hand-written file. Port the behaviour, not the description.

---

## Claims raised and disproved

- *"A reviewer cannot get past sign-up."* False: `PhoneVerifyScreen.kt:144-146`
  offers a Skip button that lands in the full app. (App access instructions in
  the console are still worth filling in.)
- *"The disclosure dialogs can be dismissed by tapping outside."* True
  mechanically, not a policy defect: dismissing grants nothing.
- *"CI cannot build because two inputs are gitignored."* Overstated; both are
  supplied as secrets exactly as PakSehat does.
- *"`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is a restricted permission."* False,
  it is normal protection level.

---

## Firebase Analytics

Not present and sending nothing. The build carries only the Firebase BoM, Auth
and Firestore (`gradle/libs.versions.toml:32-34`). The console's Analytics
section will stay empty. Adding it would pull in the advertising-ID permission
and force extra Data safety declarations on a private family tool, so the
recommendation is to leave it out. Crashlytics is a separate question and is
worth adding later; it does not require Analytics.
