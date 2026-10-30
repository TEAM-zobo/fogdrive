package com.zobo.burung.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zobo.burung.data.TripEntity
import com.zobo.burung.explore.ChallengePeriod
import com.zobo.burung.geo.Area
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The map tab's "최근 주행" button: today's count and distance, opening [TripsPanel]. Sits under the
 * map header while the panel is closed.
 */
@Composable
fun TripsButton(viewModel: MainViewModel, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val trips by viewModel.trips.collectAsState()
    val today = remember(trips) { trips.filter { isSameDay(it.startedAt, System.currentTimeMillis()) } }
    Row(
        modifier.shadow(6.dp, RoundedCornerShape(36.dp)).clip(RoundedCornerShape(36.dp)).background(FogColors.Surface)
            .clickable(onClick = onOpen).padding(start = 12.dp, end = 22.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconBadge(Icons.Outlined.Route, FogColors.Accent, size = 44.dp)
        Column {
            Text("최근 주행", style = MaterialTheme.typography.titleMedium, color = FogColors.TextMain)
            Text(
                if (today.isEmpty()) "오늘은 아직 안 달렸어요" else "오늘 ${today.size}회 · ${formatKm(today.sumOf { it.distanceMeters })}",
                style = MaterialTheme.typography.bodyMedium,
                color = FogColors.TextSub,
            )
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = FogColors.TextSub)
    }
}

/**
 * Recent trips over the left of the map: this week in one line, then each day's trips. A trip picked
 * here is drawn on the map (framed right of the panel) and opens in place with its details.
 */
@Composable
fun TripsPanel(viewModel: MainViewModel, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val trips by viewModel.trips.collectAsState()
    val names by viewModel.tripNames.collectAsState()
    val stamps by viewModel.tripStamps.collectAsState()
    val reports by viewModel.reports.collectAsState()
    val shown by viewModel.shownTrip.collectAsState()
    val summary by viewModel.summary.collectAsState()
    val days = remember(trips) { trips.groupBy { dayKey(it.startedAt) }.toList() }
    val dayFmt = remember { SimpleDateFormat("M월 d일 (E)", Locale.KOREA) }
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.KOREA) }

    Column(
        modifier.shadow(16.dp).background(FogColors.Bg).padding(start = 24.dp, end = 24.dp, top = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Route, contentDescription = null, tint = FogColors.Accent, modifier = Modifier.size(32.dp))
            Text("최근 주행", style = MaterialTheme.typography.headlineSmall, color = FogColors.TextMain, modifier = Modifier.padding(start = 10.dp).weight(1f))
            Box(
                Modifier.size(52.dp).clip(CircleShape).background(FogColors.Surface).clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, contentDescription = "닫기", tint = FogColors.TextSub, modifier = Modifier.size(32.dp))
            }
        }
        reports.firstOrNull { it.period == ChallengePeriod.WEEK }?.let { WeekLine(it) }
        if (trips.isEmpty()) {
            Text("아직 주행 기록이 없어요. 시동을 걸고 달리면 자동으로 기록돼요.", color = FogColors.TextSub)
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            days.forEach { (day, list) ->
                item(key = "day$day") {
                    Row(Modifier.padding(top = 8.dp, start = 4.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        val first = list.first().startedAt
                        Text(
                            (if (isSameDay(first, System.currentTimeMillis())) "오늘 · " else "") + dayFmt.format(Date(first)),
                            style = MaterialTheme.typography.titleMedium,
                            color = FogColors.TextMain,
                        )
                        Text("${list.size}회 · ${formatKm(list.sumOf { it.distanceMeters })}", style = MaterialTheme.typography.bodyMedium, color = FogColors.TextSub)
                    }
                }
                items(list, key = { it.id }) { trip ->
                    val open = shown?.trip?.id == trip.id
                    TripRow(
                        trip,
                        time = timeFmt.format(Date(trip.startedAt)),
                        name = names[trip.id] ?: "주행",
                        stamps = stamps[trip.id].orEmpty(),
                        open = open,
                        noRoute = open && shown?.isTrack == false,
                        onClick = { if (open) viewModel.shownTrip.value = null else viewModel.showTrip(trip, leftInset = PANEL_SHARE) },
                    )
                }
            }
            // The totals the record screen used to show, in one quiet block at the end (QA reads them).
            item(key = "totals") {
                Column(Modifier.padding(top = 16.dp, bottom = 24.dp, start = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("전체 기록", style = MaterialTheme.typography.titleSmall, color = FogColors.TextSub)
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Total("밝힌 면적", Area.format(summary.areaKm2 * 1_000_000))
                        Total("하차 지점", "${summary.landings}곳")
                        Total("새 길 비율", formatPercent(summary.newRoadRatio))
                    }
                }
            }
        }
    }
}

/** Share of the map's width the panel covers (it is [PANEL_WIDTH] of a ~1,580 dp map). */
private const val PANEL_SHARE = 0.38
val PANEL_WIDTH = 580.dp

@Composable
private fun Total(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = FogColors.TextSub)
        Text(value, style = MaterialTheme.typography.titleSmall, color = FogColors.TextMain)
    }
}

/** "이번 주 3회 · 84km · 새 동 9곳", and the distance against last week. */
@Composable
private fun WeekLine(r: PeriodReport) {
    val now = r.current
    val before = r.previous
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(Brush.horizontalGradient(listOf(FogColors.Accent.copy(alpha = 0.12f), FogColors.Amber.copy(alpha = 0.12f))))
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(r.title, style = MaterialTheme.typography.labelMedium, color = FogColors.Accent)
            Text("${now.trips}회 · ${formatKm(now.distanceM.toDouble())} · 새 동 ${now.newDongs}곳", style = MaterialTheme.typography.titleLarge, color = FogColors.TextMain)
        }
        val change = when {
            now.distanceM == before.distanceM -> null
            before.distanceM <= 0 -> "새 기록"
            now.distanceM > before.distanceM -> "${r.previousTitle}보다 ▲${(now.distanceM - before.distanceM) * 100L / before.distanceM}%"
            else -> "${r.previousTitle}보다 ▼${(before.distanceM - now.distanceM) * 100L / before.distanceM}%"
        }
        change?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = FogColors.TextSub) }
    }
}

@Composable
private fun TripRow(
    trip: TripEntity,
    time: String,
    name: String,
    stamps: List<com.zobo.burung.record.StampCode>,
    open: Boolean,
    noRoute: Boolean,
    onClick: () -> Unit,
) {
    val ratio = if (trip.distanceMeters > 0) trip.newDistanceMeters / trip.distanceMeters else 0.0
    FogCard(
        Modifier.fillMaxWidth(),
        color = if (open) FogColors.Accent.copy(alpha = 0.06f) else FogColors.Surface,
        padding = 18.dp,
        borderColor = if (open) FogColors.Accent else null,
        onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(time, style = MaterialTheme.typography.bodyMedium, color = FogColors.TextSub, maxLines = 1, modifier = Modifier.width(64.dp))
            Text(name, style = MaterialTheme.typography.titleMedium, color = FogColors.TextMain, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (stamps.isNotEmpty()) Pill("스탬프 ${stamps.size}", FogColors.Amber)
            Text(formatKm(trip.distanceMeters), style = MaterialTheme.typography.bodyMedium, color = FogColors.TextSub)
        }
        if (open) {
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                trip.endedAt?.let { end -> Detail("걸린 시간", "${((end - trip.startedAt) / 60_000).coerceAtLeast(1)}분", Modifier.weight(1f)) }
                Detail("새로 개척한 길", formatPercent(ratio), Modifier.weight(1f))
                Detail("밝힌 면적", "+${Area.formatCells(trip.newCells)}", Modifier.weight(1f))
            }
            if (stamps.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("받은 스탬프", style = MaterialTheme.typography.bodyMedium, color = FogColors.TextSub)
                    stamps.take(3).forEach { Pill(it.title, FogColors.Amber) }
                    if (stamps.size > 3) Pill("+${stamps.size - 3}", FogColors.TextSub)
                }
            }
            if (noRoute) {
                Text("경로 기록 기능 전의 주행이라 이때 새로 밝힌 곳만 점으로 보여 줘요.", style = MaterialTheme.typography.bodyMedium, color = FogColors.TextSub)
            }
        }
    }
}

@Composable
private fun Detail(label: String, value: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(FogColors.SurfaceHigh).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = FogColors.TextSub)
        Text(value, style = MaterialTheme.typography.titleMedium, color = FogColors.TextMain)
    }
}

private fun dayKey(at: Long): Int = Calendar.getInstance().run {
    timeInMillis = at
    get(Calendar.YEAR) * 1000 + get(Calendar.DAY_OF_YEAR)
}

private fun isSameDay(a: Long, b: Long) = dayKey(a) == dayKey(b)
