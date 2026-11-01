package com.zobo.burung.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.EventRepeat
import androidx.compose.material.icons.outlined.LocationCity
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.EnergySavingsLeaf
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Landscape
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Moving
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material.icons.outlined.WbCloudy
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material.icons.outlined.WbTwilight
import androidx.compose.material.icons.outlined.Weekend
import androidx.compose.material.icons.outlined.WorkOutline
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zobo.burung.FogRuntime
import com.zobo.burung.explore.RegionStat
import com.zobo.burung.explore.StampBook
import com.zobo.burung.explore.StampField
import com.zobo.burung.explore.StampProgress
import com.zobo.burung.explore.Title
import com.zobo.burung.explore.Titles
import com.zobo.burung.record.StampCode

/**
 * 내 탐험: the title (big, as a shield), this week's suggested stamps, every title, then every stamp
 * by shelf. Titles are shields and stamps are round coins, so the two never look alike. In 키즈모드
 * only the wording changes.
 */
@Composable
fun ExpeditionScreen(viewModel: MainViewModel, onShowOnMap: (RegionStat) -> Unit) {
    val prefs = FogRuntime.mapPrefs
    val stamps by viewModel.stamps.collectAsState()
    val picks by viewModel.weeklyPicks.collectAsState()
    val progressRead by viewModel.stampProgress.collectAsState()
    val progress = progressRead ?: StampProgress()
    val picked by prefs.title.collectAsState()
    val kids by prefs.kids.collectAsState()
    val team by prefs.teamName.collectAsState()
    val counts = remember(stamps) { stamps.groupingBy { it.code }.eachCount() }
    val collected = remember(counts) { StampBook.ACTIVE.filter { (counts[it.name] ?: 0) > 0 }.toSet() }
    val shown = Titles.shown(picked, collected)
    val next = Titles.next(collected)
    val k = { normal: String, kid: String -> if (kids) kid else normal }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 40.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        // The title, large: what the tab is read for at a glance.
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(32.dp))
                .background(Brush.linearGradient(listOf(Color(0xFFFFF0D9), Color(0xFFFFF8EC), Color(0xFFE3F6EE))))
                .padding(horizontal = 40.dp, vertical = 30.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(32.dp),
        ) {
            Shield(150.dp, if (shown != null) ShieldState.SHOWN else ShieldState.LOCKED, shown?.let(::titleIcon) ?: Icons.Outlined.DirectionsCar)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    Modifier.clip(RoundedCornerShape(24.dp)).background(Color.White).padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Outlined.Flag, contentDescription = null, tint = CORAL, modifier = Modifier.size(26.dp))
                    Text(team.ifBlank { MapPrefs.DEFAULT_TEAM }, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = INK)
                }
                Text(
                    shown?.name(kids) ?: Titles.NONE,
                    fontSize = 56.sp, fontWeight = FontWeight.Black, color = INK, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (shown == null) k("한 분야의 스탬프를 모두 모으면 첫 칭호가 생겨요", "스탬프를 한 줄 다 모으면 칭호가 생겨요!") else k("나의 칭호", "내 칭호"),
                    fontSize = 22.sp, color = SUB,
                )
            }
            next?.let { t ->
                Column(
                    Modifier.width(380.dp).clip(RoundedCornerShape(28.dp)).background(Color.White.copy(alpha = 0.85f)).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("다음 칭호", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = SUB)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(t.name(kids), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = INK)
                        Icon(Icons.Outlined.Lock, contentDescription = null, tint = AMBER_INK, modifier = Modifier.size(26.dp))
                    }
                    ProgressBar(t.progress(collected).toFloat() / t.need, color = AMBER, height = 16.dp)
                    val left = t.need - t.progress(collected)
                    Text(
                        (t.field?.let { "${if (kids) it.kidsLabel else it.label} " } ?: "") + k("스탬프 ${left}개만 더!", "스탬프 ${left}개만 더 모아요!"),
                        fontSize = 21.sp, fontWeight = FontWeight.Bold, color = AMBER_INK,
                    )
                }
            }
        }

        // This week's three suggestions: stamps still to collect, with how and (for a place) where.
        Heading(Icons.Outlined.Explore, MINT, k("이번 주 탐험 추천", "이번 주 모험 추천"), k("월요일마다 초기화돼요", "월요일마다 바뀌어요"))
        Section {
            if (picks.isEmpty() && progressRead != null) Text(
                // Picks can also run out while stamps are left (e.g. only far-off progress stamps left).
                if (collected.size == StampBook.ACTIVE.size) k("스탬프를 모두 모았어요!", "스탬프를 다 모았어요! 최고예요!")
                else k("이번 주는 추천할 스탬프가 없어요. 계속 달리면 열려요", "이번 주는 쉬어 가요. 계속 달리면 새 모험이 열려요!"),
                fontSize = 24.sp, color = FogColors.TextSub, modifier = Modifier.padding(vertical = 20.dp),
            )
            picks.forEachIndexed { i, p ->
                if (i > 0) Box(Modifier.fillMaxWidth().height(2.dp).background(LINE))
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 22.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Coin(stampIcon(p.code), p.done, 84.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(p.code.title, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = FogColors.TextMain)
                            Tag(pickTag(p.code, kids), MINT)
                        }
                        Text(StampBook.hint(p.code, kids), fontSize = 22.sp, color = FogColors.TextSub)
                        if (!p.done) StampBook.progress(p.code, progress)?.let { (n, t) ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                ProgressBar(n.toFloat() / t, Modifier.weight(1f), color = MINT, height = 14.dp)
                                Text(StampBook.progressLabel(p.code, progress) ?: "", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = MINT)
                            }
                        }
                    }
                    when {
                        p.done -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MINT, modifier = Modifier.size(30.dp))
                            Text(k("받았어요", "받았어요!"), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MINT)
                        }
                        p.place != null -> Row(
                            Modifier.clip(RoundedCornerShape(30.dp)).background(INK).clickable { onShowOnMap(p.place.region) }
                                .padding(horizontal = 22.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(Icons.Outlined.Map, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
                            Text(
                                if (kids) "${p.place.region.name} 가 보기" else "${p.place.region.name} · ${"%.0f".format(p.place.km)} km",
                                fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Color.White,
                            )
                        }
                    }
                }
            }
        }

        // Every title: earned ones can be picked to show; locked ones say what opens them.
        Heading(Icons.Outlined.EmojiEvents, TEAL, "칭호", "${Titles.ALL.count { it.earned(collected) }} / ${Titles.ALL.size} · " + k("눌러서 대표 칭호로", "눌러서 내 칭호로 고르기"))
        Section(padding = 36.dp) {
            Titles.ALL.chunked(TITLES_PER_ROW).forEachIndexed { r, row ->
                if (r > 0) Spacer(Modifier.height(30.dp))
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { t ->
                        val earned = t.earned(collected)
                        val isShown = t == shown
                        Column(
                            Modifier.weight(1f).clip(RoundedCornerShape(20.dp))
                                .then(if (earned) Modifier.clickable { prefs.setTitle(t.id) } else Modifier).padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Shield(116.dp, when { isShown -> ShieldState.SHOWN; earned -> ShieldState.EARNED; else -> ShieldState.LOCKED }, titleIcon(t))
                            Text(t.name(kids), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = if (earned) INK else GREY_TEXT, textAlign = TextAlign.Center)
                            Text(
                                when {
                                    isShown -> k("대표 칭호", "내 칭호")
                                    earned -> t.condition(kids)
                                    else -> "${t.condition(kids)} · ${t.progress(collected)}/${t.need}"
                                },
                                fontSize = 18.sp, color = if (isShown) AMBER_INK else FogColors.TextSub, textAlign = TextAlign.Center,
                            )
                        }
                    }
                    repeat(TITLES_PER_ROW - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }

        // Every stamp, by shelf: four coins, then the shelf's title, which lights up once all four are in.
        Heading(Icons.Outlined.AutoAwesome, AMBER_INK, k("스탬프", "내가 모은 스탬프"), "${collected.size} / ${StampBook.ACTIVE.size}")
        StampField.entries.forEach { field ->
            val list = StampBook.ACTIVE.filter { StampBook.field(it) == field }
            val got = list.count { it in collected }
            Section(padding = 30.dp) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Icon(fieldIcon(field), contentDescription = null, tint = MINT, modifier = Modifier.size(32.dp))
                    Text(if (kids) field.kidsLabel else field.label, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = FogColors.TextMain)
                    ProgressBar(got.toFloat() / list.size, Modifier.width(260.dp), color = MINT, height = 12.dp)
                    Text("$got/${list.size}", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MINT)
                }
                Spacer(Modifier.height(20.dp))
                val title = Titles.ALL.first { it.field == field }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    list.forEach { code ->
                        val got = code in collected
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Coin(stampIcon(code), got, 92.dp)
                            // Name, then "받았어요" (QA reads it right after the name), the progress, or how to get it.
                            Text(code.title, fontSize = 21.sp, fontWeight = if (got) FontWeight.Bold else FontWeight.Medium, color = if (got) INK else GREY_TEXT, textAlign = TextAlign.Center)
                            Text(
                                when {
                                    got -> "받았어요"
                                    else -> StampBook.progressLabel(code, progress) ?: StampBook.hint(code, kids)
                                },
                                fontSize = 16.sp, color = if (got) MINT else FogColors.TextSub, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    // The shelf's title at the end of the row: what the four stamps add up to.
                    Column(
                        Modifier.weight(1.2f).padding(top = 2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val earned = title.earned(collected)
                        Shield(80.dp, if (earned) ShieldState.EARNED else ShieldState.LOCKED, titleIcon(title))
                        Text(title.name(kids), fontSize = 19.sp, fontWeight = FontWeight.Bold, color = if (earned) TEAL else GREY_TEXT, textAlign = TextAlign.Center)
                        Text(if (earned) k("칭호 획득!", "칭호를 받았어요!") else k("${title.need}개 모으면 칭호", "${title.need}개 모으면 칭호!"), fontSize = 16.sp, color = FogColors.TextSub)
                    }
                }
            }
        }
    }
}

private enum class ShieldState { SHOWN, EARNED, LOCKED }

/** A title's crest: amber for the one shown, teal for the others earned, grey with a lock if not yet. */
@Composable
private fun Shield(width: Dp, state: ShieldState, icon: ImageVector) {
    val colors = when (state) {
        ShieldState.SHOWN -> listOf(Color(0xFFFFC66B), Color(0xFFF08A12))
        ShieldState.EARNED -> listOf(Color(0xFF2C8C74), Color(0xFF1F6F5C))
        ShieldState.LOCKED -> listOf(GREY, GREY)
    }
    Box(Modifier.size(width, width * 1.16f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            fun shield(inset: Float) = Path().apply {
                val w = size.width - 2 * inset
                val h = size.height - 2 * inset
                moveTo(inset + w / 2, inset)
                lineTo(inset + w, inset + h * 0.13f)
                lineTo(inset + w, inset + h * 0.5f)
                cubicTo(inset + w, inset + h * 0.78f, inset + w * 0.77f, inset + h * 0.92f, inset + w / 2, inset + h)
                cubicTo(inset + w * 0.23f, inset + h * 0.92f, inset, inset + h * 0.78f, inset, inset + h * 0.5f)
                lineTo(inset, inset + h * 0.13f)
                close()
            }
            drawPath(shield(0f), Brush.linearGradient(colors, Offset.Zero, Offset(size.width, size.height)))
            if (state != ShieldState.LOCKED) drawPath(shield(size.width * 0.09f), Color.White.copy(alpha = 0.14f))
        }
        Icon(
            if (state == ShieldState.LOCKED) Icons.Outlined.Lock else icon,
            contentDescription = null,
            tint = if (state == ShieldState.LOCKED) GREY_TEXT else Color.White,
            modifier = Modifier.size(width * 0.42f).padding(bottom = width * 0.04f),
        )
    }
}

/** A stamp: a round coin, amber once collected, grey until then. */
@Composable
private fun Coin(icon: ImageVector, got: Boolean, size: Dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(if (got) Color(0xFFFFF0D9) else GREY)
            .border(4.dp, if (got) Color(0xFFF7C98A) else Color(0xFFDAD5CA), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (got) AMBER_INK else Color(0xFFB9B4A9), modifier = Modifier.size(size * 0.48f))
    }
}

@Composable
private fun Heading(icon: ImageVector, tint: Color, title: String, sub: String) {
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(34.dp))
        Text(title, fontSize = 32.sp, fontWeight = FontWeight.Bold, color = FogColors.TextMain)
        Text(sub, fontSize = 20.sp, color = FogColors.TextSub)
    }
}

@Composable
private fun Section(padding: Dp = 30.dp, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(32.dp)).background(FogColors.Surface).padding(horizontal = padding, vertical = 10.dp + padding / 3)) {
        content()
    }
}

@Composable
private fun Tag(text: String, color: Color) {
    Text(
        text, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = color,
        modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(color.copy(alpha = 0.12f)).padding(horizontal = 14.dp, vertical = 4.dp),
    )
}

private fun pickTag(code: StampCode, kids: Boolean): String = when (code) {
    StampCode.NEW_DISTRICT, StampCode.PROVINCE_3 -> if (kids) "가까운 모험지" else "다음 탐험 후보"
    StampCode.WEEKEND -> "이번 주말"
    else -> if (kids) "도전!" else "추천"
}

private fun titleIcon(t: Title): ImageVector = when (t.id) {
    "daily" -> Icons.Outlined.WbSunny
    "region" -> Icons.Outlined.Map
    "trip" -> Icons.Outlined.Weekend
    "veteran" -> Icons.Outlined.StarOutline
    else -> Icons.Outlined.EmojiEvents
}

private fun fieldIcon(f: StampField): ImageVector = when (f) {
    StampField.DAILY -> Icons.Outlined.WbSunny
    StampField.REGION -> Icons.Outlined.Map
    StampField.TRIP -> Icons.Outlined.Weekend
}

fun stampIcon(code: StampCode): ImageVector = when (code) {
    StampCode.CHARGE -> Icons.Outlined.BatteryChargingFull
    StampCode.ARRIVAL -> Icons.Outlined.Flag
    StampCode.EXPEDITION -> Icons.Outlined.TravelExplore
    StampCode.DAWN -> Icons.Outlined.WbTwilight
    StampCode.SUNSET -> Icons.Outlined.WbSunny
    StampCode.WEEKEND -> Icons.Outlined.Weekend
    StampCode.LONG_DRIVE -> Icons.Outlined.Speed
    StampCode.PIONEER -> Icons.Outlined.Landscape
    StampCode.NEW_DISTRICT -> Icons.Outlined.LocationCity
    StampCode.COMMUTE -> Icons.Outlined.WorkOutline
    StampCode.FULL_HOUSE -> Icons.Outlined.Groups
    StampCode.ALL_NEW -> Icons.Outlined.AutoAwesome
    StampCode.HOMECOMING -> Icons.Outlined.Home
    StampCode.FIRST_DRIVE -> Icons.Outlined.Key
    StampCode.DONG_HOPPER -> Icons.Outlined.Explore
    StampCode.PROVINCE_CROSS -> Icons.Outlined.Moving
    StampCode.NEW_PROVINCE -> Icons.Outlined.Public
    StampCode.OPEN_AIR -> Icons.Outlined.Air
    StampCode.SUNROOF -> Icons.Outlined.WbCloudy
    StampCode.ECO -> Icons.Outlined.EnergySavingsLeaf
    StampCode.CREW_MISSION -> Icons.Outlined.Groups
    StampCode.WEEK_THREE -> Icons.Outlined.CalendarMonth
    StampCode.STREAK_4W -> Icons.Outlined.EventRepeat
    StampCode.KM_1000 -> Icons.Outlined.Speed
    StampCode.DONG_10 -> Icons.Outlined.Explore
    StampCode.DONG_100 -> Icons.Outlined.TravelExplore
    StampCode.PROVINCE_3 -> Icons.Outlined.Public
    StampCode.FAR_DAY -> Icons.Outlined.Moving
    else -> Icons.Outlined.DirectionsCar
}

private const val TITLES_PER_ROW = 5
private val INK = Color(0xFF2B2A33)
private val SUB = Color(0xFF7C7A86)
private val MINT = Color(0xFF22B07D)
private val TEAL = Color(0xFF1F6F5C)
private val AMBER = Color(0xFFF59E2E)
private val AMBER_INK = Color(0xFFC8761A)
private val CORAL = Color(0xFFFF6F61)
private val GREY = Color(0xFFE6E3DC)
private val GREY_TEXT = Color(0xFFA9A59B)
private val LINE = Color(0xFFEDE6D8)
