# Setup Guide

Step-by-step instructions for setting up TextGate from scratch.

---

## 1. Firebase Project

### Create project

1. Go to [console.firebase.google.com](https://console.firebase.google.com)
2. Click **Add project** → name it (e.g. `TextGate`) → continue
3. Disable Google Analytics if you don't need it → **Create project**

### Enable Authentication

1. In the Firebase console, go to **Build → Authentication**
2. Click **Get started**
3. Under **Sign-in providers**, enable **Email/Password**
4. Save

### Enable Firestore

1. Go to **Build → Firestore Database**
2. Click **Create database**
3. Choose **Production mode** (you'll set rules below)
4. Pick a region close to your users → **Done**

### Firestore Security Rules

Go to **Firestore → Rules** and paste:

```js
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    match /sim_module/ttgo_users/{uid}/{document=**} {
      allow read, write: if request.auth != null && request.auth.uid == uid;
    }
    match /sim_module/sms/sms_jobs/{phone} {
      allow read, write: if request.auth != null;
    }
    match /sim_module/device {
      allow read: if request.auth != null;
      allow write: if false;
    }
  }
}
```

Click **Publish**.

### Create the device document

In Firestore, manually create the document `sim_module/device` with:

```json
{
  "free_sms_quota": 10,
  "active": true
}
```

(The TTGO v8 firmware will populate the rest. For testing without a device, `free_sms_quota` is all you need.)

---

## 2. Android App Setup

### Add google-services.json

1. In Firebase console → **Project Settings** (gear icon) → **Your apps**
2. Click **Add app** → Android
3. Package name: `com.textgate.app`
4. Nickname: TextGate → **Register app**
5. Download `google-services.json`
6. Place at `app/google-services.json` (next to `app/build.gradle.kts`)

### Configure local.properties

```bash
cp local.properties.example local.properties
```

Edit `local.properties`:

```properties
FIREBASE_PROJECT_ID=your-project-id   # from Firebase console → Project Settings
SMS_JOBS_PATH=sim_module/sms/sms_jobs
USERS_PATH=sim_module/ttgo_users
DEVICE_DOC_PATH=sim_module/device
UNVERIFIED_QUOTA=2
PARTIAL_VERIFIED_QUOTA=4
HISTORY_POLL_INTERVAL_SECONDS=10
WIFI_STABILITY_MINUTES=10
MIN_WIFI_STABILITY_MINUTES=5
```

### Gradle wrapper (first-time only)

The binary `gradle/wrapper/gradle-wrapper.jar` is not committed (binary files in git). On first clone, let Android Studio download it automatically by opening the project, **or** run:

```bash
gradle wrapper --gradle-version 8.4
```

(Requires Gradle installed locally. Android Studio handles this for you.)

### Build

```bash
./gradlew assembleDebug
```

Install on device:

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## 3. Verification Checklist

After installing:

- [ ] Sign up with a Pakistani number (03XXXXXXXXX) and email
- [ ] Check Firestore: `sim_module/ttgo_users/{uid}` document created
- [ ] OTP SMS arrives on the registered phone (requires TTGO device online)
- [ ] Enter OTP → `phone_verified: true` in Firestore
- [ ] Send a test SMS → `sim_module/sms/sms_jobs/{+923...}` document created with `status: "pending"`
- [ ] History screen shows the queued message
- [ ] Status updates after TTGO processes the job

---

## 4. V2 Prerequisites (Arrival Detection)

Additional permissions required on the device:

- `ACCESS_FINE_LOCATION` — needed to read WiFi BSSID
- `ACCESS_BACKGROUND_LOCATION` — needed on Android 10+ for background BSSID reads
- `POST_NOTIFICATIONS` — Android 13+ for the persistent foreground service notification

The V2 setup screen explains each permission to the user before requesting.

WiFi scanning requires location services to be **On** at the OS level (Android restriction).
