# V5: timeline, shared history, and live location

Version 1.9.0 (versionCode 16). Six changes, one per tracker issue: #30 to #35.

## 1. Where the day went (#30, #31)

### What the phone records

`data/local/VisitLogStore.kt` keeps a week of **stays** in
`filesDir/place_visits.jsonl`, one JSON object per line, modelled on
`MonitorLogStore`. A stay is `{place, label, from, to}`.

The single entry point is `note(at, placeId, label)`. It extends the open stay
when the place is the same and observation never lapsed; otherwise it closes
that stay and opens a new one, returning the closed one so the caller can put it
on the account. There is no separate `close()`: a stay's `to` is always its last
observation, which is exactly what a process the system killed leaves behind.

Two ids are not real places:

- `__unknown` / "Unknown place": somewhere that was never saved. It is a stay
  in its own right, because "he was somewhere for two hours" is an answer.
- Nothing at all is written when the phone cannot look (WiFi off, location off,
  permission gone). The **gap** is the honest record, and the Timeline page
  fills gaps longer than twenty minutes with a "Not tracked" row.

### Where the samples come from

| Caller | When | Why it is needed |
|---|---|---|
| `ArrivalService.runSweep` | every sweep, after `resolvePresence` | the main source |
| `ArrivalService.onGeofenceEnter` | a fence crossing | a place with no saved networks is never audible, so a crossing is the only evidence it will ever produce |
| `ArrivalService.onGeofenceExit` | a confirmed departure | closes the stay |
| `ArrivalWatchdogReceiver` | the fifteen-minute alarm | in geofence mode the service is stopped most of the time; without this a fenced phone records a blank week |

The sweep uses `here ?: confirming ?: winner`. The first two are the presence
machine's settled answer; `winner` is the fallback that covers a place the
machine never advances, such as one whose alerts are switched off.

The presence state machine was deliberately **not** used as the source. It is an
*alert* state machine: a place with alerts off returns before any presence write,
and so do the restart hold, the cooling-off window and quiet hours. A timeline
built on it would mark somebody away for a whole visit inside their own quiet
window.

### Where the account records it

`ttgo_users/{uid}/place_visits/{autoId}`:

```
place_id     string   "__unknown" for somewhere never saved
place_label  string   denormalised, see below
started_at   number   epoch millis
ended_at     number   epoch millis
```

Only stays of five minutes or more are mirrored, so a phone bouncing on a
boundary does not write a document per wobble. Thirty days are kept, pruned once
a day from the watchdog tick (`prunePlaceVisits`); there is no server actor and
no console policy, so the owning phone is the only thing keeping this bounded.

**The label travels with the row on purpose.** The user document that holds the
place list is owner-only, and a Firestore read grant is per document and never
per field, so a reader handed a place id alone could never turn it into a name.

### The page

`presentation/timeline/` is a fifth bottom-nav destination. A ring chart of time
per place with the legend beside it (not under it: that would leave the right
half of the card empty), then the day as a vertical rail with the clock time on
the left of each dot and the place on the right.

Untracked time is **excluded from the chart** and named on a line under it. Left
in, a night with the radios off would be the largest slice of the day.

Segment boundaries are accurate to a sweep interval at best and to a Doze window
at worst, which is why every duration on the page reads "about".

## 2. Who else can read it (#32)

Grants live on the OWNER's link document, never on the reader's, so taking one
back is a write to one's own document. `firestore.rules` deliberately keeps
every `perm_*` field out of what the other party may write.

- `perm_visit_log: true`, a guardian. Reads everything.
- `visit_log_place_ids: [...]`, a per-place contact. Reads those places only.

A guardian's condition does not depend on the row, so they may list the whole
subcollection. A per-place contact's does, so **their query must carry
`whereEqualTo("place_id", …)`**: a Firestore list is allowed only when the rule
holds for every row the query could return, so an unfiltered read is refused
outright rather than quietly trimmed. `firestore.indexes.json` carries the
composite index that query needs.

A guardian who has no Spotwire account cannot be shown anything: the grant needs
a uid and the guardian list is phone numbers. The "Alert in app" button on each
guardian row is the bridge: it resolves the number through `phone_directory`
and creates the link.

## 3. Live position and the WiFi around it (#35)

This reverses the older "a linked person only ever sees a place name" stance,
and only behind an explicit second permission that is off by default.

A location request gains `mode` (`place` or `precise`) and `stop_requested`. A
precise request stays **pending** and collects readings in
`location_requests/{id}/answers/{autoId}`:

```
at           number
latitude     double
longitude    double
accuracy_m   double
place_label  string   "" when not at a saved place
networks     array    "name|hardware id|signal", twenty loudest
```

Three things bound it, and all three are needed:

1. `perm_precise_location` on the owner's link document. Turning off "Ask where
   I am right now" takes it with it.
2. `stop_requested`, the one field the asker may write. Closing the panel writes
   it, so a request cannot be left running by walking away from the screen.
3. A thirty-minute ceiling the answering phone enforces itself, because the
   asker's phone may never come back.

Constraints that shaped the implementation:

- **The answer loop never calls `startScan`.** Android allows four sweeps per
  two minutes and the arrival engine's entire cadence is built around that
  budget. `visibleNetworks` reads the cache the sweeps keep warm.
- **One-shot fix, never a stream.** `getCurrentLocation` with
  `PRIORITY_HIGH_ACCURACY` inside a twenty-second timeout. Lifting
  `requestLocationUpdates` out of a screen and into the service is the exact
  background use Play removes apps for.
- **The service must stay alive.** `stopIfOnlyFencesAreNeeded` yields while a
  live request is open. This is the one place the geofence battery win is given
  up, and only for the life of the request. The ongoing notification says the
  location is being shared, so the person being followed can see it.
- **There is no push of any kind in this app.** No FCM, no WorkManager. A
  request opened while the other phone's app is closed and its places are fully
  fenced waits for the fifteen-minute watchdog tick, which now starts the
  service when a request is waiting. The dialog says so.

## 4. Guardians and per-contact WhatsApp (#33, #34)

`User.guardianNumber` became `User.guardianNumbers`. Firestore keeps
`guardian_numbers` and still writes `guardian_number` as the first entry, so a
phone on an older build keeps alerting the main guardian. An account that has
never been re-saved reads the old field and becomes a list of one.

`PlaceContact` gained `whatsApp`, defaulting to true so nothing changed for an
existing contact. Ticked means WhatsApp when the account's own gateway works and
a text when it does not; unticked means always a text, for somebody who does not
use WhatsApp. The tick is only shown once a gateway is connected, reusing the
`waConfigured` flag the place editor already had. A WhatsApp failure now logs
before falling through to SMS; it used to be silent, which made a dead gateway
indistinguishable from a contact who had never wanted WhatsApp.

## 5. Still open

- A per-place contact must have a Spotwire account and an accepted link before
  the place grant does anything.
- A WhatsApp arrival row is still written as permanently `sent` with the gateway
  message id thrown away, so the tick is a promise nothing checks. The manual
  path already does this properly and is the shape to copy.
- `RecordArrivalUseCase` still does not check `isPakistaniMobile`, so unticking
  WhatsApp for a foreign number queues a text the device can never deliver.
