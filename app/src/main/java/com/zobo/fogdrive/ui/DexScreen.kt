package com.zobo.fogdrive.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.FamilyRestroom
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.NightsStay
import androidx.compose.material.icons.outlined.Umbrella
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.zobo.fogdrive.explore.ProvinceProgress
import com.zobo.fogdrive.explore.RegionStat
import com.zobo.fogdrive.record.StampCode

@Composable
fun DexScreen(viewModel: MainViewModel, onShowOnMap: (RegionStat) -> Unit) {
    val regions by viewModel.regions.collectAsState()
    val provinceCode by viewModel.dexProvince.collectAsState()
    val districtCode by viewModel.dexDistrict.collectAsState()
    val province = regions?.firstOrNull { it.code == provinceCode && it.visited }
    val district = province?.districts?.firstOrNull { it.code == districtCode && it.visited }
    val toCountry = { viewModel.dexProvince.value = null; viewModel.dexDistrict.value = null }
    val toProvince = { viewModel.dexDistrict.value = null }
    // Back steps up one level (읍·면·동 → 시·군·구 → 시·도) before leaving the dex.
    BackHandler(enabled = province != null) { if (district != null) toProvince() else toCountry() }

    // A fresh scroll position per level.
    key(province?.code, district?.code) {
        Column(
            Modifier.fillMaxSize().padding(40.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            val r = regions
            when {
                r == null -> {
                    ScreenTitle("지역 도감")
                    Text("지역 정보를 불러오는 중…", color = FogColors.TextSub)
                }
                district != null && province != null -> {
                    Breadcrumb(listOf("전국" to toCountry, province.name to toProvince, district.name to null))
                    RegionHeading(district, "읍·면·동 ${district.visitedDongs} / ${district.dongs.size} 방문") { onShowOnMap(district) }
                    Text("동을 누르면 지도에서 보여 줘요.", color = FogColors.TextSub)
                    RegionGrid(district.dongs, columns = 5, onSelect = onShowOnMap) { d ->
                        Text(d.name, style = MaterialTheme.typography.titleMedium)
                        Text(formatRate(d.rate), style = MaterialTheme.typography.titleLarge, color = FogColors.Accent)
                    }
                }
                province != null -> {
                    Breadcrumb(listOf("전국" to toCountry, province.name to null))
                    RegionHeading(province, "시·군·구 ${province.visitedDistricts} / ${province.districts.size} 방문") { onShowOnMap(province) }
                    RegionGrid(province.districts, columns = 4, onSelect = { viewModel.dexDistrict.value = it.code }) { d ->
                        Text(d.name, style = MaterialTheme.typography.titleMedium)
                        Text(formatRate(d.rate), style = MaterialTheme.typography.headlineMedium, color = FogColors.Accent)
                        Text("읍·면·동 ${d.visitedDongs} / ${d.dongs.size}", style = MaterialTheme.typography.bodyMedium, color = FogColors.TextSub)
                        ProgressBar(d.visitedDongs.toFloat() / maxOf(1, d.dongs.size), height = 8.dp)
                    }
                }
                else -> {
                    CountryPage(r) { viewModel.dexProvince.value = it.code; viewModel.dexDistrict.value = null }
                    SeasonAndStamps(viewModel)
                }
            }
        }
    }
}

/** 시·도 cards for the whole country: entered ones show their rate and open their 시·군·구. */
@Composable
private fun CountryPage(regions: List<ProvinceProgress>, onOpen: (ProvinceProgress) -> Unit) {
    ScreenTitle("지역 도감", "달성률은 그 지역에서 안개를 걷은 면적 비율이에요. 가본 곳만 열리고 나머지는 ? 로 남아요.")
    val districts = regions.flatMap { it.districts }
    val dongs = districts.flatMap { it.dongs }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Pill("시·도 ${regions.count { it.visited }} / ${regions.size}", FogColors.Accent)
        Pill("시·군·구 ${districts.count { it.visited }} / ${districts.size}", FogColors.Blue)
        Pill("읍·면·동 ${dongs.count { it.visited }} / ${dongs.size}", FogColors.Amber)
        Spacer(Modifier.weight(1f))
        Text("행정구역 경계: admdongkor (CC BY 4.0), 원자료 통계청", style = MaterialTheme.typography.bodySmall, color = FogColors.TextSub)
    }
    RegionGrid(regions, columns = 4, onSelect = onOpen) { p ->
        Text(p.name, style = MaterialTheme.typography.titleMedium)
        Text(formatRate(p.rate), style = MaterialTheme.typography.headlineMedium, color = FogColors.Accent)
        Text("시·군·구 ${p.visitedDistricts} / ${p.districts.size}", style = MaterialTheme.typography.bodyMedium, color = FogColors.TextSub)
        ProgressBar(p.visitedDistricts.toFloat() / maxOf(1, p.districts.size), height = 8.dp)
    }
}

@Composable
private fun SeasonAndStamps(viewModel: MainViewModel) {
    val season by viewModel.season.collectAsState()
    viewModel.stamps.collectAsState().value // recompose on new stamps
    val counts = viewModel.stampCounts()

    SectionHeader("시즌 챌린지", Modifier.padding(top = 24.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.fillMaxWidth()) {
        season.forEach { goal ->
            FogCard(Modifier.weight(1f)) {
                Text(goal.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${goal.label(goal.current.coerceAtMost(goal.target))} / ${goal.label(goal.target)}",
                    style = MaterialTheme.typography.headlineMedium,
                    color = if (goal.done) FogColors.Accent else FogColors.TextMain,
                )
                ProgressBar(goal.current.toFloat() / goal.target)
            }
        }
    }

    SectionHeader("상황 스탬프", Modifier.padding(top = 24.dp))
    Text("차량·내비 데이터로만 얻을 수 있는 스탬프예요. 폰 앱은 비, 전조등, 동승자, 충전 상태, 내비 도착을 알 수 없어요.", color = FogColors.TextSub)
    StampCode.entries.chunked(3).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            row.forEach { code ->
                val n = counts[code] ?: 0
                FogCard(Modifier.weight(1f).fillMaxHeight(), outlined = n == 0) {
                    IconBadge(stampIcon(code), if (n > 0) FogColors.Amber else FogColors.TextSub)
                    // Title, description, count: QA reads the count two nodes after the title.
                    Text(code.title, style = MaterialTheme.typography.titleLarge, color = if (n > 0) FogColors.Amber else FogColors.TextSub)
                    Text(code.description, style = MaterialTheme.typography.bodyMedium, color = FogColors.TextSub)
                    Text(if (n > 0) "${n}회 획득" else "아직 없음", style = MaterialTheme.typography.bodyLarge)
                }
            }
            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

private fun stampIcon(code: StampCode): ImageVector = when (code) {
    StampCode.RAIN -> Icons.Outlined.Umbrella
    StampCode.NIGHT -> Icons.Outlined.NightsStay
    StampCode.FAMILY -> Icons.Outlined.FamilyRestroom
    StampCode.CHARGE -> Icons.Outlined.BatteryChargingFull
    StampCode.ARRIVAL -> Icons.Outlined.Flag
    else -> Icons.Outlined.EmojiEvents
}

/** "전국 › 서울특별시 › 성동구": every level but the current one is a link back up. */
@Composable
private fun Breadcrumb(path: List<Pair<String, (() -> Unit)?>>) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val up = path.getOrNull(path.size - 2)?.second
        if (up != null) {
            IconButton(onClick = up) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "뒤로", tint = FogColors.TextMain, modifier = Modifier.size(32.dp))
            }
        }
        path.forEachIndexed { i, (name, onClick) ->
            if (i > 0) Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = FogColors.TextSub)
            if (onClick != null) {
                TextButton(onClick = onClick) { Text(name, style = MaterialTheme.typography.titleMedium, color = FogColors.Accent) }
            } else {
                Text(name, style = MaterialTheme.typography.titleMedium, color = FogColors.TextMain, modifier = Modifier.padding(horizontal = 12.dp))
            }
        }
    }
}

/** Region name and rate, with the button that frames it on the map. */
@Composable
private fun RegionHeading(region: RegionStat, visitedLine: String, onShowOnMap: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(region.name, style = MaterialTheme.typography.displaySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Pill("달성률 ${formatRate(region.rate)}", FogColors.Accent)
                Pill(visitedLine, FogColors.Blue)
            }
        }
        Button(
            onClick = onShowOnMap,
            shape = RoundedCornerShape(4.dp),
            colors = ButtonDefaults.buttonColors(containerColor = FogColors.TextMain, contentColor = FogColors.Surface),
        ) {
            Icon(Icons.Outlined.Map, contentDescription = null, modifier = Modifier.size(28.dp))
            Text("지도에서 보기", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 10.dp, top = 6.dp, bottom = 6.dp))
        }
    }
}

/** Equal-width, equal-height cards; unvisited regions are outlined "?" cards and cannot be opened. */
@Composable
private fun <T : RegionStat> RegionGrid(
    items: List<T>,
    columns: Int,
    onSelect: (T) -> Unit,
    content: @Composable ColumnScope.(T) -> Unit,
) {
    items.chunked(columns).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            row.forEach { item ->
                val mod = Modifier.weight(1f).fillMaxHeight()
                if (!item.visited) {
                    FogCard(mod, outlined = true, padding = 20.dp) {
                        Text("?", style = MaterialTheme.typography.headlineMedium, color = FogColors.TextSub)
                    }
                } else {
                    FogCard(mod, padding = 20.dp, onClick = { onSelect(item) }) { content(item) }
                }
            }
            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}
