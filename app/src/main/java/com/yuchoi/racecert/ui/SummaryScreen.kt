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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yuchoi.racecert.BuildConfig
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import com.yuchoi.racecert.data.BackupManager
import com.yuchoi.racecert.data.PbCalc
import com.yuchoi.racecert.data.RecordStore
import com.yuchoi.racecert.sync.DriveSync
import kotlinx.coroutines.launch
import java.time.LocalDate

private val categoryOrder = listOf("10K", "하프", "풀코스", "기타", "거리 미입력")

/** 거리 문자열을 참가 요약용 카테고리로 분류 */
private fun distanceCategory(distance: String): String {
    val d = distance.trim().uppercase().replace(" ", "")
    return when {
        d.isEmpty() -> "거리 미입력"
        d.contains("하프") || d.contains("HALF") || d.contains("21") -> "하프"
        d.contains("풀") || d.contains("FULL") || d.contains("42") -> "풀코스"
        d.contains("10") -> "10K"
        else -> "기타"
    }
}

private fun syncMessage(r: DriveSync.SyncResult): String = when (r) {
    DriveSync.SyncResult.UPLOADED -> "이 폰의 기록을 Drive에 올렸어요."
    DriveSync.SyncResult.DOWNLOADED -> "Drive의 최신 기록을 받아왔어요."
    DriveSync.SyncResult.IN_SYNC -> "이미 최신 상태예요."
    DriveSync.SyncResult.NOT_SIGNED_IN -> "로그인이 필요해요."
    DriveSync.SyncResult.ERROR -> "동기화 실패 (네트워크/권한/OAuth 설정 확인)."
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SummaryScreen(
    bottomBar: @Composable () -> Unit,
    onOpenRecord: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Google Drive 자동 동기화 상태
    var syncEmail by remember { mutableStateOf(DriveSync.accountEmail(context)) }
    var syncStatus by remember { mutableStateOf("") }
    val signInLauncher = rememberLauncherForActivityResult(StartActivityForResult()) { result ->
        val account = DriveSync.accountFromIntent(result.data)
        if (account != null) {
            syncEmail = account.email
            syncStatus = "동기화 중…"
            DriveSync.requestSync(context) { r -> syncStatus = syncMessage(r) }
        } else {
            syncStatus = "로그인에 실패했어요. (OAuth 설정/네트워크 확인)"
        }
    }

    fun syncNow() {
        syncStatus = "동기화 중…"
        DriveSync.requestSync(context) { r -> syncStatus = syncMessage(r) }
    }

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
            val msg = when (BackupManager.import(context, uri)) {
                BackupManager.ImportResult.Success -> "백업을 복원했어요."
                BackupManager.ImportResult.CantOpen ->
                    "파일을 열 수 없어요. Google Drive라면 먼저 다운로드(오프라인 저장) 후 다시 시도해 주세요."
                BackupManager.ImportResult.NotAZip ->
                    "이 앱에서 만든 백업(.zip) 파일이 아니에요. race-backup-….zip 파일을 골라 주세요."
                BackupManager.ImportResult.NoRecords ->
                    "백업 안에 기록 데이터가 없어요. 다른 백업 파일인지 확인해 주세요."
                BackupManager.ImportResult.IoError ->
                    "복원 중 오류가 났어요. 다시 시도해 주세요."
            }
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }

    val all = RecordStore.records.toList()
    val today = LocalDate.now()
    // 예정(미래) 대회는 참가 통계에서 제외
    val records = all.filter { !it.date.isAfter(today) }
    val upcomingCount = all.size - records.size
    val pbs = remember(records) { PbCalc.compute(records) }
    val thisYear = today.year
    val thisYearCount = records.count { it.date.year == thisYear }
    val typeCounts = records.groupingBy { it.type }.eachCount()
    // 올해 참가비 합계 (기본 + 이벤트 추가금)
    val thisYearRecords = records.filter { it.date.year == thisYear }
    val thisYearFee = thisYearRecords.sumOf { it.totalFeeAmount }
    val thisYearEventFee = thisYearRecords.sumOf { it.eventFeeAmount }

    // 거리(카테고리) × 연도별 참가 횟수
    val breakdown: Map<String, Map<Int, Int>> = remember(records) {
        records.groupBy { distanceCategory(it.distance) }
            .mapValues { (_, list) -> list.groupingBy { it.date.year }.eachCount() }
    }
    var summaryExpanded by remember { mutableStateOf(false) }

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
                Card(
                    modifier = Modifier.fillMaxWidth()
                        .clickable { summaryExpanded = !summaryExpanded },
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("참가 요약", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.weight(1f))
                            Text(
                                if (summaryExpanded) "접기 ▲" else "거리·연도별 보기 ▼",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
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
                        if (thisYearFee > 0) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "올해 참가비 합계 ${"%,d".format(thisYearFee)}원" +
                                    if (thisYearEventFee > 0) " (이벤트 ${"%,d".format(thisYearEventFee)} 포함)" else "",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }

                        if (summaryExpanded) {
                            Spacer(Modifier.height(12.dp))
                            HorizontalDivider()
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "거리별 · 연도별 참가",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(8.dp))
                            if (breakdown.isEmpty()) {
                                Text(
                                    "아직 참가 기록이 없어요.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                categoryOrder.forEach { cat ->
                                    val years = breakdown[cat] ?: return@forEach
                                    val total = years.values.sum()
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        "$cat · 총 ${total}회",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        years.toSortedMap(compareByDescending { it })
                                            .entries.joinToString("  ·  ") { (y, c) -> "${y}년 ${c}회" },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
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
                    // 기록 목록과 완전히 동일한 카드(사진·배경 이미지·날씨·기록 등)로 표시하고,
                    // 클릭하면 기록 상세로 이동한다.
                    RecordCard(
                        record = pb.record,
                        onClick = { onOpenRecord(pb.record.id) },
                        isPb = true,
                    )
                }
            }

            item(key = "autosync") {
                Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("자동 동기화 (Google Drive)", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Google 계정으로 로그인하면 같은 계정을 쓰는 다른 폰과 기록이 자동으로 동기화돼요.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        val email = syncEmail
                        if (email == null) {
                            Button(
                                onClick = { signInLauncher.launch(DriveSync.client(context).signInIntent) },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("🔗 Google로 로그인하여 동기화") }
                        } else {
                            Text("로그인: $email", style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = { syncNow() },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("🔄 지금 동기화") }
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = {
                                    DriveSync.signOut(context) {
                                        syncEmail = null
                                        syncStatus = ""
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("로그아웃") }
                        }
                        if (syncStatus.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                syncStatus,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            item(key = "strava") {
                var stravaConnected by remember { mutableStateOf(com.yuchoi.racecert.strava.StravaAuth.isConnected(context)) }
                Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Strava 연동", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Strava 계정을 연결하면, 기록 추가/수정 화면에서 '이 대회 날짜 러닝 가져오기'로 완주 시간·거리·시작 시간을 자동으로 채울 수 있어요. (가민 러닝도 Strava로 들어오면 함께)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        if (stravaConnected) {
                            val who = com.yuchoi.racecert.strava.StravaAuth.athleteName(context)
                            Text("연결됨${who?.let { " · $it" } ?: ""}", style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = {
                                    com.yuchoi.racecert.strava.StravaAuth.disconnect(context)
                                    stravaConnected = false
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Strava 연결 해제") }
                        } else {
                            Button(
                                onClick = { context.startActivity(com.yuchoi.racecert.strava.StravaAuth.authorizeIntent()) },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("🔗 Strava 연결") }
                            if (!com.yuchoi.racecert.strava.StravaAuth.hasClientSecret()) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "※ 서버에 Strava Client Secret이 아직 설정되지 않았어요. (GitHub Secret STRAVA_CLIENT_SECRET 추가 후 다음 빌드부터 동작)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }

            item(key = "backup") {
                Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("수동 백업 / 복원", style = MaterialTheme.typography.titleMedium)
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
