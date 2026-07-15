# Arrival detection: what is broken, and the design that fixes it

Final engineering proposal for the automatic "I arrived" alerts in TextGate.
Written against HEAD `3729794`. Every claim below is anchored to a file and line
in this repository.

Two things were decided against the audits and in favour of the challenges, and
they shape everything that follows:

1. **Play Services geofencing is not the primary trigger.** Google documents
   geofencing as needing a data connection and WiFi to fire at all, which is
   exactly the state the owner sleeps in. Re-registering a fence while already
   inside it fires an immediate entry event, which recreates the false morning
   alert on every restart. It also does not survive an aggressive OEM battery
   manager any better than the current service does, because the transition is
   still delivered to this app's process. WiFi scanning plus free platform
   sensors stays the engine. Geofencing is listed as an optional future
   corroborator only.
2. **Local storage is the source of truth for presence, not Firestore.** The
   Firestore read used on every sweep (`app/src/main/java/com/textgate/app/services/ArrivalService.kt:106`
   into `app/src/main/java/com/textgate/app/data/firebase/FirestoreDataSource.kt:64`)
   falls back to the offline cache and can be hours old. Building the new state
   machine on that read would inherit the exact staleness that causes the
   current double and missed alerts. Firestore becomes a mirror for the second
   device, never the decider.

---

## 1. What is broken today

### Symptom 1: some days no alert at all when reaching home

There is no single cause. Ranked by how likely each is to be the real story on
this phone:

**1a. The morning false alert eats the evening alert. This is the most likely
explanation and no audit connected it.**
The only replay guard is a calendar date string
(`app/src/main/java/com/textgate/app/services/ArrivalService.kt:126`, re-checked
at `app/src/main/java/com/textgate/app/domain/usecase/location/RecordArrivalUseCase.kt:35`,
written at `app/src/main/java/com/textgate/app/data/firebase/FirestoreDataSource.kt:265`).
When symptom 2 fires a bogus "arrived home" at breakfast, it stamps today's date
against the home place. The genuine arrival that evening hits that guard and is
discarded with a log line and nothing else. Symptom 2 is therefore the cause of
symptom 1, and of half of symptom 3.
**One-minute test:** read `last_arrival_dates` and `arrival_times.home` in
Firestore for this account and look for morning entries.

**1b. The service is simply not running.**
There is no boot receiver anywhere. `app/src/main/AndroidManifest.xml` declares
no `RECEIVE_BOOT_COMPLETED` permission and no receiver of any kind; the only
declared component besides the activity is the service itself
(`app/src/main/AndroidManifest.xml:39-42`). The comment at
`app/src/main/java/com/textgate/app/MainActivity.kt:31-33` claiming the service
survives reboots is false. After a restart, detection is dead until the app is
opened by hand. There is also no watchdog: WorkManager is not a dependency and
appears nowhere in the source. On a Xiaomi skin, a foreground service that is
not whitelisted is routinely killed, and nothing notices.

**1c. Monitoring can silently refuse to start.**
`app/src/main/java/com/textgate/app/presentation/settings/SettingsScreen.kt:781-786`
requires the notification permission on Android 13 and above as a hard
precondition, and `SettingsScreen.kt:76-81` and `:185-190` only start the service
when every listed permission is granted. Declining the notification prompt once
leaves arrival monitoring permanently off, with the preference never even
persisted.

**1d. An offline send can stall the sweep loop for a while.**
`RecordArrivalUseCase.kt:103` reaches
`FirestoreDataSource.kt:304-310`, a batch commit awaited with no timeout
anywhere in the codebase. Offline, that call parks until the phone is back
online. The cadence loop awaits `sweep()` inline
(`ArrivalService.kt:97-100`), so the loop stops advancing for as long as the
stall lasts. This is real but bounded, not permanent: the WiFi availability
callback at `ArrivalService.kt:70-74` keeps launching independent sweeps, and
Firestore resolves the queued write once connectivity returns. It is a delay
generator, not a kill switch. Note also that reopening the app cannot clear it,
because `MainActivity.kt:35` skips the start when the static running flag
(`ArrivalService.kt:191`) is still true.

**1e. Nobody to send to, reported as success.**
`RecordArrivalUseCase.kt:44-56`: with no place contacts, no linked account
granted automatic updates, and a blank guardian number, the recipient list is
empty, nothing is written, and the result is a success. The service then logs
"arrival recorded" at `ArrivalService.kt:145`. The guardian number is only
persisted by the explicit Save button, so editing places alone can leave it
blank.

**1f. The saved network is the wrong one.** A place stores exactly one access
point identifier (`app/src/main/java/com/textgate/app/domain/model/Place.kt:15`).
A dual-band router advertises a different identifier per radio and a mesh has one
per node, so saving the 5 GHz identifier means the far side of the house never
matches.

**Not the cause, despite being claimed:** the missing coarse location permission
(`AndroidManifest.xml:10` has fine only, `SettingsScreen.kt:782` requests fine
only). It is a genuine bug worth fixing, but a permission that was never granted
predicts zero alerts ever, not "some days". The owner's symptom 2 proves scan
results are non-empty on this device. Doze is also not the cause: Doze needs the
device stationary, and a man walking or riding home is not stationary.

### Symptom 2: WiFi and data off overnight, false "arrived home" in the morning

This is a design gap, not a line-level bug. The service has **no concept of
having been away**. Nothing anywhere records "he is at home right now".

The chain:
1. Evening arrival fires and stamps yesterday's date
   (`FirestoreDataSource.kt:265`).
2. Radios go off. `app/src/main/java/com/textgate/app/core/utils/WifiUtils.kt:22-37`
   now returns an empty set. The service cannot tell a switched-off radio from a
   departure; it logs a warning at `ArrivalService.kt:112` and carries on.
   `canScanWifi` exists at `WifiUtils.kt:59-62` and the service never calls it.
3. Midnight passes. The date guard resets by itself.
4. Radios come back on. The availability callback at `ArrivalService.kt:70-74`
   runs an immediate sweep, the home network is heard, and
   `ArrivalService.kt:131` starts a **fresh countdown right then**.
5. Ten minutes later the alert fires, stamped with the time the countdown
   finished (`RecordArrivalUseCase.kt:61`), so the message says "arrived at Home
   at 8:07 AM".

**Before choosing a fix, read the time printed inside the received message.**
If it says a time near midnight, the alert was generated then and only
*delivered* in the morning, because the queued job sat in Firestore's offline
queue until data returned. If it says the morning time, the countdown genuinely
restarted. Both are real problems, they have different fixes, and the received
message text distinguishes them for free.

### Symptom 3: home for 10 to 15 minutes, then back to office, neither alerted

**The office half is working as designed and cannot be tuned around.** He was
already alerted at the office that morning, so `ArrivalService.kt:126` short
circuits. One alert per place per calendar day means a return trip can never
notify anyone.

**The home half is the timing budget.** The nominal wait is 10 minutes
(`local.properties:47` to `app/build.gradle.kts:39-40` to
`app/src/main/java/com/textgate/app/core/utils/Constants.kt:31`), but three
independent penalties each add up to 120 seconds:
- the countdown starts on the first sweep that hears the network, not on arrival
  (`ArrivalService.kt:99`);
- the scan is requested and the results read on the very next line
  (`ArrivalService.kt:107-108`), and a real scan takes seconds, so the sweep
  usually evaluates the previous sweep's cache. This penalty does not apply when
  the phone auto-joins the network, because the connected access point is folded
  in live at `WifiUtils.kt:31-35`;
- elapsed minutes are integer division sampled only at sweep boundaries
  (`ArrivalService.kt:137-138`).

So the floor is 10 minutes and the ceiling is about 16. A 10 to 15 minute visit
lands right on that line, and once he leaves, two missed sweeps
(`ArrivalService.kt:120`, `:201`) throw the nearly-complete countdown away. The
day guard from a morning false alert (cause 1a) would also block it outright.

### Defects not yet noticed, ranked by likelihood of causing a missed or false alert

| # | Defect | Evidence | Effect |
|---|---|---|---|
| D1 | One alert per calendar day is also one **missed** alert per calendar day: any earlier alert, false or real, blocks every later one | `ArrivalService.kt:126`, `RecordArrivalUseCase.kt:35` | missed alert, daily |
| D2 | Two saved places audible from one spot both run independent countdowns and both fire | `ArrivalService.kt:116-151` iterates places independently | two contradictory alerts |
| D3 | Queued messages are keyed by phone number, so a second message to the same person replaces the first before the gateway drains it | `FirestoreDataSource.kt:171,304,360,409,452` | silently destroyed message |
| D4 | Partial fan-out counts as done: one recipient succeeding records the day and blocks retry for the other | `RecordArrivalUseCase.kt:111,125,127` | one person never told |
| D5 | Send succeeds but the day-guard write fails, so the whole call reports failure and the next countdown re-sends to everyone | `RecordArrivalUseCase.kt:127`, `ArrivalService.kt:146-150` | duplicate messages |
| D6 | The two sweep entry points share unsynchronised maps and can both fire one arrival | `ArrivalService.kt:58,61,72,96,131` | duplicate messages, lost map entries |
| D7 | Deleted or re-pointed places keep their stale countdown, so a re-pointed place fires with zero waiting time on the first match | `ArrivalService.kt:109,120,131` | instant false alert |
| D8 | The routine shortcut has no midnight wraparound: someone arriving near midnight gets an average around noon with an enormous spread, so the wait halves at arbitrary times of day | `app/src/main/java/com/textgate/app/core/utils/RoutineAnalyzer.kt:20,24,31-36` | premature alert |
| D9 | The routine shortcut learns the wrong times, because it records when the countdown finished rather than when the network was first heard, and every false morning alert poisons the distribution | `RecordArrivalUseCase.kt:53,127` | worsening over time |
| D10 | Shared mutable date formatters on a shared object, used from several threads, and the timezone is frozen at process start for a service meant to run for weeks | `app/src/main/java/com/textgate/app/core/utils/DateUtils.kt:8-9` | corrupted date permanently mis-arms the guard |
| D11 | Every sweep does a full user document read and the arrival listener stream stays open for the life of the service | `ArrivalService.kt:106,158-167` | the real battery cost, see section 3 |
| D12 | Scan refusals are discarded, so a throttled scan silently replays stale results and still logs "networks in range" | `WifiUtils.kt:43-49` | invisible failure |
| D13 | The notification says "Arrival monitoring active" unconditionally, even when the app has been unable to scan for a week | `ArrivalService.kt:182-187` | the owner cannot tell it is broken |
| D14 | The per-user waiting-time setting already exists end to end and is read by nothing | `app/src/main/java/com/textgate/app/data/model/UserDto.kt:40-41`, `app/src/main/java/com/textgate/app/domain/model/User.kt:31` | dead code, but a free head start on the new control |
| D15 | Coarse location is absent from the manifest and from the runtime request | `AndroidManifest.xml:10`, `SettingsScreen.kt:781-786` | on some releases the permission dialog never appears at all |
| D16 | Whole places array is rewritten on every save from a fixed key map, so an older build of the app on a second phone wipes any new per-place settings | `FirestoreDataSource.kt:187-194,216` | silent settings loss |

---

## 2. The redesigned detection model

### The core idea: three states, not two

The current code has two states, present and absent, and resolves every
ambiguity toward absent. That is why it fabricates arrivals. Simply flipping the
default to present would swallow real ones instead. Reality has a third state:
**cannot tell**. It must be named, stored, and handled.

Second core idea: **an alert belongs to a visit, not to a day.** The guard is
"one alert per visit", and a visit only ends when the app has positive evidence
that the surroundings changed. A gap in observation is never evidence.

### The states

Per place:

- **AWAY** - the app can currently observe, and this place's networks are not
  among what it hears.
- **APPROACHING** - the place's networks are heard, the visit clock is running,
  no alert sent yet.
- **HERE** - the visit is confirmed. Either an alert was sent, or the visit was
  adopted silently (see below). No further alert can be sent for this visit.
- **BLIND** - the app cannot observe at all: the radio is off, location services
  are off, the permission is revoked, the service was dead, or the newest scan
  result is older than the freshness limit. BLIND remembers the state it entered
  from and the time it entered.

Plus one global fact stored alongside: an **environment fingerprint**, the set of
every access point identifier heard on the last successful scan, saved or not.
This is what makes "the surroundings changed" a testable statement.

### The transitions

**AWAY to APPROACHING.** The place's networks are heard on a scan result no older
than 60 seconds, and the number of matching access points meets the place's match
requirement, and the strongest matching access point beats the place's closeness
floor. The visit clock starts at that moment and the moment is remembered as the
real arrival time.

**APPROACHING to HERE, sending an alert.** All of the following:
- the visit clock has run for at least the place's waiting time;
- **the phone has been stationary for a continuous stretch inside that window.**
  This single rule is what lets the waiting time be short without becoming
  trigger-happy: a 12 minute visit contains roughly 10 stationary minutes, a
  drive-past contains none. It replaces the whole "pick your sensitivity and
  accept the failure mode" trade-off;
- the deciding scan is fresh, under 60 seconds old;
- this place wins the single-place contest (below) by a clear margin;
- the current time is outside the place's quiet window;
- the re-arm cooldown for this place has expired.

**APPROACHING to AWAY.** Not a timer. The place's networks are absent **and** the
environment fingerprint has changed substantially from what it was when the visit
started **and** the current scan is non-empty and fresh. Two unlucky scans, a
router reboot, or a power cut do not qualify, because during a power cut the
neighbours' networks are still there and the fingerprint barely moves.

**HERE to AWAY, which is what re-arms the next alert.** Same rule as above, held
for a departure debounce of ten minutes of continuous, observed, changed
environment. Crossing this edge, and only this edge, is what allows the place to
alert again.

**Anything to BLIND.** Entered whenever observation is impossible or the newest
scan result is stale. Nothing is inferred while BLIND. No visit clock advances,
no departure is recorded, no alert is sent.

**BLIND back to an observable state, the rule that fixes symptom 2 and the
reboot case.** On the first fresh scan after a blind period:
- If the place's networks are heard and the state before going blind was HERE:
  **adopt as already present. Send nothing.** This is the morning case, and it
  is also the reboot-at-home case.
- If the place's networks are heard and the state before going blind was AWAY or
  unknown, consult a free corroborator: the step counter delta across the blind
  window, or a latched significant-motion event. **No motion during the gap means
  he never went anywhere, so adopt silently.** Motion during the gap means the
  answer is genuinely unknown.
- Genuinely unknown resolves to a **local notification on his own phone** with
  "Send" and "Do not send", defaulting to not sending, and expiring by itself.
  This is the honest answer to an ambiguous sensor, and it is the piece missing
  from every version of this design so far.

**Boot and process restart.** Load the persisted state before anything is allowed
to alert. Treat the whole outage as BLIND and apply the rule above. Suppress all
alerts for a two minute settling window after start. If the phone has not been
unlocked yet and the stored state cannot be read, do not run detection at all.
A restart must never begin from a clean slate, because a clean slate reads as a
fresh arrival.

### One place at a time

"Where am I" is a single decision across all places, not one machine per place.
Every place is scored on how many of its saved access points are visible and how
strong the strongest one is. The winner must beat the runner-up by a clear
margin. If two places tie, the answer is UNKNOWN and nothing is sent. This kills
the double alert when home and office are audible from the same spot, which the
current loop at `ArrivalService.kt:116-151` will produce every time.

### What replaces the calendar-day guard

Three things together, and all three are needed:
- **one alert per visit**, cleared only by an observed departure;
- **a re-arm cooldown** per place, default 45 minutes, which stops a flapping
  boundary from alerting twice in quick succession. It is a floor, never a
  permission by itself: the cooldown expiring does not authorise an alert, only
  a departure does;
- **quiet hours** per place, default off, which suppress the *sending* only.
  A suppressed alert must not stamp anything and must not arm the cooldown, or
  the suppression itself becomes the cause of the next missed alert.

### Where the settings live

Written to local storage first, then mirrored to Firestore. The service reads
local storage only. This means the value on screen and the value in use are
always the same, even offline, and it means no state transition depends on the
component that hangs offline.

**Changing a place's settings resets that place's running visit clock and applies
from the next detection.** Each running clock is stamped with the settings
version it began under. This must be stated in the interface, because otherwise
shortening the waiting time mid-visit fires an alert instantly.

---

## 3. Battery plan

### The real cost is not WiFi scanning

The headline "3 to 8 percent per day" from the audits does not hold. Its largest
component was 720 wake-and-settle cycles, and this code holds no wake lock and
schedules no alarm (`ArrivalService.kt:94-102` is a plain coroutine delay), so it
cannot wake a sleeping processor. It resumes when something else has already
woken the device. Scanning alone is roughly 0.2 to 0.7 percent per day.

What nobody costed is `ArrivalService.kt:106`: a full Firestore document read
every 120 seconds, roughly 700 network round trips a day, plus the arrival
listener stream held open for the entire life of the service
(`ArrivalService.kt:158-167`). On cellular, a data transaction plus its radio
tail costs an order of magnitude more than a WiFi scan, and an open stream keeps
the modem out of deep sleep regardless. **Cutting the Firestore poll is the
single biggest battery win available, and it is a small change.**

### Cadence per state

| State | WiFi scan cadence | Firestore |
|---|---|---|
| BLIND (radio or location off) | none, waiting on system broadcasts | none |
| AWAY and stationary for more than 10 minutes | none. Waiting on a significant-motion trigger and on the free scan-results broadcast that other apps' scans produce | none |
| AWAY and moving | one scan every 2 minutes | none |
| AWAY and a saved network just appeared, or the phone just joined a network | one scan every 60 seconds for up to 5 minutes | none |
| APPROACHING | one scan every 60 seconds | none |
| HERE and stationary | one scan every 15 minutes, purely to notice a departure | none |
| Alert about to send | - | one read, one write |

Settings are read from local storage and refreshed from Firestore on app launch
and on a slow background schedule, not per sweep. That takes the daily Firestore
traffic from roughly 700 reads to under 10.

Expected duty cycle on a normal day: two commutes, a few dozen scans, two
alerts. Overnight and at a desk, essentially zero. Well under half a percent of
battery per day, and, more importantly, correct rather than merely cheap.

### Which free sensors gate what

- **Significant motion**, a hardware trigger that runs on the sensor hub, wakes
  the device itself, needs no wake lock and, confirmed, needs no activity
  recognition permission. It is what ends a stationary back-off. It is one-shot
  and must be re-armed every time.
- **Step counter delta**, used only as the blind-window corroborator described
  above. It resets on reboot, so it is unavailable in exactly the reboot case,
  which is why the reboot case has its own rule.
- **Stationary and motion detect sensors** where present, as a cheaper substitute
  for the stationary test. They return null on many Xiaomi devices, so they are
  used opportunistically and never depended on.
- **The system scan-results broadcast**, free, tells the app when fresh results
  exist and whether they are actually new. Consuming other apps' scans costs
  nothing and removes the entire "read the previous sweep's cache" family of
  bugs.

**Important limitation, and the reason the service is not deleted:** a sensor
callback and a scan-results broadcast are **not** on Android's list of things
allowed to start a foreground service from the background. Only a boot
broadcast, an exact alarm from a user request, a high-priority push message, a
geofence or activity transition, or having battery optimisation switched off can
do that. So the design keeps one long-lived foreground service and varies what it
does inside, rather than starting and stopping bursts. The persistent
notification stays, and it earns its place by carrying real status.

### Surviving reboot and an aggressive battery manager

1. A boot receiver on device boot, on boot completed after unlock, and on package
   replaced. The location service type is not blocked from boot on Android 14 or
   15, so this is legal.
2. Restore persisted state before starting detection, and never alert during the
   settling window.
3. A 15 minute periodic background worker as a watchdog. It is deferred while the
   device sleeps, but it catches the case that actually matters, which is the
   OEM having killed the service.
4. A first-run setup step that deep links to the Xiaomi autostart page and the
   per-app battery saver page, and asks for the battery optimisation exemption.
   On this phone these are not optional, and no amount of correct code
   substitutes for them.
5. A heartbeat written on every successful sweep. If the app opens and the
   heartbeat is more than a few hours old while monitoring is supposedly on, tell
   the owner in the interface, and send him a local notification if detection has
   been blind for more than 24 hours.

---

## 4. User-facing settings

The owner asked for **one** adjustable control. Eight new per-place fields would
be eight new ways to configure the feature into permanent silence. So: one
visible control, a capture button, and an advanced drawer that most people never
open.

### Per place, visible

**1. Alerts for this place: on / off.** Default on. Off keeps the place saved and
still answers "where am I", but never sends.

**2. Sensitivity: Quick / Balanced / Careful.** Default Balanced. Three options,
not four, because a genuinely instant option cannot be made honest.

The explanation line changes with the selection:

- **Quick.** "Alerts about 3 minutes after you settle at this place. Best for
  places you only stop at briefly. Because it waits for you to actually stop
  moving, driving past will not set it off, but a short stop next door might."
- **Balanced.** "Alerts about 8 minutes after you settle at this place. This is
  the safe middle and what most people should leave it on."
- **Careful.** "Alerts about 20 minutes after you settle. Use this where your
  neighbours' networks overlap yours, or where you often pass by without going
  in. Very short visits will end before the alert goes out."

The honest trade-off, in one line under the chooser: **"Shorter waits tell people
sooner and catch short visits, but are more likely to announce a visit you did
not really make. The app only counts time while your phone is sitting still, so
even the short setting will not fire while you are driving past."**

**3. Do not alert between.** Default off. Two time fields. Copy: "No alerts are
sent during these hours. Nothing else changes, so a real arrival right after this
window still alerts normally."

**4. Networks saved here.** A summary line, "4 networks saved, needs 2", with the
two buttons below.

### Capture

**"Capture networks here"** button, next to the existing scan button on the place
card (`SettingsScreen.kt:570-574`). Pressing it runs a short burst of scans and
lists everything heard, with signal strength. Only the strongest access point and
its same-name siblings are **pre-selected**; everything else is unticked and
opt-in with a warning line: "Only tick networks you know are yours. Ticking a
neighbour's network will make the app think you are home when you are not."

One capture is not calibration, so capture is **cumulative**. Each time he
presses it at that place, newly seen access points are recorded as candidates,
and a candidate is promoted into the matching set once it has been seen
alongside the anchor across several separate visits. The interface says
"2 networks confirmed, 3 more being learned".

**How many must match.** Automatic: one when a place has one or two saved
networks, two when it has three or more, never more than two by default. A single
access point dropping out of a scan is normal, so requiring the whole mesh makes
detection worse rather than better. Requiring two of four means one dead node
cannot block an alert and one recycled identifier cannot start one.

**Closeness.** Three chips: **Any signal / Nearby / Inside only**. Default
Nearby. The underlying number is set automatically from the capture sessions
(the typical strongest reading minus a margin), and the chips override it with
round numbers. Plain-English copy: "Inside only means the app must hear your
network loudly, the way it sounds from inside the building rather than from the
street." A read-only line shows what it measured: "Typical strength here: -52."

### Advanced drawer, collapsed by default

Exact waiting time in minutes, exact re-arm gap in minutes, exact number of
networks required, exact signal floor. Blank means follow the preset. These exist
for debugging and for the owner, not for a normal user.

### One more control that is worth more than all of the above

**"Test detection here"** button. It runs a scan and reports, in plain English,
what the app currently believes: which place it thinks it is at, how many saved
networks it heard, how strong, and whether it would alert right now and why not.
This is what turns a silent failure into a five second diagnosis.

### Settings history

The existing change-history mechanism carries all of this unchanged. Log a line
whenever alerts are turned on or off, sensitivity changes, the effective waiting
time changes, the re-arm gap changes, quiet hours change, the saved networks
change, or the closeness setting changes. Log the *effective* number, not the raw
field, so flipping a preset while an advanced override is set does not write a
line that hides no behaviour change. One migration line records that the app-wide
waiting time became a per-place setting.

---

## 5. Free versus paid

**Everything in this design is free at unlimited volume, with no billing account,
no API key and no quota.**

| Used | What it is | Cost |
|---|---|---|
| WiFi scanning and scan results | Android platform, `WifiManager` | free, no key, unlimited |
| Scan-results broadcast | Android platform | free |
| Significant motion, step counter, stationary and motion detect | Android platform, `SensorManager` | free |
| Connectivity callbacks | Android platform | free |
| Foreground service, boot receiver, background worker | Android platform and Jetpack | free |
| Local storage | Jetpack DataStore | free |
| Firestore | Firebase | already in use; the design **reduces** usage by roughly 99 percent, from around 700 document reads a day to under 10 |

**Optional future additions that are also free of billing** but carry other
costs: Play Services geofencing and activity transition recognition. Both ship in
the Play Services location library, need no key, no billing account and no quota.
Their real costs are a new dependency on Play Services, one extra runtime
permission, and, if this is ever distributed on the Play store, a policy
declaration review for background location. They are not in the NOW plan.

**Google products that would bill, and which this design touches nowhere:** the
Places API, the Geocoding API, the Geolocation API, the Roads API, the Maps SDK
and Distance Matrix. None of them is required, because the app never needs a
street address or a map. It needs to recognise a set of radio identifiers, which
is free forever.

---

## 6. Implementation order

Each item is one commit. Dependency order. Nothing later depends on anything
marked FUTURE-PLAN.

**Foundation, no behaviour change yet**

1. **NOW** - Make dates thread-safe and timezone-live. Replace the shared mutable
   formatters at `DateUtils.kt:8-9`. Prerequisite for trusting any stored
   timestamp. (D10)
2. **NOW** - Add a time limit to every Firestore call in the arrival path, and
   stop the sweep loop from awaiting a send inline. Removes the stall in cause
   1d. (`RecordArrivalUseCase.kt:103,127`, `ArrivalService.kt:97-100`)
3. **NOW** - Give queued messages unique identifiers instead of keying them by
   phone number, so two messages to the same person cannot replace each other.
   (`FirestoreDataSource.kt:171,304,360,409,452`) (D3)
4. **NOW** - Track delivery per recipient and retry only the ones that failed,
   instead of treating a partial fan-out as complete. (`RecordArrivalUseCase.kt:111,125,127`)
   (D4, D5)

**The state machine, which is what actually fixes the three symptoms**

5. **NOW** - Introduce the presence store in local storage: per-place state,
   visit start, last observed departure, last alert time, and the environment
   fingerprint. Firestore mirrors it, never decides from it. Nothing reads it
   yet.
6. **NOW** - Rewrite the sweep as the state machine in section 2: the four states,
   the observed-departure rule, one alert per visit, the freshness precondition,
   and the single-place contest. Delete the calendar-day guard from
   `ArrivalService.kt:126` and `RecordArrivalUseCase.kt:35`. **This one commit
   fixes symptom 2, the office half of symptom 3, and cause 1a of symptom 1.**
7. **NOW** - Add the blind-window rule and the adopt-silently path, including the
   step-counter and significant-motion corroborator. Fixes the morning alert even
   when the phone genuinely was elsewhere overnight.
8. **NOW** - Add the boot receiver, state restore, the settling window, and the
   periodic watchdog. Fixes cause 1b. (`AndroidManifest.xml`, `MainActivity.kt:34-38`)
9. **NOW** - Add coarse location to the manifest and to the runtime request, stop
   treating the notification permission as a hard block on monitoring, recheck
   permissions before the service goes into the foreground, and make the
   can-scan check also test whether location services are on.
   (`AndroidManifest.xml:10`, `SettingsScreen.kt:781-786`, `WifiUtils.kt:59-62`)
   Fixes cause 1c and D15.

**Making it adjustable**

10. **NOW** - Move the waiting time from a build-time constant to a per-place
    setting with the three presets, and seed it from the dead per-user field.
    Delete the build-time fields from `app/build.gradle.kts:39-42`.
    (`Constants.kt:30-33`, D14)
11. **NOW** - Support several networks per place, with the cumulative capture
    button, the automatic match count, and the closeness discriminator. Keep
    writing the single strongest identifier alongside, so an older build of the
    app still detects the place. (`Place.kt:15`, `FirestoreDataSource.kt:187-194`)
    (D16, cause 1f)
12. **NOW** - Add quiet hours, the re-arm gap, and the advanced drawer, with
    suppression that does not stamp anything.

**Battery**

13. **NOW** - Stop polling Firestore on every sweep. Read settings from local
    storage, refresh them on launch and on a slow schedule, and stop holding the
    request listener open continuously. **Largest single battery win.**
    (`ArrivalService.kt:106,158-167`) (D11)
14. **NOW** - Drive scans from the system scan-results broadcast and enforce the
    freshness precondition. Stop discarding scan refusals and log them.
    (`WifiUtils.kt:43-49`, `ArrivalService.kt:107-108`) (D12)
15. **NOW** - Add the motion gate and the per-state cadence table from section 3.

**Visibility**

16. **NOW** - Make the ongoing notification tell the truth, add the in-app
    detection health card, the test-detection button, and a warning when
    detection has been blind for more than a day. (`ArrivalService.kt:182-187`)
    (D13)
17. **NOW** - Add the guided setup for autostart, per-app battery saver and the
    battery optimisation exemption on this phone family.
18. **NOW** - Ask before sending when the app is not confident: a local
    notification with Send and Do not send, defaulting to not sending, for
    post-blind, post-reboot, first-visit and tied-place cases.

**Later**

19. **FUTURE-PLAN** - Replace the routine-learning shortcut. It currently learns
    the wrong timestamps and has no midnight wraparound
    (`RoutineAnalyzer.kt:20,24,31-36`). Under the new model it should shorten the
    waiting time only when the arrival matches a learned pattern computed on a
    circular clock, and it should learn from when the network was first heard.
    Until then, switch it off rather than leave it wrong. (D8, D9)
20. **FUTURE-PLAN** - Create places before travelling to them, from pasted
    coordinates or a hand-typed label, with network capture as an on-arrival
    upgrade. Today both configuration paths require standing in the place, so a
    first visit can never be covered, and creating a place while standing in it
    fires an alert about ninety seconds later.
21. **FUTURE-PLAN** - Optional geofence and activity-transition corroboration as
    a second opinion only, never as the trigger, once the state machine is proven.
22. **FUTURE-PLAN** - Move the build to the newer Android target the Play store
    will require from 31 August 2026. The project cannot compile against it today
    without a toolchain upgrade, and that upgrade also turns on a new set of
    background-service behaviours that must be retested.
23. **FUTURE-PLAN** - The gate ecosystem work, which depends on this whole
    section being finished and proven first.

---

## 7. Risks and what could still go wrong

**The environment fingerprint is the load-bearing idea, and it is unproven here.**
The whole "never infer a departure from a gap in observation" rule rests on the
neighbours' networks being stable enough to say "the surroundings did not
change". In a dense apartment block with many mobile hotspots, the fingerprint
may move enough on its own to read as a departure. Mitigation: compare on overlap
proportion rather than exact equality, require the change to persist for the full
debounce, and log every departure decision with the evidence so it can be tuned
from real data rather than guessed.

**The stationary test may not fire reliably.** The significant-motion sensor's
own specification permits false negatives to save power, and vendors tune it
conservatively. A car ride that never triggers it would leave the app in a
back-off state. Mitigation: never let the motion gate be the only thing that can
wake detection. The WiFi connectivity callback and the free scan-results
broadcast both still run, and there is a slow floor cadence that applies no matter
what the sensors say.

**The Xiaomi battery manager can still kill everything.** No architecture defeats
it. The watchdog, the boot receiver and the guided setup reduce it to a rare
event, and the health card makes it visible when it happens, but if autostart is
off then nothing in this document works. This must be the first thing checked
when an alert goes missing.

**Adopt-silently trades a false alert for a missed one.** If he genuinely goes out
at 3 in the morning with both radios off and comes back at 7, the app sees a
blind window and a home network and says nothing. The step counter corroborator
catches most of these, and the confirm prompt covers the rest, but this is a
deliberate choice: a missed alert is a smaller harm than a false one, because a
false one erodes trust in every future alert.

**The confirm prompt could become noise.** If the app asks too often, he will stop
reading it, which is worse than not asking. It must be rare by construction:
after a blind window, after a reboot, on a first visit, and on a tie. If it fires
more than once or twice a week in practice, the thresholds are wrong.

**The second phone hazard.** Until every device is on the new build, an older copy
of the app signed into the same account will wipe the new per-place settings the
first time it saves a place, because the whole places array is rewritten from a
fixed key list (`FirestoreDataSource.kt:187-194,216`). Upgrade every device, or
accept that settings can vanish.

**None of the battery numbers here were measured.** They are engineering
estimates. Before quoting any of them, reset the battery statistics on the actual
phone, run overnight, and read the mobile-radio-active time against the WiFi scan
count. The expectation is that Firestore traffic dominates, and if it does not,
the priority order in section 6 should change.

**Message loss is still possible until items 3 and 4 land.** Everything else in
this plan improves *detection*. If two messages to the same person still overwrite
each other in the queue, perfect detection still ends in a message nobody
received.

---

## ISSUE DRAFTS

### Repository: ttgo-sms-app

---

**1. Arrival alert is sent in the morning after switching the phone radios back on**

Turning WiFi and mobile data back on in the morning currently sends an "arrived
home" message, hours after actually getting home, with the wrong time on it.
The app has no way of knowing it never left, so a network coming back into range
looks identical to walking in the door.
After this change the app remembers where it was before it lost the ability to
observe, and a network reappearing at a place it was already at is treated as
still being there.
No message is sent in that case.

Labels: bug

---

**2. Some days no arrival alert is sent at all**

On some days arriving home produces no message.
The main cause is the once-a-day rule combined with the false morning alert: once
any alert has been sent for a place that day, the real one later is discarded
silently.
Secondary causes are the app never restarting itself after the phone restarts,
and the monitoring switch quietly refusing to turn on when a permission prompt
was declined.
After this change an alert is allowed once per visit rather than once per day,
monitoring restarts by itself, and a refused prompt no longer disables the
feature without saying so.

Labels: bug

---

**3. Returning to a place later the same day never sends an alert**

Leaving the office, going home for a while, and coming back to the office sends
nothing for the return, because only one alert per place per day is allowed.
That rule was meant to stop repeats but it also blocks every genuine second
visit.
After this change the rule becomes one alert per visit, and a new visit only
begins after the app has actually observed leaving.
A short cooling-off period still stops repeats when the boundary flickers.

Labels: bug

---

**4. Short visits end before the alert is sent**

A stop of 10 to 15 minutes often produces nothing, because the waiting time is
effectively longer than it looks and the countdown is thrown away as soon as the
place is left.
After this change the waiting time is adjustable per place, the clock only counts
time while the phone is actually sitting still, and a brief loss of signal no
longer discards a nearly finished countdown.
A normal short visit will alert; driving past will not.

Labels: bug

---

**5. Arrival monitoring stops after the phone restarts**

After a restart, arrival detection stays off until the app is opened by hand,
which can be hours later or the next day.
There is currently nothing that starts it automatically and nothing that notices
it has stopped.
After this change monitoring starts again by itself after a restart and after an
app update, and a background check restarts it if the phone's battery manager
kills it.
The app also suppresses alerts for a short settling period after a restart so a
restart at home never produces a false arrival.

Labels: bug

---

**6. Two messages to the same person can replace each other before they are sent**

When two alerts are queued for the same phone number close together, the second
one overwrites the first and one message is silently lost, while the history
still shows both.
This affects arrival alerts, manual sends and verification codes alike.
After this change each queued message is independent and none can replace
another.
The history shows the true delivery state of each one.

Labels: bug

---

**7. An arrival is recorded as done even when some people were not reached**

When an alert goes to several people and only some of them are reached, the
arrival is marked as complete and the people who were missed never get anything,
with no retry.
After this change delivery is tracked separately for each recipient.
Only the recipients who were actually missed are retried, so nobody is silently
dropped and nobody gets the same message twice.

Labels: bug

---

**8. Two saved places within range of the same spot both send alerts**

When two saved places can hear each other's networks, for example a home above a
shop or an office across the road, both places complete their countdowns and both
send a message, so one of the two is always wrong.
After this change the app decides on a single place at a time.
If two places are too close to separate confidently, nothing is sent and the
situation is shown in the app rather than guessed at.

Labels: bug

---

**9. Adjustable sensitivity for each place**

The waiting time before an alert is currently fixed when the app is built and
cannot be changed by the user.
After this change each place has its own sensitivity setting with three choices,
a plain-English explanation of the trade-off, and an advanced section for exact
numbers.
Shorter settings alert sooner and catch short visits; longer settings are safer
where neighbouring networks overlap.
Existing places keep behaving exactly as they do today until the setting is
touched.

Labels: enhancement

---

**10. Save every network at a place in one step, and ignore weak far-away signals**

A place can only remember one network today, so a router with two bands, a mesh
system, or a router replacement quietly stops detection with no warning.
After this change a place can hold several networks, captured with one button
while standing there, and networks are confirmed across repeated visits rather
than from a single moment.
A closeness setting separates being inside the building from walking past it
outside.
The place card also warns when a saved place has not been detected for several
days, which is what a replaced router looks like.

Labels: enhancement

---

**11. Detection backs off while the phone is sitting still**

The app currently scans every two minutes around the clock, including all night
and all day at a desk, which is wasted battery for a feature that fires twice a
day.
After this change the app scans frequently only while moving or while close to a
saved place, and stops almost entirely while the phone is stationary, using the
phone's own free motion sensor to notice when it starts moving again.
The much larger saving is that the app stops contacting the server every two
minutes.
Detection speed at a real arrival is unchanged or better.

Labels: enhancement

---

**12. Show whether arrival detection is actually working, and ask when unsure**

The ongoing notification says monitoring is active even when the app has been
unable to detect anything for a week, so a broken feature is invisible.
After this change the app shows a health view with the last successful check, the
current state and when each place was last seen, plus a "test detection here"
button that explains in plain words what the app currently believes.
If detection has been blind for more than a day, the app says so.
When the app is genuinely unsure whether an arrival happened, it asks first with
a private notification that defaults to not sending.

Labels: enhancement, future-plan

---

### Repository: Door-Monitoring

---

**1. The gate accepts an open command from any nearby device**

The gate can currently be opened by any unpaired device in range that sends the
right plain-text command, with no check of who is asking.
After this change the gate issues a one-time challenge and only opens for a
device that can answer it with a shared secret, and it remembers a counter so an
old recorded command cannot be replayed.
Nothing about the existing manual controls changes.

Labels: bug, future-plan

---

**2. The gate advertises its identity and its unlock service to everyone in range**

The gate broadcasts a recognisable name together with the exact service
identifier used to open it, which tells anyone scanning nearby exactly what it is
and how it is controlled.
After this change the gate advertises as little as possible and only during the
periods when it is expecting a legitimate request.
It stays discoverable during setup.

Labels: bug, future-plan

---

**3. The gate should only listen during a short window the phone asks for**

The gate is always ready to accept an open request, which is a permanently open
surface for a feature that is needed for a few seconds a day.
After this change the gate stays closed to requests by default and only opens a
listening window of about ninety seconds when the phone signals that its owner is
genuinely approaching.
Outside that window the gate ignores everything, and the existing manual switch
that disables opening entirely stays as the final override.

Labels: future-plan

---

**4. The gate should confirm the phone is genuinely close before opening**

Once a legitimate request arrives, the gate has no independent way of judging
whether the phone is at the gate or sitting on a sofa inside the house.
After this change the gate reads the strength of the connection itself as a
second opinion and refuses to open when the phone is clearly not close, so a
mistaken request from indoors cannot open it.
This is a check the gate performs itself and does not rely on anything the phone
claims.

Labels: future-plan

---

**5. The gate cannot do timed work while it waits**

The gate's main program pauses for whole seconds at a time, so it cannot run a
listening window, a timeout, or a retry while it is doing anything else.
After this change the waiting is replaced with a timed loop so the gate can track
several things at once, which is a prerequisite for the armed window and the
automatic opening feature.
Existing behaviour of the manual controls and the web page is unchanged.

Labels: future-plan
