package com.zobo.fogdrive.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zobo.fogdrive.record.StampCode

@Composable
fun DexScreen(viewModel: MainViewModel) {
    val season by viewModel.season.collectAsState()
    viewModel.stamps.collectAsState().value // recompose on new stamps
    val counts = viewModel.stampCounts()

    Column(
        Modifier.fillMaxSize().padding(40.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Text("시즌 챌린지", style = MaterialTheme.typography.displaySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.fillMaxWidth()) {
            season.forEach { goal ->
                FogCard(Modifier.weight(1f)) {
                    Text(goal.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${goal.current.coerceAtMost(goal.target)} / ${goal.target}",
                        style = MaterialTheme.typography.headlineMedium,
                        color = if (goal.done) FogColors.Accent else FogColors.TextMain,
                    )
                    ProgressBar(goal.current.toFloat() / goal.target)
                }
            }
        }
        Text("상황 스탬프", style = MaterialTheme.typography.displaySmall)
        Text("차량 데이터로만 얻을 수 있는 스탬프예요. 폰 앱은 비, 전조등, 동승자, 충전 상태를 알 수 없어요.", color = FogColors.TextSub)
        StampCode.entries.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { code ->
                    val n = counts[code] ?: 0
                    FogCard(Modifier.weight(1f), color = if (n > 0) FogColors.SurfaceHigh else FogColors.Surface) {
                        Text(code.title, style = MaterialTheme.typography.titleLarge, color = if (n > 0) FogColors.Amber else FogColors.TextSub)
                        Text(code.description, style = MaterialTheme.typography.bodyMedium, color = FogColors.TextSub)
                        Text(if (n > 0) "${n}회 획득" else "아직 없음", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}
