package com.yuchoi.racecert.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
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
import com.yuchoi.racecert.data.RaceRecord
import com.yuchoi.racecert.data.RecordStore
import java.time.format.DateTimeFormatter

private val dateFormatter = DateTimeFormatter.ofPattern("yyyy.MM.dd")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordListScreen(
    onAddRecord: () -> Unit,
    onOpenRecord: (String) -> Unit,
    onCheckUpdate: () -> Unit,
) {
    // true = 최신순(내림차순), false = 오래된순(오름차순)
    var newestFirst by remember { mutableStateOf(true) }

    val sorted = remember(RecordStore.records.toList(), newestFirst) {
        val comparator = compareBy<RaceRecord>({ it.dateEpochDay }, { it.createdAt })
        if (newestFirst) RecordStore.records.sortedWith(comparator.reversed())
        else RecordStore.records.sortedWith(comparator)
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
                    androidx.compose.material3.IconButton(onClick = onCheckUpdate) {
                        Icon(Icons.Filled.SystemUpdate, contentDescription = "업데이트 확인")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddRecord,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("기록 추가") },
            )
        },
    ) { innerPadding ->
        if (sorted.isEmpty()) {
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
                items(sorted, key = { it.id }) { record ->
                    RecordCard(record = record, onClick = { onOpenRecord(record.id) })
                }
            }
        }
    }
}

@Composable
private fun RecordCard(record: RaceRecord, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
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
                if (record.imagePaths.size > 1) {
                    Spacer(Modifier.height(4.dp))
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

@Composable
private fun Thumbnail(path: String?) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
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
            "오른쪽 아래 '기록 추가'로 첫 대회 기록증을 등록해 보세요.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
