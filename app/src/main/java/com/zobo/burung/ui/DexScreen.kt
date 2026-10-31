package com.zobo.burung.ui

import com.zobo.burung.FogRuntime
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.TravelExplore
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.style.TextAlign
import com.zobo.burung.geo.Tiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.zobo.burung.explore.ProvinceProgress
import com.zobo.burung.explore.RegionStat

@Composable
fun DexScreen(viewModel: MainViewModel, onShowOnMap: (RegionStat) -> Unit) {
    val regions by viewModel.regions.collectAsState()
    val provinceCode by viewModel.dexProvince.collectAsState()
    val districtCode by viewModel.dexDistrict.collectAsState()
    val province = regions?.firstOrNull { it.code == provinceCode && it.visited }
    val district = province?.districts?.firstOrNull { it.code == districtCode && it.visited }
    // The 동 list goes back to the slide it was opened from.
    val toSlides = { viewModel.dexDistrict.value = null }
    BackHandler(enabled = district != null) { toSlides() }

    val r = regions
    when {
        r.isNullOrEmpty() -> Column(Modifier.fillMaxSize().padding(40.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            ScreenTitle("지역 도감")
            Text(if (r == null) "지역 정보를 불러오는 중…" else "지역 정보를 불러오지 못했어요. 앱을 다시 설치해 주세요.", color = FogColors.TextSub)
        }
        district != null && province != null -> key(district.code) {
            Column(
                Modifier.fillMaxSize().padding(40.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Breadcrumb(listOf(province.name to toSlides, district.name to null))
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
        }
        else -> DexSlides(viewModel, r, onShowOnMap)
    }
}

/**
 * The whole country, then every 시·도 (those entered first), one slide each (swipe, the ‹ › buttons
 * or the chips). A 시·도 not entered yet shows its 시·군·구 all grey and the nearest one to try.
 * The dex opens on the slide picked with "이 지역을 기본으로 보기" (the country until one is picked).
 */
@Composable
private fun DexSlides(viewModel: MainViewModel, regions: List<ProvinceProgress>, onShowOnMap: (RegionStat) -> Unit) {
    val slides: List<ProvinceProgress?> = remember(regions) { listOf(null) + regions.filter { it.visited } + regions.filter { !it.visited } }
    val home by FogRuntime.mapPrefs.dexHome.collectAsState()
    val stories by viewModel.dexStories.collectAsState()
    val start = remember { (viewModel.dexProvince.value ?: home).let { c -> slides.indexOfFirst { (it?.code ?: "") == c } }.coerceAtLeast(0) }
    val pager = rememberPagerState(initialPage = start) { slides.size }
    val scope = rememberCoroutineScope()
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(notice) { if (notice != null) { delay(NOTICE_MS); notice = null } }
    // Remembered so a 동 list opened from this slide comes back to it.
    LaunchedEffect(pager.currentPage) { viewModel.dexProvince.value = slides.getOrNull(pager.currentPage)?.code }
    // A 시·도 entered for the first time moves up among the slides: stay on the one being shown.
    LaunchedEffect(slides) {
        val code = viewModel.dexProvince.value ?: ""
        val i = slides.indexOfFirst { (it?.code ?: "") == code }
        if (i >= 0 && i != pager.currentPage) pager.scrollToPage(i)
    }
    val go = { i: Int -> scope.launch { pager.animateScrollToPage(i.coerceIn(0, slides.size - 1)) }; Unit }
    val openProvince = { code: String -> slides.indexOfFirst { it?.code == code }.takeIf { it > 0 }?.let(go); Unit }
    val homeName = slides.firstOrNull { (it?.code ?: "") == home }?.let { slideName(it) } ?: "전국"

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(start = 40.dp, end = 40.dp, top = 32.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ScreenTitle("지역 도감")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.weight(1f, fill = false).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    slides.forEachIndexed { i, p ->
                        val on = i == pager.currentPage
                        val count = p?.let { "${it.visitedDistricts}/${it.districts.size}" } ?: "${regions.count { it.visited }}/${regions.size}"
                        FogCard(color = if (on) FogColors.TextMain else FogColors.Surface, padding = 12.dp, onClick = { go(i) }) {
                            Text(
                                (if ((p?.code ?: "") == home) "📌 " else "") + "${slideName(p)} $count",
                                style = MaterialTheme.typography.titleSmall,
                                color = when {
                                    on -> FogColors.Surface
                                    p != null && !p.visited -> FogColors.TextSub
                                    else -> FogColors.TextMain
                                },
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    slides.indices.forEach { i ->
                        Box(
                            Modifier.size(width = if (i == pager.currentPage) 24.dp else 10.dp, height = 10.dp).clip(RoundedCornerShape(5.dp))
                                .background(if (i == pager.currentPage) FogColors.TextMain else FogColors.Outline),
                        )
                    }
                }
                Text("← 밀어서 넘기기 →", style = MaterialTheme.typography.bodyMedium, color = FogColors.TextSub)
            }
            // A slide is the whole width of the screen: a short swipe (a fifth of it) is enough to turn it.
            HorizontalPager(
                pager,
                Modifier.weight(1f),
                pageSpacing = 24.dp,
                flingBehavior = PagerDefaults.flingBehavior(pager, snapPositionalThreshold = SWIPE_THRESHOLD),
            ) { page ->
                val p = slides[page]
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    SlideMap(
                        viewModel, regions, p,
                        isHome = (p?.code ?: "") == home,
                        hasPrev = page > 0, hasNext = page < slides.size - 1,
                        onPrev = { go(page - 1) }, onNext = { go(page + 1) },
                        onPin = {
                            FogRuntime.mapPrefs.setDexHome(p?.code ?: "")
                            notice = "📌 기본 화면을 $homeName → ${withRo(slideName(p))} 바꿨어요"
                        },
                        onOpenProvince = openProvince,
                        onOpenDistrict = { viewModel.dexDistrict.value = it },
                        modifier = Modifier.weight(0.47f).fillMaxHeight(),
                    )
                    SlideDashboard(
                        regions, p, stories[p?.code ?: ""],
                        onOpenProvince = openProvince,
                        onOpenDistrict = { viewModel.dexDistrict.value = it },
                        onShowOnMap = onShowOnMap,
                        modifier = Modifier.weight(0.53f).fillMaxHeight(),
                    )
                }
            }
        }
        notice?.let {
            Text(
                it,
                style = MaterialTheme.typography.titleMedium,
                color = FogColors.Surface,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 120.dp)
                    .clip(RoundedCornerShape(28.dp)).background(FogColors.TextMain).padding(horizontal = 28.dp, vertical = 14.dp),
            )
        }
    }
}

/** "서울", "경기", "전남광주" for chips and labels; the whole country is "전국". */
private fun slideName(p: ProvinceProgress?): String = p?.name?.let(::shortProvince) ?: "전국"

private fun shortProvince(name: String): String = when (name) {
    "충청북도" -> "충북"
    "충청남도" -> "충남"
    "경상북도" -> "경북"
    "경상남도" -> "경남"
    "전라북도" -> "전북"
    "전라남도" -> "전남"
    else -> name.replace(Regex("(통합특별시|특별자치시|특별자치도|특별시|광역시|도)$"), "")
}

/** "서울로", "경기로", "전남광주로", "전북으로": 으로 after a final consonant other than ㄹ. */
private fun withRo(name: String): String {
    val c = name.lastOrNull() ?: return name
    val jong = if (c in '가'..'힣') (c - '가') % 28 else 0
    return name + if (jong == 0 || jong == 8) "로" else "으로"
}

/** "은평구" → "은평" on the small map (two-letter names like "중구" stay whole). */
private fun shortDistrict(name: String): String = if (name.length >= 3 && name.last() in "구군시") name.dropLast(1) else name

/** Mint, deeper the more of the region was explored (relative to the most explored one shown). */
private fun exploredFill(rate: Double, top: Double): Color {
    val t = if (top > 0) kotlin.math.sqrt((rate / top).coerceIn(0.0, 1.0)).toFloat() else 1f
    return lerp(Color(0xFFC4EEDD), Color(0xFF1FA97A), t)
}

/** The slide's map card: the 시·도 of the country or the 시·군·구 of one 시·도, explored ones in mint. */
@Composable
private fun SlideMap(
    viewModel: MainViewModel,
    regions: List<ProvinceProgress>,
    p: ProvinceProgress?,
    isHome: Boolean,
    hasPrev: Boolean,
    hasNext: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onPin: () -> Unit,
    onOpenProvince: (String) -> Unit,
    onOpenDistrict: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val atlas = FogRuntime.regions?.atlas
    val shapes by produceState(emptyList<DexShape>(), atlas, p?.code) {
        if (atlas != null) value = withContext(Dispatchers.Default) { if (p == null) DexShapes.provinces(atlas) else DexShapes.districts(atlas, p.code) }
    }
    val homePlace by viewModel.navi.home.collectAsState()
    val homeTile = homePlace?.let { Offset(Tiles.lngToX(it.lng).toFloat(), Tiles.latToY(it.lat).toFloat()) }
    val unexplored = FogColors.SurfaceHigh
    val sub = FogColors.TextSub
    Box(modifier.clip(RoundedCornerShape(24.dp)).background(FogColors.Surface)) {
        if (p == null) {
            val byCode = remember(regions) { regions.associateBy { it.code } }
            val top = regions.maxOfOrNull { it.rate } ?: 0.0
            DexMap(
                shapes,
                fill = { s -> byCode[s.code]?.takeIf { it.visited }?.let { exploredFill(it.rate, top) } ?: unexplored },
                label = { s ->
                    val stat = byCode[s.code]
                    val n = shortProvince(s.name)
                    if (stat != null && stat.visited) DexLabel(n, Color.White, true, small = n in SMALL_PROVINCES, chip = "${stat.visitedDistricts}/${stat.districts.size}")
                    else DexLabel(n, sub, false, small = n in SMALL_PROVINCES)
                },
                home = homeTile,
                onTap = { s -> onOpenProvince(s.code) },
                modifier = Modifier.fillMaxSize().padding(top = 64.dp, bottom = 16.dp, start = 56.dp, end = 56.dp),
            )
        } else {
            val byCode = remember(p) { p.districts.associateBy { it.code } }
            val top = p.districts.maxOfOrNull { it.rate } ?: 0.0
            val visitedText = Color(0xFF0B5E43)
            DexMap(
                shapes,
                fill = { s -> byCode[s.code]?.takeIf { it.visited }?.let { exploredFill(it.rate, top) } ?: unexplored },
                label = { s ->
                    if (byCode[s.code]?.visited == true) DexLabel(shortDistrict(s.name), visitedText, true)
                    else DexLabel(shortDistrict(s.name), sub, false, small = true)
                },
                home = homeTile?.takeIf { h -> shapes.any { it.outline.contains(h.x.toDouble(), h.y.toDouble()) } },
                onTap = { s -> if (byCode[s.code]?.visited == true) onOpenDistrict(s.code) },
                modifier = Modifier.fillMaxSize().padding(top = 64.dp, bottom = 88.dp, start = 56.dp, end = 56.dp),
            )
            DexHint(Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp))
        }
        Text(
            p?.name ?: "대한민국",
            style = MaterialTheme.typography.headlineSmall,
            color = FogColors.TextMain,
            modifier = Modifier.align(Alignment.TopStart).padding(start = 24.dp, top = 18.dp),
        )
        if (!isHome && (p == null || p.visited)) {
            OutlinedButton(
                onClick = onPin,
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(2.dp, FogColors.Accent),
                modifier = Modifier.align(Alignment.TopEnd).padding(end = 16.dp, top = 12.dp),
            ) {
                Text(if (p == null) "📌 전국을 기본으로 보기" else "📌 이 지역을 기본으로 보기", style = MaterialTheme.typography.titleSmall, color = FogColors.Accent)
            }
        }
        if (hasPrev) PageArrow("‹", onPrev, Modifier.align(Alignment.CenterStart).padding(start = 10.dp))
        if (hasNext) PageArrow("›", onNext, Modifier.align(Alignment.CenterEnd).padding(end = 10.dp))
    }
}

private val SMALL_PROVINCES = setOf("서울", "인천", "세종", "대전", "광주", "대구", "울산", "부산")

@Composable
private fun PageArrow(text: String, onClick: () -> Unit, modifier: Modifier) {
    Box(
        modifier.size(56.dp).clip(CircleShape).background(FogColors.SurfaceHigh).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.headlineMedium, color = FogColors.TextSub)
    }
}

/** "많이 탐험할수록 색이 진해져요!" from a little compass, with the shades it means. */
@Composable
private fun DexHint(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(FogColors.Amber), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Explore, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
        }
        Row(
            Modifier.clip(RoundedCornerShape(20.dp)).background(Color(0xFFE7F7F1)).padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            listOf(Color(0xFFDDE2E9), Color(0xFFC4EEDD), Color(0xFF7FDDB8), Color(0xFF1FA97A)).forEach {
                Box(Modifier.size(18.dp).clip(RoundedCornerShape(6.dp)).background(it))
            }
            Text("많이 탐험할수록 색이 진해져요!", style = MaterialTheme.typography.titleSmall, color = Color(0xFF0B5E43), modifier = Modifier.padding(start = 6.dp))
        }
    }
}

/** The slide's numbers (three), its two story cards and its list of explored regions. */
@Composable
private fun SlideDashboard(
    regions: List<ProvinceProgress>,
    p: ProvinceProgress?,
    story: DexStory?,
    onOpenProvince: (String) -> Unit,
    onOpenDistrict: (String) -> Unit,
    onShowOnMap: (RegionStat) -> Unit,
    modifier: Modifier = Modifier,
) {
    val date = remember { SimpleDateFormat("M월 d일", Locale.KOREA) }
    Column(
        modifier.clip(RoundedCornerShape(24.dp)).background(FogColors.Surface).verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (p == null) {
            // ~3,800 regions: count once per tally, not on every recomposition.
            val totals = remember(regions) {
                val districts = regions.flatMap { it.districts }
                val dongs = districts.flatMap { it.dongs }
                listOf(regions.count { it.visited }, regions.size, districts.count { it.visited }, districts.size, dongs.count { it.visited }, dongs.size)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile("시·도", "${totals[0]} / ${totals[1]}", Modifier.weight(1f))
                StatTile("시·군·구", "${totals[2]} / ${totals[3]}", Modifier.weight(1f))
                StatTile("읍·면·동", "%,d / %,d".format(totals[4], totals[5]), Modifier.weight(1f))
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile("개척률", formatRate(p.rate), Modifier.weight(1f), FogColors.Accent)
                StatTile("시·군·구", "${p.visitedDistricts} / ${p.districts.size}", Modifier.weight(1f))
                StatTile("읍·면·동", "${p.districts.sumOf { it.visitedDongs }} / ${p.districts.sumOf { it.dongs.size }}", Modifier.weight(1f))
            }
        }
        story?.recent?.let { rc ->
            StoryCard(
                Icons.Outlined.AutoAwesome, FogColors.Amber, "최근 개척", rc.name,
                if (rc.district != null) "${date.format(Date(rc.at))} · ${rc.district}" else "${date.format(Date(rc.at))}에 열었어요",
            )
        }
        story?.next?.let { nx ->
            StoryCard(
                Icons.Outlined.TravelExplore, FogColors.Accent, "다음 탐험 후보",
                if (p == null) shortProvince(nx.region.name) else nx.region.name,
                "${if (nx.fromHome) "집에서" else "여기서"} ${"%.0f".format(nx.km)}km · 아직 안 가 봤어요",
                action = "지도에서 ›" to { onShowOnMap(nx.region) },
            )
        }
        if (p == null) {
            Text("시·도 · 누르면 그 지역으로 넘어가요", style = MaterialTheme.typography.bodyMedium, color = FogColors.TextSub, modifier = Modifier.padding(top = 6.dp))
            regions.filter { it.visited }.sortedByDescending { it.rate }.forEach { r ->
                RegionRow(shortProvince(r.name), "${r.visitedDistricts} / ${r.districts.size} 시·군·구", r.visitedDistricts.toFloat() / maxOf(1, r.districts.size), formatRate(r.rate)) { onOpenProvince(r.code) }
            }
            Text("아직 안 가 본 시·도 ${regions.count { !it.visited }}곳", style = MaterialTheme.typography.bodyLarge, color = FogColors.TextSub)
            Text("행정구역 경계: admdongkor (CC BY 4.0), 원자료 통계청", style = MaterialTheme.typography.bodySmall, color = FogColors.TextSub)
        } else {
            if (p.visited) {
                Text("시·군·구 · 개척률 순 · 누르면 동 목록이 열려요", style = MaterialTheme.typography.bodyMedium, color = FogColors.TextSub, modifier = Modifier.padding(top = 6.dp))
            } else {
                Text("아직 탐험 전이에요. 가 보면 여기에 시·군·구가 하나씩 쌓여요.", style = MaterialTheme.typography.bodyLarge, color = FogColors.TextSub)
            }
            val shown = p.districts.filter { it.visited }.sortedByDescending { it.rate }
            val top = shown.firstOrNull()?.rate ?: 0.0
            shown.forEach { d ->
                RegionRow(d.name, "동 ${d.visitedDongs}/${d.dongs.size}", if (top > 0) (d.rate / top).toFloat() else 0f, formatRate(d.rate)) { onOpenDistrict(d.code) }
            }
            val left = p.districts.count { !it.visited }
            if (left > 0 && p.visited) Text("아직 안 가 본 시·군·구 ${left}곳", style = MaterialTheme.typography.bodyLarge, color = FogColors.TextSub)
        }
    }
}

@Composable
private fun StoryCard(icon: ImageVector, tint: Color, label: String, title: String, sub: String, action: Pair<String, () -> Unit>? = null) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(tint.copy(alpha = 0.10f)).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(FogColors.Surface), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(32.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = FogColors.TextSub)
            Text(title, style = MaterialTheme.typography.titleLarge, color = FogColors.TextMain)
            Text(sub, style = MaterialTheme.typography.bodyMedium, color = FogColors.TextSub)
        }
        action?.let { (text, onClick) ->
            TextButton(onClick = onClick) { Text(text, style = MaterialTheme.typography.titleSmall, color = tint) }
        }
    }
}

@Composable
private fun RegionRow(name: String, detail: String, bar: Float, rate: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(FogColors.SurfaceHigh).clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(name, style = MaterialTheme.typography.titleMedium, color = FogColors.TextMain, modifier = Modifier.width(150.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = FogColors.TextSub, modifier = Modifier.width(150.dp))
        ProgressBar(bar, Modifier.weight(1f), color = Color(0xFF2EC28E), height = 10.dp)
        Text(rate, style = MaterialTheme.typography.titleMedium, color = Color(0xFF0F8A63), modifier = Modifier.width(90.dp), textAlign = TextAlign.End)
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = FogColors.TextSub)
    }
}

private const val NOTICE_MS = 2_500L
private const val SWIPE_THRESHOLD = 0.2f

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
