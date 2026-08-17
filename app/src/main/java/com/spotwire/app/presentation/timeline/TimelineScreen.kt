package com.spotwire.app.presentation.timeline

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.spotwire.app.core.theme.SpotwireTheme
import com.spotwire.app.core.theme.StatusBlocked
import com.spotwire.app.core.theme.StatusFailed
import com.spotwire.app.core.theme.StatusInProgress
import com.spotwire.app.core.theme.StatusPending
import com.spotwire.app.core.theme.StatusSent
import com.spotwire.app.core.theme.StatusUnknown
import com.spotwire.app.core.utils.DateUtils
import com.spotwire.app.data.local.VisitLogStore
import org.koin.androidx.compose.koinViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Where the day went: how the time split across the saved places, and then the
 * same day read top to bottom as the run of stops that produced it.
 *
 * Everywhere that was never saved is one slice called "Unknown place", and any
 * stretch nobody was watching is kept out of the chart and named underneath it,
 * so the percentages add up instead of quietly hiding a night with the radios
 * switched off.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineScreen(viewModel: TimelineViewModel = koinViewModel()) {
    val uiState by viewModel.uiState.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text("Timeline", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(10.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            TimelineWindow.entries.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = uiState.window == option,
                    onClick = { viewModel.load(option) },
                    shape = SegmentedButtonDefaults.itemShape(index, TimelineWindow.entries.size),
                ) { Text(option.label) }
            }
        }
        Spacer(Modifier.height(12.dp))
        TimelineContent(uiState)
    }
}

@Composable
private fun TimelineContent(uiState: TimelineUiState) {
    // One colour per place for the whole page, so a slice and its dots on the
    // rail are recognisably the same place without a legend beside each one.
    val colorByLabel = remember(uiState.totals) {
        uiState.totals.mapIndexed { index, (label, _) ->
            label to if (label == VisitLogStore.UNKNOWN_LABEL) StatusUnknown
                else SLICE_COLORS[index % SLICE_COLORS.size]
        }.toMap()
    }

    when {
        uiState.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        uiState.stretches.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                uiState.error ?: "Nothing recorded yet. Stops appear once arrival monitoring " +
                    "has been running for a while.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
        }
        else -> LazyColumn(Modifier.fillMaxSize()) {
            item {
                TimeSpentCard(uiState, colorByLabel)
                Spacer(Modifier.height(16.dp))
                Text(
                    "Where the time went",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(6.dp))
            }
            items(uiState.stretches, key = { "${it.placeId}-${it.from}" }) { stretch ->
                StretchRow(stretch, colorByLabel[stretch.label] ?: StatusUnknown)
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun TimeSpentCard(uiState: TimelineUiState, colorByLabel: Map<String, Color>) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PieChart(
                slices = uiState.totals.map { (label, millis) ->
                    (colorByLabel[label] ?: StatusUnknown) to millis
                },
                modifier = Modifier.size(120.dp),
            )
            Spacer(Modifier.width(16.dp))
            // The legend takes the rest of the row rather than sitting under the
            // chart, which would leave the right half of the card empty.
            Column(Modifier.weight(1f)) {
                uiState.totals.forEach { (label, millis) ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(10.dp).clip(CircleShape)
                                .background(colorByLabel[label] ?: StatusUnknown)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            label,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            duration(millis),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                }
                if (uiState.untrackedMillis > 0L) {
                    Text(
                        "Not tracked: ${duration(uiState.untrackedMillis)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                }
            }
        }
    }
}

/**
 * A ring rather than a filled circle: the slices stay readable at this size and
 * the hole carries the total, which is the number most people are after.
 */
@Composable
private fun PieChart(slices: List<Pair<Color, Long>>, modifier: Modifier = Modifier) {
    val total = slices.sumOf { it.second }.coerceAtLeast(1L)
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val thickness = size.minDimension * 0.22f
            val inset = thickness / 2f
            var startAngle = -90f
            slices.forEach { (color, millis) ->
                val sweep = 360f * millis.toFloat() / total.toFloat()
                drawArc(
                    color = color,
                    startAngle = startAngle,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - thickness, size.height - thickness),
                    style = Stroke(width = thickness, cap = StrokeCap.Butt),
                )
                startAngle += sweep
            }
        }
        Text(duration(total), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * One stop on the rail. The clock time sits on the left of the dot and the place
 * on its right, so the column of times reads as a clock down the page.
 */
@Composable
private fun StretchRow(stretch: Stretch, color: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Text(
            rowTime(stretch.from),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            modifier = Modifier.width(84.dp),
        )
        Box(
            Modifier.padding(top = 4.dp).size(10.dp).clip(CircleShape)
                .background(if (stretch.tracked) color else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f))
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                stretch.label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (stretch.tracked) 0.9f else 0.5f),
            )
            Text(
                // "About", because a check runs every few minutes at best and
                // the phone dozing between two of them stretches that further.
                "about ${duration(stretch.millis)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
        }
    }
}

// Six fixed colours rather than the theme's, because dynamic colour is on and
// two slices picked from one accent would be indistinguishable.
private val SLICE_COLORS = listOf(
    StatusInProgress, StatusSent, StatusPending, StatusBlocked, StatusFailed, StatusUnknown,
)

private fun duration(millis: Long): String {
    val minutes = millis / 60_000
    return when {
        minutes < 1 -> "under a minute"
        minutes < 60 -> "${minutes}m"
        minutes % 60 == 0L -> "${minutes / 60}h"
        else -> "${minutes / 60}h ${minutes % 60}m"
    }
}

// A stop from an earlier day carries its date: a bare clock time read the next
// morning looks like something that happened today.
private fun rowTime(at: Long): String {
    val date = Date(at)
    return if (DateUtils.dayString(date) == DateUtils.todayString())
        SimpleDateFormat("h:mm a", Locale.US).format(date)
    else SimpleDateFormat("d MMM h:mm a", Locale.US).format(date)
}

@Preview(showBackground = true, name = "Timeline — a day")
@Composable
private fun TimelinePreview() {
    val now = System.currentTimeMillis()
    val hour = 60 * 60 * 1000L
    SpotwireTheme {
        TimelineContent(
            TimelineUiState(
                isLoading = false,
                stretches = listOf(
                    Stretch("home", "Home", now - hour, now),
                    Stretch(VisitLogStore.UNKNOWN_ID, "Unknown place", now - 2 * hour, now - hour),
                    Stretch("office", "Office", now - 8 * hour, now - 2 * hour),
                ),
                totals = listOf("Office" to 6 * hour, "Home" to hour, "Unknown place" to hour),
                untrackedMillis = 3 * hour,
            )
        )
    }
}
