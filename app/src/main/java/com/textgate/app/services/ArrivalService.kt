package com.textgate.app.services

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.firebase.firestore.FirebaseFirestore
import com.textgate.app.R
import com.textgate.app.App
import com.textgate.app.core.utils.DateUtils
import com.textgate.app.core.utils.RoutineAnalyzer
import com.textgate.app.core.utils.WifiConfig
import com.textgate.app.core.utils.requestWifiScan
import com.textgate.app.core.utils.resolvePresence
import com.textgate.app.core.utils.scanBlocker
import com.textgate.app.core.utils.visibleAccessPoints
import com.textgate.app.core.utils.visibleBssids
import com.textgate.app.data.local.MonitorLogStore
import com.textgate.app.data.local.PreferencesDataSource
import com.textgate.app.domain.model.Place
import com.textgate.app.domain.model.PresenceState
import com.textgate.app.domain.model.User
import com.textgate.app.domain.repository.LinkRepository
import com.textgate.app.domain.repository.UserRepository
import com.textgate.app.domain.usecase.links.AnswerLocationRequestsUseCase
import com.textgate.app.domain.usecase.location.ArrivalOutcome
import com.textgate.app.domain.usecase.location.RecordArrivalUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.android.ext.android.inject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Watches for the WiFi networks of saved places being IN RANGE, whether or not
 * the phone joins them. Someone on mobile data all day still passes their home
 * router, so connection was never the right signal.
 *
 * Detection is a scan sweep on a fixed cadence rather than a countdown job per
 * network event: a scan that misses an access point once (which happens often)
 * no longer throws away a countdown that was nearly finished, and a service
 * restart cannot leave a timer behind that never fires.
 */
class ArrivalService : Service() {

    private val userRepo: UserRepository by inject()
    private val recordArrival: RecordArrivalUseCase by inject()
    private val linkRepo: LinkRepository by inject()
    private val answerLocationRequest: AnswerLocationRequestsUseCase by inject()
    private val prefs: PreferencesDataSource by inject()
    private val monitorLog: MonitorLogStore by inject()
    private val firestore: FirebaseFirestore by inject()
    private val routineAnalyzer = RoutineAnalyzer()

    // The log is for reading, so a condition that holds across many sweeps is
    // written once when it appears, not once per sweep it survives.
    private var lastProblem: String? = null
    // The reason looking is impossible right now and the moment it started. The
    // notice has to say how long it has been true: "WiFi is off" reads like it
    // just happened even when nothing has been observable since breakfast.
    private var currentBlocker: String? = null
    private var blockerSince = 0L
    // Concurrent because the send outlives the sweep that started it and reports
    // its outcome here while the next sweep is already writing its own rows.
    private val lastPlaceNote = ConcurrentHashMap<String, String>()

    private fun notePlace(place: Place, kind: String, message: String) {
        if (lastPlaceNote[place.id] == message) return
        lastPlaceNote[place.id] = message
        monitorLog.append(kind, "${place.label.ifBlank { place.id }}: $message")
    }

    private fun noteProblem(message: String) {
        if (lastProblem == message) return
        lastProblem = message
        monitorLog.append(MonitorLogStore.Kind.PROBLEM, message)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var sweepJob: Job? = null
    // The timer, the motion trigger and the WiFi-join callback all ask for a
    // sweep and can land within a second of each other. Two running at once
    // would both find the same place still counting down and send the whole
    // fan-out twice.
    private val sweeping = AtomicBoolean(false)
    // A restart must never read as a fresh arrival. Nothing may be sent until the
    // first observations have had time to land and the state has been re-read.
    private var startedAt = 0L
    // Settings are read from the server only while someone is in the app, plus a
    // slow safety refresh so a change made on another device still lands.
    private var cachedUser: User? = null
    private var cachedUserAt = 0L
    private var nextSweepSeconds = SWEEP_SECONDS_MOVING
    private var stillSweeps = 0

    // place id → sweeps in a row the network went missing, because scan results
    // drop an access point at random even while the phone sits next to it.
    private val missedSweeps = mutableMapOf<String, Int>()
    // Sweeps in a row the scan came back completely empty while scanning was
    // available. One empty read is nearly always the cache, not the world.
    private var emptySweeps = 0
    // When the current run of empty scans began, so a long silence can name the
    // time it started rather than claiming it is about to retry.
    private var firstEmptySweepAt = 0L

    private val connectivityManager by lazy {
        getSystemService(ConnectivityManager::class.java)
    }

    // Step count is a free hardware counter kept by the sensor hub, so reading it
    // costs almost nothing and needs no wake lock. It is used only to answer "has
    // this phone actually moved since the last sweep", which is what separates a
    // real visit from driving past. Phones without the sensor fall back to
    // counting plain elapsed time, so detection degrades rather than stops.
    private val sensorManager by lazy { getSystemService(SensorManager::class.java) }
    private val stepSensor by lazy { sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) }
    @Volatile private var stepCount = -1f
    private var lastStepCount = -1f
    private var lastSweepAt = 0L

    // A hardware trigger that runs on the sensor hub and wakes the device itself,
    // so a long back-off ends the moment the phone is genuinely picked up and
    // carried rather than at the end of the interval. It is one-shot and has to be
    // re-armed every time it fires.
    private val motionSensor by lazy { sensorManager?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION) }
    private val motionTrigger = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            Log.i(TAG, "Significant motion, looking again now")
            stillSweeps = 0
            armMotionTrigger()
            scope.launch { sweep() }
        }
    }

    private fun armMotionTrigger() {
        motionSensor?.let { sensorManager?.requestTriggerSensor(motionTrigger, it) }
    }

    private val stepListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            stepCount = event.values.firstOrNull() ?: stepCount
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    /**
     * Milliseconds since the last sweep that the phone spent still, which is zero
     * when it walked anywhere in between.
     */
    private fun stationarySinceLastSweep(now: Long): Long {
        val elapsed = if (lastSweepAt == 0L) 0L else now - lastSweepAt
        lastSweepAt = now
        val current = stepCount
        val previous = lastStepCount
        lastStepCount = current
        // No sensor, or the counter reset on a reboot: fall back to elapsed time.
        if (current < 0f || previous < 0f || current < previous) return elapsed
        return if (current - previous > STEPS_THAT_COUNT_AS_MOVING) 0L else elapsed
    }

    // Joining a WiFi network is a strong arrival hint, so sweep immediately
    // instead of waiting out the rest of the cadence. Losing one means nothing:
    // the network can still be in range.
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            scope.launch { sweep() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        startedAt = System.currentTimeMillis()
        // Second line of defence behind the check in start(): the permission can
        // also be revoked in the window between the two. Stopping cleanly beats
        // taking the whole process down.
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            Log.e(TAG, "Could not start the arrival service in the foreground", e)
            monitorLog.append(MonitorLogStore.Kind.PROBLEM,
                "Monitoring could not start, the location permission was taken away")
            isRunning = false
            stopSelf()
            return
        }
        monitorLog.append(MonitorLogStore.Kind.EVENT, "Monitoring started")
        ArrivalWatchdogReceiver.scheduleNextCheck(this)
        connectivityManager.registerNetworkCallback(buildNetworkRequest(), networkCallback)
        stepSensor?.let {
            sensorManager?.registerListener(stepListener, it, SensorManager.SENSOR_DELAY_NORMAL)
        } ?: Log.w(TAG, "No step sensor — the wait will count plain elapsed time")
        armMotionTrigger()
        startSweeping()
        flushQueuedAlerts()
        watchLocationRequests()
    }

    override fun onDestroy() {
        isRunning = false
        monitorLog.append(MonitorLogStore.Kind.EVENT, "Monitoring stopped")
        // The watchdog keeps running on purpose: the common way this method is
        // reached is the system killing the service, which is exactly the case
        // the watchdog exists to undo. It is cancelled when the user switches
        // monitoring off, not when the service goes away.
        try { connectivityManager.unregisterNetworkCallback(networkCallback) } catch (_: Exception) {}
        sensorManager?.unregisterListener(stepListener)
        motionSensor?.let { sensorManager?.cancelTriggerSensor(motionTrigger, it) }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startSweeping() {
        sweepJob?.cancel()
        sweepJob = scope.launch {
            while (isActive) {
                sweep()
                delay(nextSweepSeconds * 1000L)
            }
        }
    }

    /**
     * An alert written while offline sits in Firestore's own queue on the phone
     * until the process runs with a network again, which is usually the moment
     * this service starts. Waiting on that queue here is what turns "queued on
     * this phone" into a real send, and a wait that took a while is worth a line
     * in the log because it explains an SMS arriving late.
     */
    private fun flushQueuedAlerts() {
        scope.launch {
            runCatching {
                val waitStartedAt = System.currentTimeMillis()
                firestore.waitForPendingWrites().await()
                if (System.currentTimeMillis() - waitStartedAt > BACKLOG_WORTH_NOTING_MILLIS) {
                    monitorLog.append(MonitorLogStore.Kind.EVENT, "alerts queued earlier reached the server")
                }
            }.onFailure { Log.w(TAG, "Could not wait for the queued writes", it) }
        }
    }

    /**
     * Settings, without a network round trip on every sweep. A refresh happens on
     * the first sweep, whenever the app is open on screen, and once every few
     * hours as a safety net.
     */
    private suspend fun currentUser(): User? {
        // An edit made on this phone has to reach the engine on the next sweep,
        // not six hours later, or a place someone just fixed goes on being
        // detected with the settings it had before they fixed it.
        val stale = System.currentTimeMillis() - cachedUserAt > SETTINGS_REFRESH_MILLIS ||
            cachedUserAt < placesChangedAt
        if (cachedUser == null || App.isInForeground || stale) {
            // A read that never comes back would hold the sweep loop open, and
            // the loop is the only thing watching for arrivals. Ten seconds and
            // then carry on: a timeout counts as a refresh that did not happen,
            // so whatever was cached stays in use.
            withTimeoutOrNull(10_000) { userRepo.getCurrentUser() }?.let {
                cachedUser = it
                cachedUserAt = System.currentTimeMillis()
            }
        }
        return cachedUser
    }

    private suspend fun sweep() {
        if (!sweeping.compareAndSet(false, true)) {
            Log.d(TAG, "A sweep is already running, leaving this one to it")
            return
        }
        try {
            runSweep()
        } finally {
            sweeping.set(false)
        }
    }

    private suspend fun runSweep() {
        // A sweep that gives up here still has to say so. Leaving the last good
        // check on screen is how a signed-out phone went on looking like a
        // working one, and neither of these paths has observed anything, so
        // nothing may touch the last-observed stamp either.
        val uid = userRepo.currentFirebaseUser()?.uid ?: run {
            updateNotification("Signed out, monitoring idle")
            return
        }
        val user = currentUser() ?: run {
            updateNotification(
                "Could not read settings at ${DateUtils.time12h(System.currentTimeMillis())}, will retry")
            noteProblem("Could not read the account settings, monitoring is waiting for them")
            return
        }
        requestWifiScan(this)
        var visible = visibleAccessPoints(this)
        val blocker = scanBlocker(this)
        if (blocker != currentBlocker) {
            currentBlocker = blocker
            blockerSince = System.currentTimeMillis()
        }
        // A requested scan takes a few seconds to land, and right after a
        // service start the cache is empty. Give the request one chance to
        // land before treating the sweep as unobservable.
        if (blocker == null && visible.isEmpty()) {
            delay(SCAN_RESULTS_WAIT_MILLIS)
            visible = visibleAccessPoints(this)
        }
        val saved = user.places.filter { it.savedBssids.isNotEmpty() }

        // Not being able to look is its own answer. Inferring "away" from it is
        // what turned switching the radios on in the morning into an arrival.
        if (blocker != null) {
            Log.w(TAG, "Cannot observe: $blocker")
            nextSweepSeconds = SWEEP_SECONDS_SETTLED
            updateNotification("$blocker, since ${DateUtils.time12h(blockerSince)}")
            noteProblem(blocker)
            goBlind(saved)
            return
        }
        // Scanning should work, the scan just came back with nothing. That is
        // a cold cache or a throttled request far more often than a genuine
        // absence of networks, so retry soon instead of blaming settings that
        // are on and then sitting on the wrong message for fifteen minutes.
        if (visible.isEmpty()) {
            if (emptySweeps == 0) firstEmptySweepAt = System.currentTimeMillis()
            emptySweeps += 1
            Log.w(TAG, "Scan returned no networks although scanning is available ($emptySweeps in a row)")
            val blind = emptySweeps >= EMPTY_SWEEPS_TO_GO_BLIND
            // Looking often is worth it while a cold cache is still the likely
            // explanation. Once presence is parked there is nothing left to
            // catch quickly, so stop burning the fast cadence on silence;
            // significant motion still cuts the long wait short.
            nextSweepSeconds = if (blind) SWEEP_SECONDS_SETTLED else SWEEP_SECONDS_MOVING
            updateNotification(
                if (blind) "No WiFi heard since ${DateUtils.time12h(firstEmptySweepAt)}, cannot confirm places"
                else "No WiFi heard at ${DateUtils.time12h(System.currentTimeMillis())}, retrying soon"
            )
            noteProblem("Scan returned no networks although scanning is on, retrying soon")
            // Presence keeps its last observed state through a short gap; going
            // blind on the first empty read is what made every service restart
            // adopt the current place silently and swallow its arrival alert.
            if (blind) goBlind(saved)
            return
        }
        emptySweeps = 0
        firstEmptySweepAt = 0L
        if (lastProblem != null) {
            lastProblem = null
            monitorLog.append(MonitorLogStore.Kind.EVENT, "Able to observe again")
        }
        Log.d(TAG, "Sweep: ${visible.size} networks in range, ${saved.size} saved places")
        prefs.setLastObservedAt(System.currentTimeMillis())
        prefs.setEnvironment(visible.keys)
        // Once per sweep: it advances the step baseline, so calling it per place
        // would hand the first place the whole interval and the rest nothing.
        val stillSinceLastSweep = stationarySinceLastSweep(System.currentTimeMillis())

        // The same rule the two settings buttons ask, so the three never disagree.
        val presenceNow = resolvePresence(saved, visible)
        val winner = presenceNow.winner
        if (presenceNow.contested) {
            // Worth reading, not just worth logging: two places this close is
            // why one of them is quietly never the winner and never alerts.
            val tooClose = presenceNow.closest.joinToString {
                "${it.place.label.ifBlank { it.place.id }} at ${it.strongest} dBm"
            }
            Log.w(TAG, "Two places too close to separate: $tooClose")
            noteProblem("Two places too close to separate: $tooClose")
        }

        saved.forEach { place ->
            if (!place.alertsEnabled) {
                Log.d(TAG, "${place.id}: alerts switched off for this place")
                notePlace(place, MonitorLogStore.Kind.EVENT,
                    "alerts are switched off for this place, nothing will be sent")
                return@forEach
            }
            val presence = prefs.getPresence(place.id)
            // Audible but beaten by a nearer place counts as not being here, so a
            // losing place cannot quietly run its own countdown to an alert.
            val present = place.isPresentIn(visible) && place.id == winner?.id

            // First look after a blind spell. Hearing this place now says nothing
            // about whether the phone travelled while nobody was watching, so the
            // visit is adopted without a message either way. A missed alert is a
            // smaller harm than one that announces an arrival that never happened.
            if (presence.state == PresenceState.BLIND) {
                val resumed = if (present) {
                    Log.i(TAG, "${place.id}: audible after a blind spell (was " +
                        "${presence.stateBeforeBlind}), adopting as already here")
                    notePlace(place, MonitorLogStore.Kind.EVENT,
                        "heard again after a gap in observation, treated as already here, no alert")
                    presence.copy(state = PresenceState.HERE)
                } else {
                    Log.i(TAG, "${place.id}: not audible after a blind spell, away")
                    notePlace(place, MonitorLogStore.Kind.EVENT,
                        "not heard after a gap in observation, marked away")
                    presence.copy(state = PresenceState.AWAY, visitStartedAt = 0L, stationaryMillis = 0L)
                }
                prefs.setPresence(place.id, resumed)
                return@forEach
            }

            if (!present) {
                // Leaving has to be seen, not assumed. A power cut leaves the
                // neighbours' networks exactly where they were, so the
                // surroundings barely move and this correctly refuses to call it
                // a departure. Actually driving off changes almost all of them.
                val familiar = prefs.getPlaceEnvironment(place.id)
                val overlap = if (familiar.isEmpty()) 0f
                    else familiar.intersect(visible.keys).size.toFloat() / familiar.size
                val missed = (missedSweeps[place.id] ?: 0) + 1
                missedSweeps[place.id] = missed
                val departed = missed >= MISSED_SWEEPS_TO_LEAVE && overlap < FAMILIAR_OVERLAP_TO_STAY
                if (departed && presence.state != PresenceState.AWAY) {
                    Log.i(TAG, "${place.id}: departure observed, overlap ${"%.2f".format(overlap)}")
                    notePlace(place, MonitorLogStore.Kind.EVENT, "departure observed")
                    prefs.setPresence(place.id, presence.copy(
                        state = PresenceState.AWAY, visitStartedAt = 0L, stationaryMillis = 0L,
                    ))
                } else if (missed >= MISSED_SWEEPS_TO_LEAVE) {
                    Log.d(TAG, "${place.id}: not heard but surroundings unchanged " +
                        "(overlap ${"%.2f".format(overlap)}), still counted as here")
                    notePlace(place, MonitorLogStore.Kind.EVENT,
                        "not heard on this check, but the surroundings have not " +
                            "changed, still counted as here")
                }
                return@forEach
            }
            missedSweeps[place.id] = 0
            prefs.setPlaceEnvironment(place.id, visible.keys)

            // One alert per VISIT. The visit only ends at an observed departure,
            // which is what lets a second trip to the office on the same day
            // alert, and stops a flickering boundary alerting twice.
            if (presence.state == PresenceState.HERE) {
                Log.d(TAG, "${place.id}: already alerted for this visit")
                // The branch that silently swallowed every arrival at a place
                // whose stored state was stuck here. It has to be visible.
                notePlace(place, MonitorLogStore.Kind.EVENT,
                    "in range, already alerted for this visit, waiting for a departure")
                return@forEach
            }
            if (System.currentTimeMillis() - startedAt < SETTLING_MILLIS) {
                Log.d(TAG, "${place.id}: still settling after a restart, not alerting yet")
                notePlace(place, MonitorLogStore.Kind.EVENT,
                    "in range, but held back while monitoring settles after a restart")
                return@forEach
            }
            val sinceAlert = System.currentTimeMillis() - presence.lastAlertAt
            if (presence.lastAlertAt > 0L && sinceAlert < REARM_MINUTES * 60_000L) {
                Log.d(TAG, "${place.id}: within the ${REARM_MINUTES} minute cooling-off period")
                notePlace(place, MonitorLogStore.Kind.EVENT,
                    "in range, but inside the ${REARM_MINUTES} minute cooling-off period")
                return@forEach
            }

            val started = presence.state != PresenceState.APPROACHING
            if (started) {
                Log.i(TAG, "${place.id}: in range, starting the wait")
                notePlace(place, MonitorLogStore.Kind.EVENT, "in range, waiting for the phone to settle")
            }
            // The wait counts only the time the phone actually sat still. A 12
            // minute visit is nearly all still time; driving past contains none,
            // so a short setting stays safe instead of becoming trigger-happy.
            val stationary = presence.stationaryMillis + if (started) 0L else stillSinceLastSweep
            val running = presence.copy(
                state = PresenceState.APPROACHING,
                visitStartedAt = if (started) System.currentTimeMillis() else presence.visitStartedAt,
                stationaryMillis = stationary,
            )
            prefs.setPresence(place.id, running)

            val arrivalTimes = user.arrivalTimesByPlace[place.id] ?: emptyList()
            val dwellMinutes = place.effectiveDwellMinutes(WifiConfig.STABILITY_MINUTES)
            val waitMinutes = routineAnalyzer.effectiveWait(arrivalTimes, dwellMinutes)
            val stillMinutes = stationary / 60_000
            if (stillMinutes < waitMinutes) {
                Log.d(TAG, "${place.id}: $stillMinutes/$waitMinutes still minutes here")
                return@forEach
            }
            // Suppressing the send must not touch the countdown or the day guard,
            // or the suppression becomes the reason the next real arrival is lost.
            if (place.isQuietAt(DateUtils.minutesOfDay())) {
                Log.d(TAG, "${place.id}: inside its quiet hours, not sending")
                notePlace(place, MonitorLogStore.Kind.EVENT, "arrived inside its quiet hours, nothing sent")
                return@forEach
            }

            val routineTriggered = waitMinutes < dwellMinutes
            // Marked here whether or not the gateway accepted it, and BEFORE any
            // network work: delivery is retried per recipient from the Auto
            // page, and replaying the whole fan-out would double-message
            // everyone who was already reached. Flipping it first also closes
            // the window a slow send used to leave open, where the place was
            // still counting down and the next sweep sent everything again.
            prefs.setPresence(place.id, running.copy(
                state = PresenceState.HERE, lastAlertAt = System.currentTimeMillis(),
            ))
            // On its own coroutine so the sweep never waits on the network. A
            // phone with no signal has to keep watching for the departure that
            // re-arms this place.
            scope.launch {
                recordArrival(uid, place.id, routineTriggered, running.visitStartedAt)
                    .onSuccess { outcome -> noteArrivalOutcome(place, outcome) }
                    .onFailure {
                        Log.w(TAG, "${place.id}: arrival not sent, ${it.message}")
                        notePlace(place, MonitorLogStore.Kind.PROBLEM, "arrival alert failed, ${it.message}")
                    }
            }
        }

        chooseNextCadence(saved, movedSinceLastSweep = stillSinceLastSweep == 0L)
        val here = saved.firstOrNull { prefs.getPresence(it.id).state == PresenceState.HERE }
        // What each place sounded like on this check, kept with the row because
        // "not at a saved place" is only arguable next to the numbers that
        // produced it: one network short, or heard but too faint for its floor.
        val readings = presenceNow.readings.joinToString("\n") { reading ->
            val name = reading.place.label.ifBlank { reading.place.id }
            if (reading.heardCount == 0) "$name: 0/${reading.place.savedBssids.size} heard"
            else "$name: ${reading.heardCount}/${reading.place.savedBssids.size} heard, " +
                "strongest ${reading.strongest} dBm (floor ${reading.place.minRssi})"
        }
        monitorLog.append(MonitorLogStore.Kind.CHECK,
            "Heard ${visible.size} networks. " +
                (if (here != null) "At ${here.label.ifBlank { here.id }}." else "Not at a saved place.") +
                " Next check in ${nextSweepSeconds / 60} min.",
            readings.ifBlank { null })
        // The clock time, never "just now": a loop frozen by Doze used to keep
        // claiming it had only this moment looked, hours after its last sweep.
        val checkedAt = DateUtils.time12h(System.currentTimeMillis())
        updateNotification(
            if (here != null) "At ${here.label.ifBlank { here.id }}, checked $checkedAt"
            else "Checked $checkedAt, not at a saved place"
        )
    }

    // Says what became of each recipient. One green row used to be written
    // whatever happened, so an alert still sitting on the phone read exactly
    // like one the gateway had already taken.
    private fun noteArrivalOutcome(place: Place, outcome: ArrivalOutcome) {
        val reached = outcome.whatsAppSent + outcome.enqueued
        Log.i(TAG, "${place.id}: arrival recorded, $reached sent, " +
            "${outcome.queuedOnDevice} queued on this phone, ${outcome.failed} failed")
        if (reached > 0) {
            notePlace(place, MonitorLogStore.Kind.ALERT, "arrival alert sent to $reached recipient(s)")
        }
        if (outcome.queuedOnDevice > 0) {
            notePlace(place, MonitorLogStore.Kind.EVENT,
                "alert for ${outcome.queuedOnDevice} recipient(s) queued on this phone, waiting for network")
        }
        if (outcome.failed > 0) {
            notePlace(place, MonitorLogStore.Kind.PROBLEM,
                "arrival alert failed for ${outcome.failed} of ${outcome.total}, ${outcome.firstFailure}")
        }
    }

    private suspend fun goBlind(saved: List<Place>) {
        saved.forEach { place ->
            val presence = prefs.getPresence(place.id)
            if (presence.state != PresenceState.BLIND) {
                Log.i(TAG, "${place.id}: going blind, was ${presence.state}")
                prefs.setPresence(place.id, presence.goBlind())
            }
        }
    }

    /**
     * How long to wait before looking again. Scanning hard is only worth it while
     * something can change: moving, or already counting down at a place. Sitting
     * still with nothing pending is the case that used to burn the battery all
     * night for nothing.
     */
    private suspend fun chooseNextCadence(places: List<Place>, movedSinceLastSweep: Boolean) {
        stillSweeps = if (movedSinceLastSweep) 0 else stillSweeps + 1
        val approaching = places.any {
            prefs.getPresence(it.id).state == PresenceState.APPROACHING
        }
        nextSweepSeconds = when {
            approaching -> SWEEP_SECONDS_APPROACHING
            stillSweeps >= STILL_SWEEPS_TO_BACK_OFF -> SWEEP_SECONDS_SETTLED
            else -> SWEEP_SECONDS_MOVING
        }
    }

    // A linked account can ask where we are at any moment, so this listens for
    // the whole life of the service rather than only on WiFi changes. The answer
    // is resolved from what is in range right now, so it has to be read per
    // request.
    private fun watchLocationRequests() {
        scope.launch {
            val uid = userRepo.currentFirebaseUser()?.uid ?: return@launch
            linkRepo.watchPendingRequests(uid).collect { pending ->
                pending.forEach { request ->
                    answerLocationRequest(uid, request, visibleBssids(this@ArrivalService))
                }
            }
        }
    }

    private fun buildNetworkRequest() = NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        .build()

    // Refreshed after each sweep so the ongoing notice reports what detection is
    // really doing. Claiming "monitoring active" while blind for a week is how a
    // broken feature stayed invisible.
    private fun updateNotification(status: String) {
        runCatching {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, buildNotification(status))
        }.onFailure {
            // Swallowing this left the phone showing a status from days ago with
            // nothing anywhere saying why it had stopped moving.
            Log.w(TAG, "Could not post the status notification", it)
            noteProblem("Could not update the status notice, notifications may be blocked")
        }
    }

    private fun buildNotification(status: String = "Starting up"): Notification {
        val channelId = CHANNEL_ID
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(channelId) == null) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Arrival Monitoring", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Detects home/office WiFi arrivals" }
            )
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("TextGate")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
    }

    companion object {
        var isRunning: Boolean = false
            private set

        // When the places were last edited on this phone, so the running service
        // can tell that its cached copy is out of date.
        @Volatile
        var placesChangedAt: Long = 0L
            private set

        fun notePlacesChanged() {
            placesChangedAt = System.currentTimeMillis()
        }

        private const val TAG = "TextGateArrival"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "arrival_monitoring"
        // Two minutes keeps every sweep inside Android's scan throttle (four
        // requests per two minutes) while still being far finer than the
        // stability window it is measuring.
        // Cadence by state. Two minutes stays inside Android's scan throttle of
        // four requests per two minutes; the longer ones exist because a phone on
        // a desk all afternoon has nothing to detect and should not be scanning
        // for it. Significant motion cuts any back-off short.
        private const val SWEEP_SECONDS_MOVING = 120
        private const val SWEEP_SECONDS_APPROACHING = 60
        private const val SWEEP_SECONDS_SETTLED = 900
        // Sweeps in a row with no movement before backing off.
        private const val STILL_SWEEPS_TO_BACK_OFF = 5
        private const val SETTINGS_REFRESH_MILLIS = 6 * 60 * 60 * 1000L
        private const val MISSED_SWEEPS_TO_LEAVE = 2
        // How much of the surroundings has to still be recognisable for the phone
        // to count as not having moved. Scans drop access points at random, so
        // this is deliberately generous: below a third means somewhere else.
        private const val FAMILIAR_OVERLAP_TO_STAY = 0.33f
        // A floor under repeat alerts, never a permission to send one on its own.
        // Only an observed departure re-arms a place.
        private const val REARM_MINUTES = 45
        // Picking the phone up off a desk registers a couple of steps; walking out
        // of the building registers far more than this.
        private const val STEPS_THAT_COUNT_AS_MOVING = 12
        // Long enough for the persisted state and the first scans to be read back
        // before anything is allowed to send.
        private const val SETTLING_MILLIS = 2 * 60 * 1000L
        // How long a freshly requested scan gets to deliver results before an
        // empty cache is taken at its word.
        private const val SCAN_RESULTS_WAIT_MILLIS = 6_000L
        // Consecutive empty scans before presence is parked as unobservable.
        private const val EMPTY_SWEEPS_TO_GO_BLIND = 3
        // A queue that clears instantly had nothing in it, so only a wait longer
        // than this is reported as a backlog actually going out.
        private const val BACKLOG_WORTH_NOTING_MILLIS = 2_000L

        /**
         * Android revokes the permissions of apps that have not been opened for
         * a few months, and this app is built to run without being opened. When
         * that happens, starting a location-typed foreground service throws
         * SecurityException from inside the service, which kills the process on
         * the very first screen. The check belongs here so every caller gets
         * it: the settings screen, the launcher, and the watchdog.
         */
        fun start(context: Context) {
            if (!hasLocationPermission(context)) {
                Log.w(TAG, "Not starting monitoring: location permission is not granted")
                return
            }
            context.startForegroundService(Intent(context, ArrivalService::class.java))
        }

        // Approximate location cannot read scan results, so starting on it only
        // produces a service that watches forever and hears nothing.
        fun hasLocationPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

        fun stop(context: Context) {
            context.stopService(Intent(context, ArrivalService::class.java))
        }
    }
}
