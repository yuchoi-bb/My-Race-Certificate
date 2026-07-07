package com.yuchoi.racecert.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yuchoi.racecert.BuildConfig
import com.yuchoi.racecert.data.BackupManager
import com.yuchoi.racecert.data.RaceRecord
import com.yuchoi.racecert.data.RaceType
import com.yuchoi.racecert.data.RecordStore
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val pbDateFormatter = DateTimeFormatter.ofPattern("yyyy.MM.dd")

/** 종목+거리 그룹의 최고 기록(PB) 한 건 */
private data class PbEntry(
    val type: RaceType,
    val distanceLabel: String,
    val seconds: Int,
    val record: RaceRecord,
)

/** "HH:MM:SS" → 초. 형식이 아니면 null */
private fun parseTimeSeconds(t: String): Int? {
    val m = Regex("""^(\d{1,2}):(\d{2}):(\d{2})$""").find(t.trim()) ?: return null
    val (h, min, s) = m.destructured
    return h.toInt() * 3600 + min.toInt() * 60 + s.toInt()
}

private fun formatSeconds(sec: Int): String =
    "%02d:%02d:%02d".format(sec / 3600, (sec % 3600) / 60, sec % 60)

/** 거리 문자열을 PB 그룹용으로 정규화 (10Km/10K/10km → 10km, HALF/하프 → 하프 …) */
private fun normalizeDistance(raw: String): String {
    val d = raw.trim().uppercase().replace(" ", "")
    return when {
        d.isEmpty() -> "거리 미입력"
        d.contains("하프") || d.contains("HALF") -> "하프"
        d.contains("풀") || d.contains("FULL") || d.contains("42.195") || d.contains("42K") -> "풀코스"
        else -> {
            val km = Regex("""(\d{1,3}(?:\.\d+)?)K""").find(d)?.groupValues?.get(1)
            if (km != null) "${km}km" else raw.trim()
        }
    }
}

private fun computePbs(records: List<RaceRecord>): List<PbEntry> =
    records.mapNotNull { r ->
        val sec = parseTimeSeconds(r.recordTime) ?: return@mapNotNull null
        PbEntry(r.type, normalizeDistance(r.distance), sec, r)
    }
        .groupBy { it.type to it.distanceLabel }
        .map { (_, entries) -> entries.minBy { it.seconds } }
        .sortedWith(compareBy({ it.type.ordinal }, { it.seconds }))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SummaryScreen(bottomBar: @Composable () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 백업 저장 (SAF → Google Drive 등)
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = BackupManager.export(context, uri)
            Toast.makeText(
                context,
                if (ok) "백업을 저장했어요. Google Drive 등에서 확인하세요." else "백업 저장에 실패했어요.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }
    // 복원 (SAF에서 백업 zip 선택)
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = BackupManager.import(context, uri)
            Toast.makeText(
                context,
                if (ok) "백업을 복원했어요." else "복원에 실패했어요. 올바른 백업 파일인지 확인해 주세요.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    val all = RecordStore.records.toList()
    val today = LocalDate.now()
    // 예정(미래) 대회는 참가 통계에서 제외
    val records = all.filter { !it.date.isAfter(today) }
    val upcomingCount = all.size - records.size
    val pbs = remember(records) { computePbs(records) }
    val thisYear = today.year
    val thisYearCount = records.count { it.date.year == thisYear }
    val typeCounts = records.groupingBy { it.type }.eachCount()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("요약 · 개인 PB") },
                actions = {
                    Text(
                        text = "v${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 16.dp),
                    )
                },
            )
        },
        bottomBar = bottomBar,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = innerPadding.calculateBottomPadding() + 24.dp,
                start = 16.dp,
                end = 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("참가 요약", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(12.dp))
                        Row {
                            StatItem("전체 대회", "${records.size}회", Modifier.weight(1f))
                            StatItem("올해 참가", "${thisYearCount}회", Modifier.weight(1f))
                            StatItem("PB 종목", "${pbs.size}개", Modifier.weight(1f))
                        }
                        if (typeCounts.isNotEmpty() || upcomingCount > 0) {
                            Spacer(Modifier.height(12.dp))
                            val parts = typeCounts.entries.map { (t, c) -> "${t.label} ${c}회" } +
                                if (upcomingCount > 0) listOf("예정 ${upcomingCount}개") else emptyList()
                            Text(
                                parts.joinToString("  ·  "),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            item {
                Text(
                    "개인 최고 기록 (PB)",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            if (pbs.isEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "아직 계산할 PB가 없어요.\n기록 추가 시 '기록(완주 시간)'을 입력하면 종목·거리별 최고 기록이 자동으로 표시됩니다.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            } else {
                items(pbs, key = { "${it.type.name}-${it.distanceLabel}" }) { pb ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    pb.distanceLabel,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Spacer(Modifier.width(8.dp))
                                AssistChip(onClick = {}, label = { Text(pb.type.label) })
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                formatSeconds(pb.seconds),
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "${pb.record.title.ifBlank { "(제목 없음)" }} · ${pb.record.date.format(pbDateFormatter)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            item(key = "backup") {
                Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("백업 / 복원", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "기록·사진을 하나의 파일로 저장해 Google Drive에 올리고, 다른 폰에서 그 파일을 불러와 복원할 수 있어요.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { exportLauncher.launch(BackupManager.suggestedFileName()) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("☁️ 백업 저장 (Google Drive 등)") }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                importLauncher.launch(
                                    arrayOf("application/zip", "application/octet-stream", "*/*"),
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("📥 백업 파일에서 복원") }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
