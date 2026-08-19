package com.spotwire.app.presentation.timeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotwire.app.data.local.VisitLogStore
import com.spotwire.app.domain.repository.LinkRepository
import com.spotwire.app.domain.repository.UserRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * One stretch of the day as the page draws it. A gap in the record is simply not
 * drawn: every row carries its own start time, so a jump from one row to the
 * next is still visible.
 */
data class Stretch(
    val placeId: String,
    val label: String,
    val from: Long,
    val to: Long,
) {
    val millis: Long get() = to - from
}

// How far back the page is showing.
enum class TimelineWindow(val label: String, val days: Int) {
    TODAY("Today", 1),
    WEEK("7 days", 7),
}

data class TimelineUiState(
    val isLoading: Boolean = true,
    val window: TimelineWindow = TimelineWindow.TODAY,
    val stretches: List<Stretch> = emptyList(),
    // Place label to time spent there, longest first.
    val totals: List<Pair<String, Long>> = emptyList(),
    val error: String? = null,
)

class TimelineViewModel(
    private val visitLog: VisitLogStore,
    private val userRepo: UserRepository,
    private val linkRepo: LinkRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TimelineUiState())
    val uiState: StateFlow<TimelineUiState> = _uiState.asStateFlow()

    // Set when the page is showing somebody else's timeline instead of this
    // phone's own. A place id narrows it to the one place that person is
    // trusted with, which is also the only query their grant can satisfy.
    private var otherUid: String? = null
    private var onlyPlaceId: String? = null

    init {
        load(TimelineWindow.TODAY)
    }

    fun showAccount(uid: String, placeId: String?) {
        otherUid = uid
        onlyPlaceId = placeId
        load(_uiState.value.window)
    }

    fun load(window: TimelineWindow) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, window = window, error = null)
            val from = startOf(window)
            val theirUid = otherUid
            if (theirUid != null) {
                showStretches(fromAccount(theirUid, from, onlyPlaceId), window)
                return@launch
            }
            // For my own timeline the phone's copy is the source: it is
            // complete, it needs no network, and it carries the stay that is
            // still going on. The account copy only matters on a phone that has
            // just been set up.
            val stays = withContext(Dispatchers.IO) { visitLog.stays() }
                .filter { it.to >= from }
                .sortedBy { it.from }
            val stretches = if (stays.isNotEmpty()) fill(stays, from)
                else fromAccount(userRepo.currentFirebaseUser()?.uid, from, null)
            showStretches(stretches, window)
        }
    }

    private fun showStretches(stretches: List<Stretch>, window: TimelineWindow) {
        _uiState.value = _uiState.value.copy(
            isLoading = false,
            window = window,
            stretches = stretches.reversed(),
            totals = stretches
                .groupBy { it.label }
                .map { (label, rows) -> label to rows.sumOf { it.millis } }
                .sortedByDescending { it.second },
        )
    }

    /**
     * The month kept on the account. This is the whole story for somebody else's
     * timeline, and the fallback for my own on a phone that has just been set up
     * and has no stays of its own yet.
     */
    private suspend fun fromAccount(
        uid: String?,
        from: Long,
        placeId: String?,
    ): List<Stretch> {
        if (uid == null) return emptyList()
        val visits = runCatching {
            if (otherUid == null) userRepo.getPlaceVisits(uid, from).first()
            else linkRepo.visitsOf(uid, from, placeId).first()
        }.onFailure {
            // A refusal here means the grant was taken back, which has to be
            // said. An empty page would read as "they went nowhere".
            _uiState.value = _uiState.value.copy(
                error = if (otherUid == null) "Could not read your timeline from the server"
                else "They are no longer sharing this with you",
            )
        }.getOrNull().orEmpty()
        if (visits.isEmpty()) return emptyList()
        val stays = visits.map { VisitLogStore.Stay(it.placeId, it.label, it.startedAt, it.endedAt) }
            .sortedBy { it.from }
        // Somebody trusted with one place is shown only the stays at it, which
        // is the same drawing job as a whole day of them.
        return fill(stays, from)
    }

    // Clamped into the window, so a stay that began before it does not stretch
    // the chart back past midnight.
    private fun fill(stays: List<VisitLogStore.Stay>, from: Long): List<Stretch> =
        stays.map { stay ->
            val start = maxOf(stay.from, from)
            Stretch(stay.placeId, stay.label, start, maxOf(stay.to, start))
        }

    private fun startOf(window: TimelineWindow): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_YEAR, -(window.days - 1))
    }.timeInMillis
}
