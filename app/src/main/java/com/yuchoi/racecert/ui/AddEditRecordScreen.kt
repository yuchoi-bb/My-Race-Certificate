package com.yuchoi.racecert.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import com.yuchoi.racecert.ocr.CertificateParser
import com.yuchoi.racecert.ocr.OcrEngine
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image as ForegroundImage
import com.yuchoi.racecert.data.RaceRecord
import com.yuchoi.racecert.data.RaceType
import com.yuchoi.racecert.data.RecordStore
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val formatter = DateTimeFormatter.ofPattern("yyyy.MM.dd")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditRecordScreen(
    recordId: String?,
    onDone: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val existing = remember(recordId) { recordId?.let { RecordStore.find(it) } }

    var title by remember { mutableStateOf(existing?.title ?: "") }
    var type by remember { mutableStateOf(existing?.type ?: RaceType.MARATHON) }
    var date by remember { mutableStateOf(existing?.date ?: LocalDate.now()) }
    var memo by remember { mutableStateOf(existing?.memo ?: "") }
    var recordTime by remember { mutableStateOf(existing?.recordTime ?: "") }
    var distance by remember { mutableStateOf(existing?.distance ?: "") }
    var ocrText by remember { mutableStateOf(existing?.ocrText ?: "") }
    val imagePaths: SnapshotStateList<String> =
        remember { existing?.imagePaths.orEmpty().toMutableStateList() }

    var showDatePicker by remember { mutableStateOf(false) }
    var typeExpanded by remember { mutableStateOf(false) }
    var ocrRunning by remember { mutableStateOf(false) }
    // OCR가 날짜/종목을 함부로 덮어쓰지 않도록, 사용자가 직접 만졌는지 추적
    var dateManuallySet by remember { mutableStateOf(existing != null) }
    var typeManuallySet by remember { mutableStateOf(existing != null) }

    // 첨부된 모든 사진을 OCR로 읽어 빈 칸을 초안으로 채운다. 사용자는 이후 자유롭게 수정 가능.
    suspend fun runOcrOnAllImages() {
        if (imagePaths.isEmpty()) {
            Toast.makeText(context, "먼저 기록증 사진을 추가해 주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        ocrRunning = true
        val combined = StringBuilder()
        for (path in imagePaths) {
            val text = OcrEngine.recognize(context, path)
            if (text.isNotBlank()) {
                if (combined.isNotEmpty()) combined.append("\n---\n")
                combined.append(text)
            }
        }
        val parsed = CertificateParser.parse(combined.toString())
        // 빈 칸만 채우고, 날짜·종목은 사용자가 안 만졌을 때만 반영
        if (title.isBlank()) parsed.title?.let { title = it }
        if (recordTime.isBlank()) parsed.recordTime?.let { recordTime = it }
        if (distance.isBlank()) parsed.distance?.let { distance = it }
        if (!dateManuallySet) parsed.date?.let { date = it }
        if (!typeManuallySet) parsed.type?.let { type = it }
        ocrText = combined.toString()
        ocrRunning = false
        Toast.makeText(
            context,
            if (combined.isBlank()) "사진에서 글자를 찾지 못했어요. 직접 입력해 주세요."
            else "사진 ${imagePaths.size}장에서 정보를 읽었어요. 내용을 확인·수정해 주세요.",
            Toast.LENGTH_LONG,
        ).show()
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(RaceRecord.MAX_IMAGES)
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val remaining = RaceRecord.MAX_IMAGES - imagePaths.size
        if (remaining <= 0) {
            Toast.makeText(context, "이미지는 최대 ${RaceRecord.MAX_IMAGES}장까지예요.", Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        val toImport = uris.take(remaining)
        scope.launch {
            val newPaths = RecordStore.importImages(toImport)
            imagePaths.addAll(newPaths)
            if (uris.size > remaining) {
                Toast.makeText(
                    context,
                    "최대 ${RaceRecord.MAX_IMAGES}장까지만 추가돼요.",
                    Toast.LENGTH_SHORT,
                ).show()
            }
            // 사진을 추가하면 첨부된 전체 사진을 자동으로 OCR
            runOcrOnAllImages()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existing == null) "기록 추가" else "기록 수정") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "뒤로")
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
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("대회 이름") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))

            ExposedDropdownMenuBox(
                expanded = typeExpanded,
                onExpandedChange = { typeExpanded = it },
            ) {
                OutlinedTextField(
                    value = type.label,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("종목") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeExpanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                )
                ExposedDropdownMenu(
                    expanded = typeExpanded,
                    onDismissRequest = { typeExpanded = false },
                ) {
                    RaceType.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            onClick = {
                                type = option
                                typeManuallySet = true
                                typeExpanded = false
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = { showDatePicker = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.CalendarMonth, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("대회 날짜: ${date.format(formatter)}")
            }
            Spacer(Modifier.height(16.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("기록증 사진", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(8.dp))
                Text(
                    "${imagePaths.size}/${RaceRecord.MAX_IMAGES}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            ImageStrip(
                imagePaths = imagePaths,
                onAdd = {
                    if (imagePaths.size >= RaceRecord.MAX_IMAGES) {
                        Toast.makeText(
                            context,
                            "이미지는 최대 ${RaceRecord.MAX_IMAGES}장까지예요.",
                            Toast.LENGTH_SHORT,
                        ).show()
                    } else {
                        picker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }
                },
                onRemove = { path -> imagePaths.remove(path) },
            )
            Spacer(Modifier.height(8.dp))

            // 첨부된 사진 전체를 OCR로 다시 읽기 (사진 추가 시 자동 실행되지만 수동 재실행도 지원)
            OutlinedButton(
                onClick = { scope.launch { runOcrOnAllImages() } },
                enabled = !ocrRunning && imagePaths.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (ocrRunning) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("사진에서 정보 읽는 중…")
                } else {
                    Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("사진에서 정보 읽기 (OCR)")
                }
            }
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = recordTime,
                onValueChange = { recordTime = it },
                label = { Text("기록 (완주 시간, 예: 00:44:16)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = distance,
                onValueChange = { distance = it },
                label = { Text("거리 / 부문 (예: 10Km, 하프, 풀코스)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = memo,
                onValueChange = { memo = it },
                label = { Text("대회 느낀점 / 메모") },
                modifier = Modifier.fillMaxWidth().height(140.dp),
            )
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = ocrText,
                onValueChange = { ocrText = it },
                label = { Text("OCR 인식 원문 (자유롭게 수정 가능)") },
                modifier = Modifier.fillMaxWidth().height(120.dp),
            )
            Spacer(Modifier.height(24.dp))

            Button(
                onClick = {
                    val record = RaceRecord(
                        id = existing?.id ?: RecordStore.newId(),
                        title = title.trim(),
                        type = type,
                        dateEpochDay = date.toEpochDay(),
                        imagePaths = imagePaths.toList(),
                        memo = memo.trim(),
                        createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                        recordTime = recordTime.trim(),
                        distance = distance.trim(),
                        ocrText = ocrText.trim(),
                    )
                    RecordStore.upsert(record)
                    onDone()
                },
                enabled = title.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("저장")
            }
        }
    }

    if (showDatePicker) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                        dateManuallySet = true
                    }
                    showDatePicker = false
                }) { Text("확인") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("취소") }
            },
        ) {
            DatePicker(state = state)
        }
    }
}

@Composable
private fun ImageStrip(
    imagePaths: List<String>,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(onClick = onAdd),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.AddPhotoAlternate, contentDescription = "사진 추가")
                    Text("추가", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        items(imagePaths, key = { it }) { path ->
            Box(modifier = Modifier.size(96.dp)) {
                val bitmap = rememberSampledBitmap(path, reqSizePx = 384)
                if (bitmap != null) {
                    ForegroundImage(
                        bitmap = bitmap,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)),
                    )
                }
                IconButton(
                    onClick = { onRemove(path) },
                    modifier = Modifier.align(Alignment.TopEnd).size(28.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "삭제",
                            tint = androidx.compose.ui.graphics.Color.White,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}
