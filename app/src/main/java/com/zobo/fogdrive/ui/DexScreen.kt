package com.zobo.fogdrive.ui

import com.zobo.fogdrive.FogRuntime
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
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.EnergySavingsLeaf
import androidx.compose.material.icons.outlined.SportsScore
import androidx.compose.material.icons.outlined.WbCloudy
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.FamilyRestroom
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Landscape
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Moving
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.NightsStay
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Thunderstorm
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material.icons.outlined.Umbrella
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material.icons.outlined.WbTwilight
import androidx.compose.material.icons.outlined.Weekend
import androidx.compose.material.icons.outlined.WorkOutline
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zobo.fogdrive.explore.ChallengePeriod
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
                    val milestones by viewModel.milestones.collectAsState()
                    RegionGrid(district.dongs, columns = 5, cardHeight = 124.dp, onSelect = onShowOnMap) { d ->
                        Text(d.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(formatRate(d.rate), style = MaterialTheme.typography.titleLarge, color = FogColors.Accent)
                            val step = FogRuntime.mapPrefs.milestoneOf(d.code, milestones)
                            if (step > 0) MilestoneBadge(step)
                        }
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
    // ~3,800 regions: count once per tally, not on every recomposition.
    val totals = remember(regions) {
        val districts = regions.flatMap { it.districts }
        val dongs = districts.flatMap { it.dongs }
        listOf(
            regions.count { it.visited }, regions.size,
            districts.count { it.visited }, districts.size,
            dongs.count { it.visited }, dongs.size,
            dongs.count { it.landed },
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Pill("시·도 ${totals[0]} / ${totals[1]}", FogColors.Accent)
        Pill("시·군·구 ${totals[2]} / ${totals[3]}", FogColors.Blue)
        Pill("읍·면·동 ${totals[4]} / ${totals[5]}", FogColors.Blue)
        Pill("하차한 동 ${totals[6]}곳", FogColors.Amber)
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
    val challenges by viewModel.challenges.collectAsState()
    viewModel.stamps.collectAsState().value // recompose on new stamps
    val counts = viewModel.stampCounts()
    var period by rememberSaveable { mutableStateOf(ChallengePeriod.TODAY) }

    SectionHeader("챌린지", Modifier.padding(top = 24.dp))
    // Period tabs: each shows how many of its goals are done.
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ChallengePeriod.entries.forEach { p ->
            val group = challenges.firstOrNull { it.period == p }
            val picked = p == period
            FogCard(
                color = if (picked) FogColors.Accent else FogColors.SurfaceHigh,
                padding = 14.dp,
                onClick = { period = p },
            ) {
                Text(
                    p.title + (group?.let { " ${it.done}/${it.goals.size}" } ?: ""),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (picked) FogColors.OnAccent else FogColors.TextMain,
                )
            }
        }
    }
    challenges.firstOrNull { it.period == period }?.let { group ->
        Text(group.label, style = MaterialTheme.typography.bodyLarge, color = FogColors.TextSub)
        group.goals.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                row.forEach { goal ->
                    FogCard(
                        Modifier.weight(1f).fillMaxHeight(),
                        borderColor = if (goal.done) FogColors.Accent else null,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(goal.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            if (goal.done) Icon(Icons.Outlined.CheckCircle, contentDescription = "달성", tint = FogColors.Accent)
                        }
                        Text(
                            "${goal.label(goal.current.coerceAtMost(goal.target))} / ${goal.label(goal.target)}",
                            style = MaterialTheme.typography.headlineMedium,
                            color = if (goal.done) FogColors.Accent else FogColors.TextMain,
                        )
                        ProgressBar(goal.current.toFloat() / goal.target)
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }

    SectionHeader("상황 스탬프", Modifier.padding(top = 24.dp))
    Text(
        "차량·내비 데이터와 주행 기록으로 얻는 스탬프예요. ${counts.count { it.value > 0 }} / ${StampCode.entries.size}종 모았어요.",
        color = FogColors.TextSub,
    )
    StampCode.entries.chunked(4).forEach { row ->
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
            repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

private fun stampIcon(code: StampCode): ImageVector = when (code) {
    StampCode.RAIN -> Icons.Outlined.Umbrella
    StampCode.NIGHT -> Icons.Outlined.NightsStay
    StampCode.FAMILY -> Icons.Outlined.FamilyRestroom
    StampCode.CHARGE -> Icons.Outlined.BatteryChargingFull
    StampCode.ARRIVAL -> Icons.Outlined.Flag
    StampCode.EXPEDITION -> Icons.Outlined.TravelExplore
    StampCode.DAWN -> Icons.Outlined.WbTwilight
    StampCode.SUNSET -> Icons.Outlined.WbSunny
    StampCode.WEEKEND -> Icons.Outlined.Weekend
    StampCode.LONG_DRIVE -> Icons.Outlined.Speed
    StampCode.PIONEER -> Icons.Outlined.Landscape
    StampCode.NEW_DISTRICT -> Icons.Outlined.NewReleases
    StampCode.MIDNIGHT -> Icons.Outlined.Bedtime
    StampCode.COMMUTE -> Icons.Outlined.WorkOutline
    StampCode.RAINY_NIGHT -> Icons.Outlined.Thunderstorm
    StampCode.FULL_HOUSE -> Icons.Outlined.Groups
    StampCode.LOW_BATTERY -> Icons.Outlined.BatteryAlert
    StampCode.MARATHON -> Icons.Outlined.DirectionsCar
    StampCode.ALL_NEW -> Icons.Outlined.AutoAwesome
    StampCode.HOMECOMING -> Icons.Outlined.Home
    StampCode.FIRST_DRIVE -> Icons.Outlined.Key
    StampCode.DONG_HOPPER -> Icons.Outlined.Explore
    StampCode.PROVINCE_CROSS -> Icons.Outlined.Moving
    StampCode.NEW_PROVINCE -> Icons.Outlined.Public
    StampCode.OPEN_AIR -> Icons.Outlined.Air
    StampCode.SUNROOF -> Icons.Outlined.WbCloudy
    StampCode.ECO -> Icons.Outlined.EnergySavingsLeaf
    StampCode.SPORT -> Icons.Outlined.SportsScore
    StampCode.CREW_MISSION -> Icons.Outlined.EmojiEvents
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

/** The highest exploration step a 동 reached (10 … 100 %), as a small medal. */
@Composable
private fun MilestoneBadge(percent: Int) {
    Row(
        Modifier.clip(RoundedCornerShape(4.dp)).background(FogColors.Amber.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(Icons.Outlined.EmojiEvents, contentDescription = "개척 단계", tint = FogColors.Amber, modifier = Modifier.size(18.dp))
        Text("$percent%", style = MaterialTheme.typography.labelMedium, color = FogColors.Amber)
    }
}
