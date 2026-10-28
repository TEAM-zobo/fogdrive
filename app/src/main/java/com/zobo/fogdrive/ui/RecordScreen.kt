package com.zobo.fogdrive.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.outlined.PlayCircle
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import java.io.File
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AltRoute
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Landscape
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
import androidx.compose.ui.unit.dp
import com.zobo.fogdrive.data.TripEntity
import com.zobo.fogdrive.geo.Area
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RecordScreen(viewModel: MainViewModel, onShowTrip: (TripEntity) -> Unit) {
    val summary by viewModel.summary.collectAsState()
    val trips by viewModel.trips.collectAsState()
    val fmt = remember { SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA) }
    val context = LocalContext.current
    val card by viewModel.shareCard.collectAsState()
    val cardMessage by viewModel.shareCardMessage.collectAsState()
    card?.let { ShareCardDialog(it, onClose = { viewModel.shareCard.value = null }) }
    val lapse by viewModel.timelapse.collectAsState()
    lapse?.let { TimelapseDialog(it, onClose = { viewModel.timelapse.value = null }) }
    val reports by viewModel.reports.collectAsState()

    Column(Modifier.fillMaxSize().padding(40.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { ScreenTitle("나의 개척 기록", "Lv.${summary.level} · 경험치 ${summary.xp}") }
            OutlinedButton(onClick = { viewModel.openTimelapse() }, shape = RoundedCornerShape(4.dp)) {
                Icon(Icons.Outlined.PlayCircle, contentDescription = null, tint = FogColors.Accent)
                Text("개척 타임랩스", modifier = Modifier.padding(start = 8.dp, top = 6.dp, bottom = 6.dp), color = FogColors.TextMain)
            }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(onClick = { viewModel.makeShareCard(context) }, shape = RoundedCornerShape(4.dp)) {
                Icon(Icons.Outlined.Share, contentDescription = null, tint = FogColors.Accent)
                Text("내 탐험 지도 카드", modifier = Modifier.padding(start = 8.dp, top = 6.dp, bottom = 6.dp), color = FogColors.TextMain)
            }
        }
        cardMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = FogColors.Amber) }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.fillMaxWidth()) {
            StatTile("밝힌 면적", Area.format(summary.areaKm2 * 1_000_000), Modifier.weight(1f), FogColors.Accent, Icons.Outlined.Landscape)
            val totalMeters = remember(trips) { trips.sumOf { it.distanceMeters } }
            StatTile("주행 거리", formatKm(totalMeters), Modifier.weight(1f), FogColors.TextMain, Icons.Outlined.Route)
            StatTile("하차 지점", "${summary.landings}곳", Modifier.weight(1f), FogColors.Amber, Icons.Outlined.Flag)
            StatTile("새 길 비율", formatPercent(summary.newRoadRatio), Modifier.weight(1f), FogColors.Blue, Icons.Outlined.AltRoute)
        }
        if (reports.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.fillMaxWidth()) {
                for (r in reports) ReportCard(r, Modifier.weight(1f))
            }
        }
        SectionHeader("주행 기록") {
            Text("눌러서 지도에서 경로 보기", style = MaterialTheme.typography.bodyMedium, color = FogColors.TextSub)
        }
        if (trips.isEmpty()) {
            FogCard(Modifier.fillMaxWidth(), outlined = true) {
                Text("아직 주행 기록이 없어요. 시동을 걸고 달리면 자동으로 기록돼요.", color = FogColors.TextSub)
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(trips, key = { it.id }) { trip ->
                FogCard(Modifier.fillMaxWidth(), padding = 20.dp, onClick = { onShowTrip(trip) }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        IconBadge(Icons.Outlined.Route, FogColors.Accent, size = 44.dp)
                        Text(fmt.format(Date(trip.startedAt)), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text(formatKm(trip.distanceMeters), style = MaterialTheme.typography.bodyLarge, color = FogColors.TextSub)
                        Pill("+${Area.formatCells(trip.newCells)}", FogColors.Accent)
                        val ratio = if (trip.distanceMeters > 0) trip.newDistanceMeters / trip.distanceMeters else 0.0
                        Pill("새 길 ${formatPercent(ratio)}", FogColors.Blue)
                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = FogColors.TextSub)
                    }
                }
            }
        }
    }
}

/** This week (or month) against the one before: area, new 읍·면·동, distance, trips. */
@Composable
private fun ReportCard(report: PeriodReport, modifier: Modifier = Modifier) {
    val now = report.current
    val before = report.previous
    FogCard(modifier, padding = 20.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${report.title} 리포트", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text("${report.previousTitle} 대비", style = MaterialTheme.typography.labelMedium, color = FogColors.TextSub)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
            ReportFigure("밝힌 면적", Area.format(now.areaM2.toDouble()), trend(now.areaM2, before.areaM2), Modifier.weight(1f))
            ReportFigure("새 읍·면·동", "${now.newDongs}곳", trend(now.newDongs, before.newDongs), Modifier.weight(1f))
            ReportFigure("주행", formatKm(now.distanceM.toDouble()), trend(now.distanceM, before.distanceM), Modifier.weight(1f))
            ReportFigure("주행 횟수", "${now.trips}회", trend(now.trips, before.trips), Modifier.weight(1f))
        }
    }
}

/** "▲ 32%", "▼ 10%", "–" (no change), or "새 기록" when the earlier window had none. */
@Composable
private fun trend(now: Int, before: Int): Pair<String, androidx.compose.ui.graphics.Color> = when {
    now == before -> "–" to FogColors.TextSub
    before <= 0 -> "새 기록" to FogColors.Accent
    now > before -> "▲ ${((now - before) * 100L / before)}%" to FogColors.Accent
    else -> "▼ ${((before - now) * 100L / before)}%" to FogColors.TextSub
}

@Composable
private fun ReportFigure(label: String, value: String, change: Pair<String, androidx.compose.ui.graphics.Color>, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = FogColors.TextSub)
        Text(value, style = MaterialTheme.typography.titleLarge, color = FogColors.TextMain, maxLines = 1)
        Text(change.first, style = MaterialTheme.typography.labelMedium, color = change.second)
    }
}

/** Preview of the saved card, with a share button when the car has an app that takes images. */
@Composable
private fun ShareCardDialog(file: File, onClose: () -> Unit) {
    val context = LocalContext.current
    // Half size is plenty for the preview and a quarter of the memory.
    val preview = remember(file) {
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = 2 })?.asImageBitmap()
    }
    val share = remember(file) {
        val uri = runCatching { FileProvider.getUriForFile(context, "${context.packageName}.cards", file) }.getOrNull()
        uri?.let {
            Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, it)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .takeIf { i -> i.resolveActivity(context.packageManager) != null }
        }
    }
    Dialog(onDismissRequest = onClose) {
        // A dialog is its own window, outside the root's click sounds.
        FogCard(Modifier.width(860.dp).clickSounds(), padding = 28.dp) {
            Text("내 탐험 지도 카드", style = MaterialTheme.typography.headlineSmall)
            preview?.let { Image(it, contentDescription = "내 탐험 지도 카드", modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)) }
            Text(
                if (share != null) "이미지로 저장했어요. 공유 버튼으로 보낼 수 있어요."
                else "이미지로 저장했어요. USB나 adb로 옮길 수 있어요:\n${file.path}",
                style = MaterialTheme.typography.bodyMedium,
                color = FogColors.TextSub,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                share?.let { intent ->
                    Button(
                        onClick = { context.startActivity(Intent.createChooser(intent, "내 탐험 지도 공유").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                        shape = RoundedCornerShape(4.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = FogColors.Accent, contentColor = FogColors.OnAccent),
                    ) { Text("공유", modifier = Modifier.padding(8.dp)) }
                }
                OutlinedButton(onClick = onClose, shape = RoundedCornerShape(4.dp)) { Text("닫기", modifier = Modifier.padding(8.dp)) }
            }
        }
    }
}
