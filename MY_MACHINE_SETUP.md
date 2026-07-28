# My machine setup — TextGate (not for git, personal reference only)

This is *your* cheat sheet for this exact machine (Kali, user `megatron`). Unlike
`setup_guide.md` (generic, for any new developer), everything here is a real
path that already exists on this computer as of 2026-07-05.

---

## What's already installed here

| Tool | Location on this machine |
|------|---------------------------|
| JDK 17 (Temurin) | `~/.jdks/jdk-17.0.19+10` |
| Android SDK | `~/Android/Sdk` (platform 34, build-tools 34.0.0, platform-tools) |
| Gradle | not installed globally — the project's own `./gradlew` handles it (wrapper pinned to 8.4) |
| `google-services.json` | already sitting at `app/google-services.json` (Firebase project `myacademiaapp`, registered for `com.textgate.app`) |
| `local.properties` | already filled in with real Firebase/SMTP values |

You do **not** need to redo any of the JDK/SDK download steps in `setup_guide.md`
— that was all done once, on this machine, already.

## The one command that builds it

```bash
cd ~/Desktop/projects/ttgo-sms-app
./run-build.sh assembleDebug
```

`run-build.sh` (not committed to git, lives only on this machine) exports
`JAVA_HOME=~/.jdks/jdk-17.0.19+10` and `ANDROID_HOME=~/Android/Sdk` just for
this project, then calls `./gradlew "$@"`. It's scoped this way because your
`academia/android` project needs JDK 21 — this keeps the two from fighting
over a global `JAVA_HOME`.

APK lands at: `app/build/outputs/apk/debug/app-debug.apk`

## Installing to your phone (POCO X3 GT)

Your phone is a **Xiaomi POCO X3 GT** running MIUI, which has two extra
gotchas beyond stock Android:

1. **Developer options → USB debugging** must be on, and you must tap
   **Allow** on the "Allow USB debugging?" popup that appears on the phone
   *every time you plug into a computer it hasn't trusted yet* (check "Always
   allow from this computer" to stop it repeating).
2. **Developer options → Install via USB** must *also* be on — MIUI blocks
   `adb install` with `INSTALL_FAILED_USER_RESTRICTED` otherwise. MIUI may ask
   you to sign into a Mi account and verify your phone number before this
   toggle becomes available/stays on.

Once both are on:

```bash
export PATH="$HOME/Android/Sdk/platform-tools:$PATH"
adb devices                 # should show your device as "device", not "unauthorized"
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

To check the app actually launched:
```bash
adb shell monkey -p com.textgate.app -c android.intent.category.LAUNCHER 1
```

### If MIUI drops the USB connection back to MTP-only

Sometimes MIUI resets to file-transfer mode after a reboot/unplug. If
`adb devices` comes up empty and `lsusb` shows the phone as `(MTP)`:
- Re-check that USB debugging is still toggled on (MIUI sometimes flips it
  off after certain reboots/updates).
- Or just copy the APK over MTP and tap it in the file manager instead —
  no adb needed for that path, just "install unknown apps" allowed for
  whichever file manager app you tap it from.

## Full command list, no memory required

```bash
cd ~/Desktop/projects/ttgo-sms-app
./run-build.sh assembleDebug
export PATH="$HOME/Android/Sdk/platform-tools:$PATH"
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Known one-off gotcha (already fixed once, might recur)

If a build ever fails with `NoSuchFileException` pointing at a path under
`/tmp/...gradle-8.4/...`, it means a stale Gradle daemon is still running from
a deleted temp copy of Gradle. Fix:
```bash
jps -l | grep -i gradle     # find the PID
kill <pid>
./run-build.sh assembleDebug   # retry
```

## Tagging releases

```bash
git tag -f v3 -m "v3: <what changed>"   # once you've tested a build
```
(`v2` currently marks the last release you confirmed working, as of the
OTP/guardian-numbers/quota-request changes on 2026-07-04/05.)
