package com.zobo.fogdrive.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
                r.isNullOrEmpty() -> {
                    ScreenTitle("지역 도감")
                    Text(if (r == null) "지역 정보를 불러오는 중…" else "지역 정보를 불러오지 못했어요. 앱을 다시 설치해 주세요.", color = FogColors.TextSub)
                    // Season and stamps don't depend on the atlas; keep them visible while it loads.
                    SeasonAndStamps(viewModel)
                }
                district != null && province != null -> {
                    Breadcrumb(listOf("전국" to toCountry, province.name to toProvince, district.name to null))
                    RegionHeading(district, "읍·면·동 ${district.visitedDongs} / ${district.dongs.size} 방문") { onShowOnMap(district) }
                    DexLegend()
                    Text("동을 누르면 지도에서 보여 줘요.", color = FogColors.TextSub)
                    RegionGrid(district.dongs, columns = 5, cardHeight = 124.dp, onSelect = onShowOnMap) { d ->
                        Text(d.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(formatRate(d.rate), style = MaterialTheme.typography.titleLarge, color = FogColors.Accent)
                    }
                }
                province != null -> {
                    Breadcrumb(listOf("전국" to toCountry, province.name to null))
                    RegionHeading(province, "시·군·구 ${province.visitedDistricts} / ${province.districts.size} 방문") { onShowOnMap(province) }
                    DexLegend()
                    RegionGrid(province.districts, columns = 4, cardHeight = 184.dp, onSelect = { viewModel.dexDistrict.value = it.code }) { d ->
                        Text(d.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        Pill("읍·면·동 ${dongs.count { it.visited }} / ${dongs.size}", FogColors.Blue)
        Pill("하차한 동 ${dongs.count { it.landed }}곳", FogColors.Amber)
        Spacer(Modifier.weight(1f))
        Text("행정구역 경계: admdongkor (CC BY 4.0), 원자료 통계청", style = MaterialTheme.typography.bodySmall, color = FogColors.TextSub)
    }
    DexLegend()
    RegionGrid(regions, columns = 4, cardHeight = 184.dp, onSelect = onOpen) { p ->
        Text(p.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
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

@Composable
private fun LandedBadge(count: Int, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(4.dp)).background(FogColors.Amber).padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Flag, contentDescription = "하차", tint = Color.White, modifier = Modifier.size(18.dp))
        Text("$count", style = MaterialTheme.typography.labelMedium, color = Color.White, modifier = Modifier.padding(start = 2.dp))
    }
}

/** Swatches explaining the three card states. */
@Composable
private fun DexLegend() {
    @Composable
    fun item(label: String, fill: Color, border: Color?) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            var m = Modifier.size(22.dp).clip(RoundedCornerShape(4.dp)).background(fill)
            if (border != null) m = m.border(2.dp, border, RoundedCornerShape(4.dp))
            Box(m)
            Text(label, style = MaterialTheme.typography.bodyMedium, color = FogColors.TextSub)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        item("하차한 곳", FogColors.Amber.copy(alpha = 0.10f), FogColors.Amber)
        item("지나간 곳", FogColors.Surface, FogColors.Accent)
        item("안 가본 곳", FogColors.SurfaceHigh, null)
    }
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
                if (region.landed) Pill("하차 ${region.landings}회", FogColors.Amber)
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

/**
 * One fixed card height per grid, so "?" rows and filled rows line up. Unlocked regions are white
 * with a blue frame; locked ones are flat grey "?" tiles that cannot be opened.
 */
@Composable
private fun <T : RegionStat> RegionGrid(
    items: List<T>,
    columns: Int,
    cardHeight: Dp,
    onSelect: (T) -> Unit,
    content: @Composable ColumnScope.(T) -> Unit,
) {
    items.chunked(columns).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
            row.forEach { item ->
                val mod = Modifier.weight(1f).height(cardHeight)
                if (!item.visited) {
                    FogCard(mod, color = FogColors.SurfaceHigh, padding = 20.dp) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("?", style = MaterialTheme.typography.headlineMedium, color = FogColors.TextSub)
                        }
                    }
                } else if (item.landed) {
                    // Got off here: warm fill and frame, plus a flag with how many times.
                    FogCard(
                        mod,
                        color = FogColors.Amber.copy(alpha = 0.10f),
                        padding = 20.dp,
                        borderColor = FogColors.Amber,
                        onClick = { onSelect(item) },
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { content(item) }
                            LandedBadge(item.landings, Modifier.align(Alignment.TopEnd))
                        }
                    }
                } else {
                    FogCard(mod, padding = 20.dp, borderColor = FogColors.Accent, onClick = { onSelect(item) }) { content(item) }
                }
            }
            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}
