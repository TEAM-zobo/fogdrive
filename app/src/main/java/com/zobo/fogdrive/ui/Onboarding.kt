package com.zobo.fogdrive.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

private class TutorialPage(val icon: ImageVector, val color: Color, val title: String, val body: String)

private val pages: List<TutorialPage>
    @Composable get() = listOf(
        TutorialPage(
            Icons.Outlined.DirectionsCar, FogColors.Accent, "시동만 걸면 안개가 걷혀요",
            "달린 길과 길에서 보이는 만큼 지도의 안개가 걷혀요. 아직 안 가 본 동네는 짙은 안개, " +
                "들어가 본 동네의 안 지난 곳은 옅은 안개로 남아요. 따로 누를 건 없어요.",
        ),
        TutorialPage(
            Icons.Outlined.Flag, FogColors.Amber, "내린 곳에는 깃발",
            "P단에서 문을 열고 내리면 그 주변을 걷고 깃발을 꽂아요. " +
                "비 오는 날, 밤, 가족과 함께, 창문을 열고 달리면 상황 스탬프도 모여요.",
        ),
        TutorialPage(
            Icons.Outlined.Explore, FogColors.Blue, "도감과 챌린지",
            "시·도 → 시·군·구 → 읍·면·동 도감에서 지역별 달성률을 확인하고, " +
                "오늘·이번 주·이번 달·시즌 챌린지에 도전해요. 처음 가 본 동네는 이야기 카드로 소개해요.",
        ),
        TutorialPage(
            Icons.Outlined.RecordVoiceOver, FogColors.Accent, "주행 중에는 소리로",
            "달리는 동안 화면은 지도와 숫자 몇 개만 보여 주고, 새 동네·스탬프·주행 요약은 음성으로 알려 줘요. " +
                "동승자가 있으면 이번 주행 미션도 생겨요.",
        ),
        TutorialPage(
            Icons.Outlined.Visibility, FogColors.Amber, "먼저 시연으로 둘러보기",
            "설정 → 시연 모드에서 코스를 골라 시작하면 자동으로 달리며 모든 기능을 보여 줘요. " +
                "튜토리얼은 설정에서 다시 볼 수 있어요.",
        ),
    )

/**
 * First-run tutorial over the parked screens. It never shows while driving (the driving view
 * replaces the whole UI), and finishing or skipping it is remembered.
 */
@Composable
fun Onboarding(onDone: () -> Unit) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    val all = pages
    val p = all[page]
    val last = page == all.lastIndex
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f))
            // Swallows taps so the map under the tutorial doesn't move. A pointer handler, not
            // clickable(): that would merge the card's texts into one node for accessibility.
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        FogCard(Modifier.width(820.dp), padding = 48.dp) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                IconBadge(p.icon, p.color, size = 72.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("안개지도 둘러보기 ${page + 1}/${all.size}", style = MaterialTheme.typography.titleSmall, color = FogColors.TextSub)
                    Text(p.title, style = MaterialTheme.typography.headlineMedium, color = FogColors.TextMain)
                }
            }
            Text(p.body, style = MaterialTheme.typography.bodyLarge, color = FogColors.TextMain, modifier = Modifier.padding(vertical = 16.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    all.indices.forEach { i ->
                        Box(Modifier.size(12.dp).clip(CircleShape).background(if (i == page) FogColors.Accent else FogColors.Outline))
                    }
                }
                Spacer(Modifier.weight(1f))
                if (!last) {
                    TextButton(onClick = onDone) {
                        Text("건너뛰기", style = MaterialTheme.typography.titleMedium, color = FogColors.TextSub, modifier = Modifier.padding(8.dp))
                    }
                }
                if (page > 0) {
                    TextButton(onClick = { page-- }) {
                        Text("이전", style = MaterialTheme.typography.titleMedium, color = FogColors.TextMain, modifier = Modifier.padding(8.dp))
                    }
                }
                Button(
                    onClick = { if (last) onDone() else page++ },
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = FogColors.Accent, contentColor = FogColors.OnAccent),
                ) { Text(if (last) "시작하기" else "다음", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            }
        }
    }
}
