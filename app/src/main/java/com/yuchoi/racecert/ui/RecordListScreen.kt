package com.yuchoi.racecert.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image as ForegroundImage
import com.yuchoi.racecert.BuildConfig
import com.yuchoi.racecert.data.RaceRecord
import com.yuchoi.racecert.data.RaceType
import com.yuchoi.racecert.data.RecordStore
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private val dateFormatter = DateTimeFormatter.ofPattern("yyyy.MM.dd")

// 카드 배경 파스텔 팔레트
private val PastelRed = Color(0xFFFFCDD2) // 마라톤 풀코스
private val PastelGreen = Color(0xFFC8E6C9) // 마라톤 하프
private val PastelBlue = Color(0xFFBBDEFB) // 마라톤 10km
private val PastelGray = Color(0xFFEEEEEE) // 그 외
private val PastelAmber = Color(0xFFFFECB3) // 예정된 대회 (D-day)

/** 마라톤은 거리별 색, 그 외 종목은 그레이 */
private fun cardColor(record: RaceRecord): Color {
    if (record.type != RaceType.MARATHON) return PastelGray
    val d = record.distance.trim().uppercase().replace(" ", "")
    return when {
        d.contains("풀") || d.contains("FULL") || d.contains("42") -> PastelRed
        d.contains("하프") || d.contains("HALF") || d.contains("21") -> PastelGreen
        d.contains("10") -> PastelBlue
        else -> PastelGray
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordListScreen(
    onAddRecord: () -> Unit,
    onOpenRecord: (String) -> Unit,
    onCheckUpdate: () -> Unit,
    bottomBar: @Composable () -> Unit = {},
) {
    // true = 최신순(내림차순), false = 오래된순(오름차순) — 지난 대회에만 적용
    var newestFirst by remember { mutableStateOf(true) }

    val today = LocalDate.now()
    val all = RecordStore.records.toList()
    // 미래 날짜로 등록한 대회 = 신청해 둔 예정 대회. 가까운 순으로 맨 위에 고정.
    val upcoming = remember(all) {
        all.filter { it.date.isAfter(today) }.sortedBy { it.dateEpochDay }
    }
    val past = remember(all, newestFirst) {
        val comparator = compareBy<RaceRecord>({ it.dateEpochDay }, { it.createdAt })
        val base = all.filter { !it.date.isAfter(today) }
        if (newestFirst) base.sortedWith(comparator.reversed()) else base.sortedWith(comparator)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("나의 대회 기록증") },
                actions = {
                    TextButton(onClick = { newestFirst = !newestFirst }) {
                        Icon(
                            imageVector = if (newestFirst) Icons.Filled.ArrowDownward
                            else Icons.Filled.ArrowUpward,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(if (newestFirst) "최신순" else "오래된순")
                    }
                    IconButton(onClick = onCheckUpdate) {
                        Icon(Icons.Filled.SystemUpdate, contentDescription = "업데이트 확인")
                    }
                    Text(
                        text = "v${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 12.dp),
                    )
                },
            )
        },
        bottomBar = bottomBar,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddRecord,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("기록 추가") },
            )
        },
    ) { innerPadding ->
        if (all.isEmpty()) {
            EmptyState(Modifier.fillMaxSize().padding(innerPadding))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding() + 8.dp,
                    bottom = innerPadding.calculateBottomPadding() + 96.dp,
                    start = 16.dp,
                    end = 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (upcoming.isNotEmpty()) {
                    item(key = "upcoming-header") {
                        SectionHeader("📅 예정된 대회")
                    }
                    upcoming.forEach { record ->
                        item(key = "up-${record.id}") {
                            UpcomingCard(
                                record = record,
                                daysLeft = ChronoUnit.DAYS.between(today, record.date),
                                onClick = { onOpenRecord(record.id) },
                            )
                        }
                    }
                }

                // 지난 대회: 연도가 바뀔 때마다 연도 헤더 삽입
                var lastYear: Int? = null
                past.forEach { record ->
                    val year = record.date.year
                    if (year != lastYear) {
                        lastYear = year
                        item(key = "year-$year") { YearHeader(year) }
                    }
                    item(key = record.id) {
                        RecordCard(record = record, onClick = { onOpenRecord(record.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun YearHeader(year: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Text(
            text = "$year",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(12.dp))
        HorizontalDivider(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun UpcomingCard(record: RaceRecord, daysLeft: Long, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = PastelAmber),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Thumbnail(path = record.imagePaths.firstOrNull())
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = record.title.ifBlank { "(제목 없음)" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AssistChip(onClick = onClick, label = { Text(record.type.label) })
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = record.date.format(dateFormatter),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (record.location.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "📍 ${record.location}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = "D-$daysLeft",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFE65100),
            )
        }
    }
}

@Composable
private fun RecordCard(record: RaceRecord, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = cardColor(record)),
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Thumbnail(path = record.imagePaths.firstOrNull())
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = record.title.ifBlank { "(제목 없음)" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AssistChip(onClick = onClick, label = { Text(record.type.label) })
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = record.date.format(dateFormatter),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (record.recordTime.isNotBlank()) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = record.recordTime,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (record.distance.isNotBlank()) {
                        Text(
                            text = record.distance,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (record.imagePaths.size > 1) {
                        if (record.distance.isNotBlank()) {
                            Text(
                                text = "  ·  ",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = "사진 ${record.imagePaths.size}장",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Thumbnail(path: String?) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = path?.let { rememberSampledBitmap(it, reqSizePx = 256) }
        if (bitmap != null) {
            ForegroundImage(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                Icons.Filled.Image,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.EmojiEvents,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))
        Text("아직 등록된 대회 기록이 없어요", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "오른쪽 아래 '기록 추가'로 첫 대회 기록증을 등록해 보세요.\n미래 날짜로 등록하면 예정된 대회(D-day)로 표시됩니다.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
