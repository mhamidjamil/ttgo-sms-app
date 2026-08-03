# V2 Arrival Feature

Automatic guardian SMS notifications triggered by a saved WiFi network being in range.

---

## How It Works

1. User sets up home and office WiFi (BSSID + label) and a guardian phone number in **Profile → Arrival Settings**
2. `ArrivalService` (foreground service) sweeps for nearby WiFi networks every two minutes
3. When a saved network is heard, connected or not, a stability countdown starts
4. If that network stays in range for the full stability period → a SMS is sent to the guardian via the TTGO gateway
5. One notification per location per day (cooldown guard)
6. Past arrival history is shown in the **Auto** tab

---

## Routine Learning (μ±σ)

`RoutineAnalyzer` tracks up to 30 past arrival times (HH:mm) per location.

If ≥5 times are recorded and the current time falls within **mean ± 1 standard deviation** of past arrivals, the stability wait is halved (minimum `MIN_WIFI_STABILITY_MINUTES`). This means:
- Usual schedule → faster confirmation (half the wait)
- Unusual time → full stability wait to avoid false triggers

The `routine_triggered` flag in `auto_history` records whether the shortened wait applied.

---

## Firestore Schema (V2 additions)

### `sim_module/ttgo_users/{uid}` — new fields

| Field | Type | Description |
|-------|------|-------------|
| `guardian_number` | string | E.164 Pakistani number for notifications |
| `home_bssid` | string | MAC address of home WiFi AP |
| `home_label` | string | Display name (e.g. "My Home") |
| `office_bssid` | string | MAC address of office WiFi AP |
| `office_label` | string | Display name (e.g. "Office") |
| `wifi_stability_minutes` | int | Stability wait (from RTDB/config, default 10) |
| `arrival_home_times` | string[] | Last 30 home arrival times in "HH:mm" |
| `arrival_office_times` | string[] | Last 30 office arrival times in "HH:mm" |
| `last_home_arrival_date` | string | "YYYY-MM-DD" cooldown guard |
| `last_office_arrival_date` | string | "YYYY-MM-DD" cooldown guard |

### `sim_module/ttgo_users/{uid}/auto_history/{id}`

| Field | Type | Description |
|-------|------|-------------|
| `location` | string | "home" or "office" |
| `sent_at` | Timestamp | When the arrival was triggered |
| `status` | string | pending → sent/failed (same flow as SMS jobs) |
| `job_phone_key` | string | Guardian phone number |
| `message` | string | e.g. "Alice arrived at My Home" |
| `routine_triggered` | bool | True if RoutineAnalyzer shortened the wait |

---

## Android Permissions

| Permission | Why |
|-----------|-----|
| `ACCESS_FINE_LOCATION` | Required to read WiFi scan results and BSSIDs (Android 8.1+) |
| `ACCESS_BACKGROUND_LOCATION` | Required on Android 10+ to keep scanning in the background |
| `FOREGROUND_SERVICE` | ArrivalService runs as foreground service |
| `FOREGROUND_SERVICE_LOCATION` | Required for foreground services using location on Android 14+ |
| `POST_NOTIFICATIONS` | Persistent foreground notification (Android 13+) |

The Settings screen requests `ACCESS_FINE_LOCATION` before showing the WiFi scan dialog. `ACCESS_BACKGROUND_LOCATION` must be granted separately — Android forces users to set it manually in system settings when the app requests it; the Settings screen explains this.

---

## ArrivalService Architecture

```
sweep loop (every 120s, plus immediately on NetworkCallback.onAvailable)
    │
    ├─ WifiManager.startScan() + scanResults + connected BSSID
    │       └─ any saved place BSSID in that set?
    │           ├─ yes, first time  → remember when it came into range
    │           ├─ yes, still short → RoutineAnalyzer.effectiveWait() not reached yet
    │           ├─ yes, long enough → RecordArrivalUseCase
    │           └─ no, twice in a row → drop the countdown
```

Detection is in-range, not connected: someone on mobile data all day still walks
past their own router, and the old connection-only rule never fired for them. The
countdown survives a single missed sweep because scan results drop an access
point at random even when the phone has not moved.

The service is started/stopped by the toggle in SettingsScreen. `ArrivalService.isRunning` is a static flag set in `onCreate`/`onDestroy` — used to reflect current state when the Settings screen opens.

---

## Enqueue flow (auto arrival)

`RecordArrivalUseCase` → `SmsRepository.enqueueAutoArrivalSms` → Firestore batch:
1. `sim_module/sms/sms_jobs/{guardianNumber}` — picked up and delivered by TTGO device
2. `sim_module/ttgo_users/{uid}/auto_history/{id}` — shown in the Auto tab

`enque_by` is `"app:{uid}:arrival"` — distinct from manual sends (`"app:{uid}"`) and OTP sends (`"app:{uid}:otp"`).

---

## BSSID vs SSID

The app stores **BSSID** (hardware MAC address), not SSID (network name). This prevents spoofing — anyone can set their hotspot to "HomeWiFi" but BSSID is tied to the physical access point.

`WifiManager.scanResults` lists every access point in range; the connected BSSID from `WifiManager.connectionInfo` is folded into the same set. Both APIs are deprecated on Android 12+ but remain functional. The `02:00:00:00:00:00` value (returned when location is off or permission denied) is explicitly filtered out.

Scanning needs either WiFi switched on or the system-wide "WiFi scanning always available" toggle, plus device location on. The "Where am I?" card on the Arrival tab reports which of those is missing.

---

## Sending your location by hand

The "Where am I?" card also offers **Send message** once a saved place is in
range. It prompts with everyone that place would alert (guardian, place contacts,
linked accounts with automatic updates), all pre-checked and individually
removable, then queues one SMS each.

That message is a manual send: it lands in **Manual** history, it costs daily
SMS quota, and its signature ends in `(via manual trigger)`. An automated arrival
alert ends in `(via automation)` instead, so the receiver can tell the two apart.
