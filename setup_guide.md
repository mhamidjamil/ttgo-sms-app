# Spotwire — Developer Setup Guide

Everything you need to go from a fresh clone to an APK on a phone. Written for
Linux; macOS is nearly identical, Windows notes are inline where they differ.

For *what the app does* see [README.md](README.md). For agent/contributor
conventions see [AGENTS.md](AGENTS.md). For deeper docs: [docs/](docs/).

---

## 1. Prerequisites at a glance

| Tool | Version | Why |
|------|---------|-----|
| JDK | **17** (Temurin recommended) | AGP 8.2 / Gradle 8.4 toolchain target |
| Android SDK | platform **34**, build-tools **34.0.0**, platform-tools | `compileSdk 34` |
| Gradle | none needed — the repo ships the **wrapper** (`./gradlew`) | pinned to 8.4 |
| Firebase | access to the project that hosts the backend | Auth + Firestore |

Android Studio Hedgehog+ bundles all of the above and is the easiest path.
The CLI-only path (no Android Studio) is documented below and is what CI or a
headless machine would use.

---

## 2. JDK 17

Check first: `java -version` — any JDK 17 works. If your distro doesn't ship 17
(e.g. Kali/Debian testing often ships 11/21/25), install Temurin 17 into your
home directory without touching system Java:

```bash
mkdir -p ~/.jdks && cd ~/.jdks
curl -L -o temurin17.tar.gz \
  "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse"
tar xzf temurin17.tar.gz && rm temurin17.tar.gz
ls ~/.jdks   # note the exact jdk-17.x.y+z directory name
```

> **Do not** set this JDK globally if other projects on the machine need a
> different one — scope it per shell/session (`export JAVA_HOME=...`) or per
> IDE project instead.

## 3. Android SDK (CLI path — skip if using Android Studio)

```bash
mkdir -p ~/Android/Sdk/cmdline-tools && cd /tmp
curl -L -o cmdline-tools.zip \
  "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
unzip -q cmdline-tools.zip
mv cmdline-tools ~/Android/Sdk/cmdline-tools/latest && rm cmdline-tools.zip

export JAVA_HOME=~/.jdks/jdk-17.*          # adjust to your exact dir
export ANDROID_HOME=~/Android/Sdk

yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" \
  "platform-tools" "platforms;android-34" "build-tools;34.0.0"
```

## 4. Project configuration

```bash
git clone <repo-url> && cd ttgo-sms-app
cp local.properties.example local.properties
```

Then edit `local.properties`:

1. **`sdk.dir`** — add the absolute path to your SDK, e.g.
   `sdk.dir=/home/<you>/Android/Sdk` (Android Studio writes this line itself).
2. **`FIREBASE_PROJECT_ID`** and the Firestore path keys — ask the project
   admin for the real values, or use your own Firebase project for development.
3. **SMTP keys** (optional) — used by "Request more SMS" admin emails and
   email-verification codes. Leave blank to disable email features locally.
   ⚠️ These are compiled into the APK; only ever use a dedicated low-privilege
   mail account (e.g. a Gmail app password for a bot account).

Key reference: the tables in [README.md](README.md#localproperties-reference)
and the comments inside `local.properties.example`.

## 5. `app/google-services.json`

The Firebase client config. It is gitignored — every developer places it
manually:

- **Ask the project admin** for the file for package `com.spotwire.app`, or
- Download it yourself if you have Firebase console access: *Project settings →
  Your apps → Android app `com.spotwire.app` → google-services.json*, or
- Register your **own** Firebase project (Auth email/password + Firestore
  enabled), add an Android app with package `com.spotwire.app`, and use its
  file (full walkthrough: [docs/SETUP.md](docs/SETUP.md)).

Place it at `app/google-services.json`. The build fails fast with a clear
error if it's missing.

## 6. Build

```bash
export JAVA_HOME=~/.jdks/jdk-17.*      # if not already in your environment
./gradlew assembleDebug                # Windows: .\gradlew.bat assembleDebug
```

APK lands at `app/build/outputs/apk/debug/app-debug.apk`.
First build downloads Gradle 8.4 + all dependencies (a few minutes); later
builds are incremental.

Tip: create a small untracked `run-build.sh` that exports `JAVA_HOME` /
`ANDROID_HOME` and calls `./gradlew "$@"` so you never depend on shell state.

## 7. Install on a phone

On the phone: *Settings → About phone → tap the build/OS-version row 7× →
Developer options → enable **USB debugging***, then plug in USB and accept the
"Allow USB debugging?" prompt.

```bash
export PATH="$ANDROID_HOME/platform-tools:$PATH"
adb devices                 # must list the device as "device", not "unauthorized"
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

No cable? Copy the APK to the phone any other way and open it (allow
"install unknown apps").

## 8. Smoke test

Follow the checklist in [README.md](README.md#running--testing): sign up with a
Pakistani number, watch the `sms_jobs` doc appear in Firestore, verify the
phone via OTP (requires the TTGO device to be online), send a test SMS, watch
History update.

---

## Troubleshooting

| Symptom | Fix |
|---------|-----|
| `SDK location not found` | `sdk.dir` missing/wrong in `local.properties` |
| `local.properties missing: <KEY>` | copy the missing key from `local.properties.example` |
| `File google-services.json is missing` | see section 5 |
| Gradle fails with `Unsupported class file major version` or Kotlin/AGP version rants | you're on the wrong JDK — point `JAVA_HOME` at JDK 17 |
| `NoSuchFileException: .../gradle-8.4/...` pointing at a deleted path | a stale Gradle daemon from an old location: `jps -l \| grep -i gradle`, `kill <pid>`, rebuild |
| `adb devices` shows nothing | phone in charge/MTP-only mode — enable USB debugging (section 7) |
| `adb devices` shows `unauthorized` | accept the RSA prompt on the phone screen |
| OTP SMS never arrives | the TTGO T-Call device is offline — it is the thing that physically sends SMS |

## Project layout (30-second tour)

```
app/src/main/java/com/textgate/app/
├── presentation/   Compose screens + ViewModels (auth, send, history, auto, profile, settings, whatsapp)
├── domain/         pure Kotlin: models, repository interfaces, use cases
├── data/           Firebase Auth/Firestore data sources, DataStore prefs, DTOs, repository impls
├── core/           DI modules (Koin), navigation, theme, utils (PhoneNormalizer, Constants…)
└── services/       ArrivalService — foreground WiFi/BSSID arrival monitoring (V2)
```

Conventions (naming, where things go, Firestore path rules) live in
[AGENTS.md](AGENTS.md) — read it before your first PR.
