package com.yuchoi.racecert.ui

import android.content.Context
import android.media.ExifInterface
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yuchoi.racecert.data.RaceRecord
import com.yuchoi.racecert.data.RaceType
import com.yuchoi.racecert.data.RecordStore
import com.yuchoi.racecert.sync.DriveSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val shareDateFormatter = DateTimeFormatter.ofPattern("yy.MM.dd")

/** 갤러리에서 공유(Share)로 받은 사진을 특정 대회 기록에 붙이는 화면. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareImportScreen(
    sharedUris: List<Uri>,
    onDone: () -> Unit,
    onOpenRecord: (String) -> Unit,
    onOpenEdit: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }

    // 첫 사진의 촬영일(EXIF)로 같은 날짜 대회를 위로 추천
    val photoDate by produceState<LocalDate?>(initialValue = null, sharedUris) {
        value = withContext(Dispatchers.IO) { readPhotoDate(context, sharedUris.firstOrNull()) }
    }

    val records = RecordStore.records.toList()
    val sorted = remember(records, photoDate) {
        records.sortedWith(
            compareByDescending<RaceRecord> { photoDate != null && it.date == photoDate }
                .thenByDescending { it.dateEpochDay },
        )
    }

    fun attachTo(record: RaceRecord) {
        if (working) return
        working = true
        scope.launch {
            val ordered = com.yuchoi.racecert.data.PhotoTime.sortByCaptureTime(context, sharedUris)
            val paths = RecordStore.importImages(ordered)
            // 기존 기록에 붙이는 사진은 기록증이 아니므로 모두 축소 저장
            paths.forEach { com.yuchoi.racecert.data.ImageCompressor.compressInPlace(it) }
            if (paths.isEmpty()) {
                working = false
                Toast.makeText(context, "사진을 가져오지 못했어요.", Toast.LENGTH_LONG).show()
                return@launch
            }
            RecordStore.upsert(record.copy(imagePaths = record.imagePaths + paths))
            DriveSync.requestSync(context)
            working = false
            Toast.makeText(
                context,
                "사진 ${paths.size}장을 '${record.title.ifBlank { "기록" }}'에 추가했어요.",
                Toast.LENGTH_LONG,
            ).show()
            onOpenRecord(record.id)
        }
    }

    fun addAsNew() {
        if (working) return
        working = true
        scope.launch {
            val ordered = com.yuchoi.racecert.data.PhotoTime.sortByCaptureTime(context, sharedUris)
            val paths = RecordStore.importImages(ordered)
            // 첫 사진은 대표(기록증일 수 있음)로 원본 유지, 나머지는 축소 저장
            paths.forEachIndexed { i, p ->
                if (i > 0) com.yuchoi.racecert.data.ImageCompressor.compressInPlace(p)
            }
            val id = RecordStore.newId()
            RecordStore.upsert(
                RaceRecord(
                    id = id,
                    title = "",
                    type = RaceType.MARATHON,
                    dateEpochDay = (photoDate ?: LocalDate.now()).toEpochDay(),
                    imagePaths = paths,
                    memo = "",
                    createdAt = System.currentTimeMillis(),
                ),
            )
            DriveSync.requestSync(context)
            working = false
            onOpenEdit(id)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("사진을 대회에 추가") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "취소")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "공유된 사진 ${sharedUris.size}장을 어느 대회에 추가할까요?",
                    style = MaterialTheme.typography.titleMedium,
                )
                photoDate?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "촬영일: ${it.format(shareDateFormatter)} — 같은 날짜 대회를 위에 추천했어요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Button(onClick = { addAsNew() }, enabled = !working) {
                    Text("➕ 새 기록으로 추가")
                }
            }

            if (working) {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            if (sorted.isEmpty()) {
                Text(
                    "아직 등록된 대회가 없어요. '새 기록으로 추가'를 눌러 주세요.",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(sorted, key = { it.id }) { record ->
                        val sameDay = photoDate != null && record.date == photoDate
                        Card(
                            modifier = Modifier.fillMaxWidth().clickable { attachTo(record) },
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        record.title.ifBlank { "(제목 없음)" },
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "${record.type.label} · ${record.date.format(shareDateFormatter)}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (sameDay) {
                                    AssistChip(onClick = { attachTo(record) }, label = { Text("📅 이 날짜") })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun readPhotoDate(context: Context, uri: Uri?): LocalDate? {
    if (uri == null) return null
    return runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val exif = ExifInterface(input)
            val raw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
            // 형식: "yyyy:MM:dd HH:mm:ss"
            val datePart = raw?.substringBefore(' ')?.split(':')
            if (datePart != null && datePart.size == 3) {
                LocalDate.of(datePart[0].toInt(), datePart[1].toInt(), datePart[2].toInt())
            } else {
                null
            }
        }
    }.getOrNull()
}
