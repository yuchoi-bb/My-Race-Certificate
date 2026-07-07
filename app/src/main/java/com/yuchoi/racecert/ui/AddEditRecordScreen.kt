package com.yuchoi.racecert.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
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
import androidx.compose.material3.AlertDialog
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
import com.yuchoi.racecert.net.WeatherService
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

/**
 * ACTION_GET_CONTENT를 앱 선택창(chooser)으로 감싸서 실행한다.
 * GetMultipleContents는 기본 앱(구글포토)으로 바로 열리므로, 기기 갤러리 등
 * 다른 앱도 고를 수 있도록 항상 선택창을 띄운다. 다중 선택 지원.
 */
private class PickImagesViaChooser : ActivityResultContract<Unit, List<Uri>>() {
    override fun createIntent(context: Context, input: Unit): Intent {
        val getContent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        return Intent.createChooser(getContent, "사진을 가져올 앱 선택")
    }

    override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> {
        if (resultCode != Activity.RESULT_OK || intent == null) return emptyList()
        val clip = intent.clipData
        if (clip != null) {
            return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
        }
        return listOfNotNull(intent.data)
    }
}

/** OCR 완료 후 필수 항목(대회 이름·날짜·기록) 인식 결과 요약 */
private data class OcrSummary(
    val imageCount: Int,
    val textFound: Boolean,
    val found: List<Pair<String, String>>,
    val missing: List<String>,
)

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
    var location by remember { mutableStateOf(existing?.location ?: "") }
    var weather by remember { mutableStateOf(existing?.weather ?: "") }
    var bodyInfo by remember { mutableStateOf(existing?.bodyInfo ?: "") }
    var bodyDate by remember { mutableStateOf(existing?.bodyDate) }
    var weatherLoading by remember { mutableStateOf(false) }
    val imagePaths: SnapshotStateList<String> =
        remember { existing?.imagePaths.orEmpty().toMutableStateList() }

    var showDatePicker by remember { mutableStateOf(false) }
    var showBodyDatePicker by remember { mutableStateOf(false) }
    var typeExpanded by remember { mutableStateOf(false) }
    var ocrRunning by remember { mutableStateOf(false) }
    // OCR가 날짜/종목을 함부로 덮어쓰지 않도록, 사용자가 직접 만졌는지 추적
    var dateManuallySet by remember { mutableStateOf(existing != null) }
    var typeManuallySet by remember { mutableStateOf(existing != null) }
    // OCR 완료 후 필수 항목(대회 이름·날짜·기록) 인식 결과 안내 다이얼로그
    var ocrSummary by remember { mutableStateOf<OcrSummary?>(null) }

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

        // 필수 항목 인식 결과 정리: 채워진 값은 보여주고, 비어 있으면 직접 입력 안내
        val found = mutableListOf<Pair<String, String>>()
        val missing = mutableListOf<String>()
        if (title.isNotBlank()) found.add("대회 이름" to title) else missing.add("대회 이름")
        if (dateManuallySet || parsed.date != null) {
            found.add("대회 날짜" to date.format(formatter))
        } else {
            missing.add("대회 날짜")
        }
        if (recordTime.isNotBlank()) found.add("기록" to recordTime) else missing.add("기록")
        ocrSummary = OcrSummary(
            imageCount = imagePaths.size,
            textFound = combined.isNotBlank(),
            found = found,
            missing = missing,
        )
    }

    // 포토 피커/다른 앱 어느 쪽에서 골라도 동일하게 처리: 복사 → 10장 제한 → 전체 OCR
    fun handlePickedUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val remaining = RaceRecord.MAX_IMAGES - imagePaths.size
        if (remaining <= 0) {
            Toast.makeText(context, "이미지는 최대 ${RaceRecord.MAX_IMAGES}장까지예요.", Toast.LENGTH_SHORT).show()
            return
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

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(RaceRecord.MAX_IMAGES)
    ) { uris -> handlePickedUris(uris) }

    // 기기 갤러리·구글포토 등 앱 선택창을 띄워 가져오기 (다중 선택 지원)
    val contentPicker = rememberLauncherForActivityResult(
        PickImagesViaChooser()
    ) { uris -> handlePickedUris(uris) }

    var showPhotoSourceDialog by remember { mutableStateOf(false) }
    // 탭한 썸네일을 크게 보는 미리보기
    var previewPath by remember { mutableStateOf<String?>(null) }

    // 대회 장소 + 날짜로 당일 날씨 자동 기입 (Open-Meteo, 과거 날짜 지원)
    fun fetchWeather() {
        if (location.isBlank()) {
            Toast.makeText(context, "먼저 대회 장소를 입력해 주세요. (예: 수원)", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            weatherLoading = true
            val result = WeatherService.fetch(location.trim(), date)
            weatherLoading = false
            if (result != null) {
                weather = result
            } else {
                Toast.makeText(
                    context,
                    "날씨 정보를 찾지 못했어요. 장소 이름이나 날짜(예보는 16일 이내)를 확인해 주세요.",
                    Toast.LENGTH_LONG,
                ).show()
            }
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
                label = { Text("대회 이름 (필수)") },
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
                Text("대회 날짜 (필수): ${date.format(formatter)}")
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
                        showPhotoSourceDialog = true
                    }
                },
                onRemove = { path -> imagePaths.remove(path) },
                onPreview = { path -> previewPath = path },
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
                label = { Text("기록 (필수 · 완주 시간, 예: 00:44:16)") },
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
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = location,
                onValueChange = { location = it },
                label = { Text("대회 장소 (날씨 조회용, 예: 수원)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { fetchWeather() },
                enabled = !weatherLoading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (weatherLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("날씨 조회 중…")
                } else {
                    Text("☀️ 대회 당일 날씨 가져오기")
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = weather,
                onValueChange = { weather = it },
                label = { Text("대회 당일 날씨") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = bodyInfo,
                onValueChange = { bodyInfo = it },
                label = { Text("대회 주변 몸 상태 (예: 70.5kg, 체지방 18%)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { showBodyDatePicker = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.CalendarMonth, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                val label = bodyDate?.let { bd ->
                    val diff = bd.toEpochDay() - date.toEpochDay()
                    val offset = when {
                        diff == 0L -> "대회 당일"
                        diff < 0 -> "대회 ${diff}일"
                        else -> "대회 +${diff}일"
                    }
                    "측정일: ${bd.format(formatter)} ($offset)"
                } ?: "몸 상태 측정일 선택 (대회일 기준 -N/+N일 표시)"
                Text(label)
            }
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
                        location = location.trim(),
                        weather = weather.trim(),
                        bodyInfo = bodyInfo.trim(),
                        bodyDateEpochDay = if (bodyInfo.isBlank()) 0 else (bodyDate?.toEpochDay() ?: 0),
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

    // 썸네일 탭 → 전체 화면 미리보기 (아무 곳이나 탭하면 닫힘)
    previewPath?.let { path ->
        androidx.compose.ui.window.Dialog(onDismissRequest = { previewPath = null }) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { previewPath = null },
                contentAlignment = Alignment.Center,
            ) {
                val bitmap = rememberSampledBitmap(path, reqSizePx = 2048)
                if (bitmap != null) {
                    ForegroundImage(
                        bitmap = bitmap,
                        contentDescription = "기록증 미리보기",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    CircularProgressIndicator()
                }
            }
        }
    }

    if (showPhotoSourceDialog) {
        AlertDialog(
            onDismissRequest = { showPhotoSourceDialog = false },
            title = { Text("사진 가져오기") },
            text = {
                Column {
                    TextButton(
                        onClick = {
                            showPhotoSourceDialog = false
                            picker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("📱 갤러리에서 선택 (포토 피커)") }
                    TextButton(
                        onClick = {
                            showPhotoSourceDialog = false
                            contentPicker.launch(Unit)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("🖼️ 기기 갤러리 / 다른 앱 선택") }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showPhotoSourceDialog = false }) { Text("취소") }
            },
        )
    }

    ocrSummary?.let { summary ->
        AlertDialog(
            onDismissRequest = { ocrSummary = null },
            title = {
                Text(
                    if (summary.textFound) "사진 ${summary.imageCount}장에서 정보를 읽었어요"
                    else "사진에서 글자를 찾지 못했어요",
                )
            },
            text = {
                Column {
                    summary.found.forEach { (label, value) ->
                        Text("✅ $label: $value")
                        Spacer(Modifier.height(4.dp))
                    }
                    if (summary.missing.isNotEmpty()) {
                        if (summary.found.isNotEmpty()) Spacer(Modifier.height(8.dp))
                        Text(
                            "⚠️ 인식하지 못한 필수 항목",
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(4.dp))
                        summary.missing.forEach { label ->
                            Text(
                                "• $label — 직접 입력해 주세요",
                                color = MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.height(2.dp))
                        }
                    } else if (summary.textFound) {
                        Spacer(Modifier.height(8.dp))
                        Text("필수 항목이 모두 채워졌어요. 내용을 확인하고 저장해 주세요.")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { ocrSummary = null }) { Text("확인") }
            },
        )
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

    if (showBodyDatePicker) {
        val initial = (bodyDate ?: date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val state = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { showBodyDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        bodyDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                    }
                    showBodyDatePicker = false
                }) { Text("확인") }
            },
            dismissButton = {
                TextButton(onClick = { showBodyDatePicker = false }) { Text("취소") }
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
    onPreview: (String) -> Unit,
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
                        contentDescription = "미리보기",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onPreview(path) },
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
