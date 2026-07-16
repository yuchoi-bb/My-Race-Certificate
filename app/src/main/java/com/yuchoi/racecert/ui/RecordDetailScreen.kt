package com.yuchoi.racecert.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image as ForegroundImage
import com.yuchoi.racecert.data.RecordStore
import java.time.format.DateTimeFormatter

private val detailFormatter = DateTimeFormatter.ofPattern("yy년 M월 d일 (E)")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordDetailScreen(
    recordId: String,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDeleted: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val record = remember(recordId, RecordStore.records.toList()) { RecordStore.find(recordId) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (record == null) {
        // 이미 삭제된 경우: 컴포지션 이후 안전하게 뒤로 이동
        androidx.compose.runtime.LaunchedEffect(Unit) { onBack() }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(record.title.ifBlank { "(제목 없음)" }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                },
                actions = {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Filled.Edit, contentDescription = "수정")
                    }
                    IconButton(onClick = { showDeleteConfirm = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "삭제")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssistChip(onClick = {}, label = { Text(record.type.label) })
                if (record.subEvent.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    AssistChip(onClick = {}, label = { Text(record.subEvent) })
                }
                Spacer(Modifier.width(12.dp))
                Text(record.date.format(detailFormatter), style = MaterialTheme.typography.bodyLarge)
            }

            if (record.recordTime.isNotBlank() || record.distance.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (record.distance.isNotBlank()) {
                        Text(
                            record.distance,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(12.dp))
                    }
                    if (record.recordTime.isNotBlank()) {
                        Text(
                            record.recordTime,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            if (record.startTime.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "🕘 시작 ${record.startTime}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 기존 정보 + Strava 상세를 하나의 카드로 합치고 중복(완주 합계 등)은 제거
            // recordTime을 위에서 이미 크게 보여주므로 Strava 요약의 "🏁 합계" 줄은 뺀다.
            val stravaDetail = record.stravaInfo.lineSequence()
                .filter { it.isNotBlank() && !it.trimStart().startsWith("🏁") }
                .joinToString("\n")
            val hasInfo = record.location.isNotBlank() || record.weather.isNotBlank() ||
                record.bodyInfo.isNotBlank() || record.entryFee.isNotBlank() ||
                record.eventFee.isNotBlank() || record.bib.isNotBlank() ||
                stravaDetail.isNotBlank() || record.routePolyline.isNotBlank()
            if (hasInfo) {
                Spacer(Modifier.height(12.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        if (stravaDetail.isNotBlank()) {
                            Text(stravaDetail, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(6.dp))
                        }
                        if (record.bib.isNotBlank()) {
                            Text("🎽 배번 ${record.bib}", style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(4.dp))
                        }
                        if (record.totalFeeAmount > 0) {
                            val total = "%,d".format(record.totalFeeAmount)
                            if (record.eventFeeAmount > 0) {
                                Text(
                                    "💳 총 참가비 ${total}원 " +
                                        "(기본 ${"%,d".format(record.baseFeeAmount)} + 이벤트 ${"%,d".format(record.eventFeeAmount)})",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            } else {
                                Text("💳 참가비 ${total}원", style = MaterialTheme.typography.bodyMedium)
                            }
                            if (record.eventNote.isNotBlank()) {
                                Text(
                                    "🎁 ${record.eventNote}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                        if (record.location.isNotBlank()) {
                            Text("📍 ${record.location}", style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(4.dp))
                        }
                        if (record.weather.isNotBlank()) {
                            Text("☀️ ${record.weather}", style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(4.dp))
                        }
                        if (record.bodyInfo.isNotBlank()) {
                            val offset = record.bodyOffsetLabel
                            val text = if (offset.isBlank()) "⚖️ ${record.bodyInfo}"
                            else "⚖️ ${record.bodyInfo}  ($offset)"
                            Text(text, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(4.dp))
                        }
                        if (record.routePolyline.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "코스",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(4.dp))
                            RouteMap(
                                polyline = record.routePolyline,
                                modifier = Modifier.fillMaxWidth().height(200.dp),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            if (record.imagePaths.isEmpty()) {
                Text("등록된 기록증 사진이 없어요.", style = MaterialTheme.typography.bodyMedium)
            } else {
                // 메인 이미지(선택) + 아래 썸네일. 썸네일을 탭하면 메인이 교체된다.
                var selected by remember(record.id) { mutableStateOf(0) }
                val mainPath = record.imagePaths.getOrElse(selected) { record.imagePaths.first() }
                val bitmap = rememberSampledBitmap(mainPath, reqSizePx = 1440)
                // 어떤 사진을 골라도 같은 크기 틀 안에 표시 (사진마다 화면이 널뛰지 않게 고정)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(440.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    if (bitmap != null) {
                        ForegroundImage(
                            bitmap = bitmap,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                if (record.imagePaths.size > 1) {
                    Spacer(Modifier.height(10.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        itemsIndexed(record.imagePaths) { index, path ->
                            val thumb = rememberSampledBitmap(path, reqSizePx = 256)
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .then(
                                        if (index == selected) Modifier.border(
                                            2.dp,
                                            MaterialTheme.colorScheme.primary,
                                            RoundedCornerShape(8.dp),
                                        ) else Modifier,
                                    )
                                    .clickable { selected = index },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (thumb != null) {
                                    ForegroundImage(
                                        bitmap = thumb,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            if (record.memo.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "느낀점 / 메모",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(8.dp))
                        // 길게 눌러 블록 지정 → 복사 가능
                        SelectionContainer {
                            Text(record.memo, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("기록 삭제") },
            text = { Text("이 대회 기록과 등록한 사진을 삭제할까요? 되돌릴 수 없어요.") },
            confirmButton = {
                TextButton(onClick = {
                    RecordStore.delete(record.id)
                    com.yuchoi.racecert.sync.DriveSync.requestSync(context)
                    showDeleteConfirm = false
                    onDeleted()
                }) { Text("삭제") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("취소") }
            },
        )
    }
}
