package com.yuchoi.racecert.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import com.yuchoi.racecert.net.ImageSearchService
import com.yuchoi.racecert.net.WeatherService
import com.yuchoi.racecert.ocr.CertificateParser
import com.yuchoi.racecert.ocr.OcrEngine
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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

private val formatter = DateTimeFormatter.ofPattern("yy.MM.dd")

/** 대회 이름에서 4자리 연도(19xx/20xx)를 모두 제거하고 공백을 정리한다. */
private fun stripYears(s: String): String =
    s.replace(Regex("""\b(19|20)\d{2}\b"""), " ").replace(Regex("""\s+"""), " ").trim()

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
    onDone: (String) -> Unit,
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
    // 신규 기록의 시작 시간 기본값 08:00 (서울이면 07:30로 자동 조정)
    var startTime by remember { mutableStateOf(existing?.startTime ?: "08:00") }
    var startTimeManuallySet by remember { mutableStateOf(existing != null) }
    var distance by remember { mutableStateOf(existing?.distance ?: "") }
    var bib by remember { mutableStateOf(existing?.bib ?: "") }
    var entryFee by remember { mutableStateOf(existing?.entryFee ?: "") }
    var eventFee by remember { mutableStateOf(existing?.eventFee ?: "") }
    var eventNote by remember { mutableStateOf(existing?.eventNote ?: "") }
    var ocrText by remember { mutableStateOf(existing?.ocrText ?: "") }
    var location by remember { mutableStateOf(existing?.location ?: "") }
    var weather by remember { mutableStateOf(existing?.weather ?: "") }
    var bodyInfo by remember { mutableStateOf(existing?.bodyInfo ?: "") }
    var bodyDate by remember { mutableStateOf(existing?.bodyDate) }
    var weatherLoading by remember { mutableStateOf(false) }
    val imagePaths: SnapshotStateList<String> =
        remember { existing?.imagePaths.orEmpty().toMutableStateList() }
    // 카드 대표(썸네일)·배경 이미지로 쓸 사진의 인덱스 (업로드 사진 중에서 각각 선택)
    var mainImageIndex by remember { mutableStateOf(existing?.mainImageIndex ?: 0) }
    var bgImageIndex by remember { mutableStateOf(existing?.bgImageIndex ?: 0) }

    var showDatePicker by remember { mutableStateOf(false) }
    var showBodyDatePicker by remember { mutableStateOf(false) }
    var typeExpanded by remember { mutableStateOf(false) }
    var ocrRunning by remember { mutableStateOf(false) }
    // OCR가 날짜/종목을 함부로 덮어쓰지 않도록, 사용자가 직접 만졌는지 추적
    var dateManuallySet by remember { mutableStateOf(existing != null) }
    var typeManuallySet by remember { mutableStateOf(existing != null) }
    // OCR 완료 후 필수 항목(대회 이름·날짜·기록) 인식 결과 안내 다이얼로그
    var ocrSummary by remember { mutableStateOf<OcrSummary?>(null) }
    var showPhotoSourceDialog by remember { mutableStateOf(false) }
    // 탭한 썸네일을 크게 보는 미리보기
    var previewPath by remember { mutableStateOf<String?>(null) }
    // 날씨용 장소 후보 (선택 다이얼로그)
    var placeCandidates by remember { mutableStateOf<List<WeatherService.Place>>(emptyList()) }
    // 예정 대회: 대회명 웹 이미지 검색 결과
    var webImages by remember { mutableStateOf<List<ImageSearchService.WebImage>>(emptyList()) }
    var webSearchLoading by remember { mutableStateOf(false) }
    var showWebImageDialog by remember { mutableStateOf(false) }

    /**
     * 사진을 OCR로 읽어 빈 칸을 초안으로 채운다. 사용자는 이후 자유롭게 수정 가능.
     * - rebuild=true: 첨부된 전체 사진을 다시 읽는다(수동 '다시 읽기').
     * - rebuild=false: [imagesToRead](새로 추가된 사진)만 읽어 기존 OCR 원문에 덧붙인다.
     */
    suspend fun ocrAndFill(imagesToRead: List<String>, rebuild: Boolean) {
        if (imagePaths.isEmpty()) {
            Toast.makeText(context, "먼저 기록증 사진을 추가해 주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        ocrRunning = true
        val fresh = StringBuilder()
        for (path in imagesToRead) {
            val text = OcrEngine.recognize(context, path)
            if (text.isNotBlank()) {
                if (fresh.isNotEmpty()) fresh.append("\n---\n")
                fresh.append(text)
            }
        }
        val combined = if (rebuild) {
            fresh.toString()
        } else {
            listOf(ocrText, fresh.toString()).filter { it.isNotBlank() }.joinToString("\n---\n")
        }
        val parsed = CertificateParser.parse(combined)
        // 빈 칸만 채우고, 날짜·종목은 사용자가 안 만졌을 때만 반영
        if (title.isBlank()) parsed.title?.let { title = stripYears(it) }
        if (recordTime.isBlank()) parsed.recordTime?.let { recordTime = it }
        if (distance.isBlank()) parsed.distance?.let { distance = it }
        if (bib.isBlank()) parsed.bib?.let { bib = it }
        if (!dateManuallySet) parsed.date?.let { date = it }
        if (!typeManuallySet) parsed.type?.let { type = it }
        ocrText = combined
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
        if (bib.isNotBlank()) found.add("배번" to bib)
        ocrSummary = OcrSummary(
            imageCount = imagePaths.size,
            textFound = combined.isNotBlank(),
            found = found,
            missing = missing,
        )
    }

    // 포토 피커/다른 앱 어느 쪽에서 골라도 동일하게 처리: 복사 → 장수 제한 → 전체 OCR
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
            // 새로 추가한 사진만 OCR해서 기존 인식 원문에 덧붙인다 (전체 재인식 방지)
            ocrAndFill(newPaths, rebuild = false)
        }
    }

    // 예정 대회: 대회명으로 웹 이미지 검색 (가장 적합한 5장). 없으면 안내.
    fun searchRaceImages() {
        if (title.isBlank()) {
            Toast.makeText(context, "먼저 대회 이름을 입력해 주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            webSearchLoading = true
            val results = ImageSearchService.search(title.trim(), limit = 5)
            webSearchLoading = false
            if (results.isEmpty()) {
                Toast.makeText(context, "‘${title.trim()}’ 대회 사진을 찾지 못했어요. (✕)", Toast.LENGTH_LONG).show()
            } else {
                webImages = results
                showWebImageDialog = true
            }
        }
    }

    fun attachWebImage(image: ImageSearchService.WebImage) {
        showWebImageDialog = false
        if (imagePaths.size >= RaceRecord.MAX_IMAGES) {
            Toast.makeText(context, "이미지는 최대 ${RaceRecord.MAX_IMAGES}장까지예요.", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            val path = RecordStore.importImageUrl(image.imageUrl)
                ?: RecordStore.importImageUrl(image.thumbnailUrl)
            if (path != null) {
                imagePaths.add(path)
                Toast.makeText(context, "대회 사진을 추가했어요.", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "사진을 내려받지 못했어요. 다른 이미지를 골라 주세요.", Toast.LENGTH_LONG).show()
            }
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(RaceRecord.MAX_IMAGES)
    ) { uris -> handlePickedUris(uris) }

    // 기기 갤러리·구글포토 등 앱 선택창을 띄워 가져오기 (다중 선택 지원)
    val contentPicker = rememberLauncherForActivityResult(
        PickImagesViaChooser()
    ) { uris -> handlePickedUris(uris) }

    // 대회 날짜 기준 기기 사진 그리드 (일주일 전~당일)
    var showDeviceGrid by remember { mutableStateOf(false) }
    var deviceLoading by remember { mutableStateOf(false) }
    val devicePhotos = remember { mutableStateListOf<Uri>() }
    val selectedDevice = remember { mutableStateListOf<Uri>() }

    // 장소가 서울이면 시작 시간을 07:30로, 그 외엔 08:00로 자동 조정 (사용자가 직접 바꾸기 전까지)
    androidx.compose.runtime.LaunchedEffect(location) {
        if (!startTimeManuallySet) {
            startTime = if (location.contains("서울")) "07:30" else "08:00"
        }
    }

    fun loadDevicePhotos() {
        scope.launch {
            deviceLoading = true
            showDeviceGrid = true
            selectedDevice.clear()
            val photos = com.yuchoi.racecert.data.DevicePhotos.photosAround(context, date)
            devicePhotos.clear()
            devicePhotos.addAll(photos)
            deviceLoading = false
        }
    }

    val mediaPermission =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE
    val mediaPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) loadDevicePhotos()
        else Toast.makeText(context, "사진 접근 권한이 필요해요.", Toast.LENGTH_SHORT).show()
    }

    fun openDeviceGrid() {
        if (ContextCompat.checkSelfPermission(context, mediaPermission) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            loadDevicePhotos()
        } else {
            mediaPermLauncher.launch(mediaPermission)
        }
    }

    // ── 헬스커넥트에서 몸 상태(몸무게·체지방) 가져오기 ──
    // 어디서 막히는지 알 수 있게 단계마다 화면에 숫자 코드를 띄운다.
    var bodyFetching by remember { mutableStateOf(false) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_LONG).show()

    fun applyReading(r: com.yuchoi.racecert.health.HealthConnectBody.BodyReading) {
        val parts = mutableListOf<String>()
        r.weightKg?.let { parts += "%.1fkg".format(it) }
        r.bodyFatPct?.let { parts += "체지방 %.1f%%".format(it) }
        bodyInfo = parts.joinToString(", ")
        r.date?.let { bodyDate = it }
        toast("코드 6: 가져왔어요 → $bodyInfo" + (r.date?.let { " (측정일 ${it.format(formatter)})" } ?: ""))
    }

    fun readAndFill() {
        scope.launch {
            bodyFetching = true
            try {
                val r = com.yuchoi.racecert.health.HealthConnectBody.readNear(context, date)
                bodyFetching = false
                if (r.isEmpty) {
                    toast("코드 5: 대회일 ±14일 범위에 몸무게/체지방 데이터가 없어요. (삼성헬스·가민커넥트가 헬스커넥트로 동기화했는지 확인)")
                } else {
                    applyReading(r)
                }
            } catch (e: Exception) {
                bodyFetching = false
                toast("코드 9: 읽기 오류 — ${e.message}")
            }
        }
    }

    val hcPermLauncher = rememberLauncherForActivityResult(
        androidx.health.connect.client.PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        if (granted.containsAll(com.yuchoi.racecert.health.HealthConnectBody.PERMISSIONS)) {
            toast("코드 3: 권한 허용됨 — 읽는 중")
            readAndFill()
        } else {
            toast("코드 4: 권한이 거부됐어요. 헬스커넥트에서 '몸무게·체지방 읽기'를 허용해 주세요.")
        }
    }

    fun fetchBodyFromHealth() {
        when (com.yuchoi.racecert.health.HealthConnectBody.availability(context)) {
            1 -> toast("코드 1: 이 기기는 헬스커넥트를 지원하지 않아요.")
            2 -> toast("코드 2: 헬스커넥트 앱 설치/업데이트가 필요해요. (Play 스토어에서 'Health Connect')")
            else -> scope.launch {
                val granted = try {
                    com.yuchoi.racecert.health.HealthConnectBody.hasPermissions(context)
                } catch (e: Exception) {
                    toast("코드 8: 권한 확인 오류 — ${e.message}")
                    return@launch
                }
                if (granted) {
                    readAndFill()
                } else {
                    hcPermLauncher.launch(com.yuchoi.racecert.health.HealthConnectBody.PERMISSIONS)
                }
            }
        }
    }

    // 장소 후보를 찾아 선택 다이얼로그를 띄운다
    fun searchPlaces() {
        if (location.isBlank()) {
            Toast.makeText(context, "먼저 대회 장소를 입력해 주세요. (예: 용인, 수원)", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            weatherLoading = true
            val places = WeatherService.searchPlaces(location.trim())
            weatherLoading = false
            if (places.isEmpty()) {
                Toast.makeText(
                    context,
                    "장소 '${location.trim()}'를 찾지 못했어요. 도시/지역명으로 입력해 주세요. (예: 용인, 수원)",
                    Toast.LENGTH_LONG,
                ).show()
            } else {
                placeCandidates = places
            }
        }
    }

    // 선택한 장소의 대회 날짜 날씨를 기입
    fun applyWeatherFor(place: WeatherService.Place) {
        placeCandidates = emptyList()
        scope.launch {
            weatherLoading = true
            val result = WeatherService.weatherAt(
                place,
                date,
                startTime = startTime.ifBlank { null },
                recordTime = recordTime.ifBlank { null },
            )
            weatherLoading = false
            when (result) {
                is WeatherService.Result.Success -> weather = result.text
                WeatherService.Result.NoWeatherData -> Toast.makeText(
                    context,
                    "그 날짜의 날씨 데이터가 없어요. 미래 대회는 16일 이내 예보만 가능해요.",
                    Toast.LENGTH_LONG,
                ).show()
                WeatherService.Result.NetworkError -> Toast.makeText(
                    context,
                    "네트워크 오류로 날씨를 못 가져왔어요. 인터넷 연결을 확인하고 다시 시도해 주세요.",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    var showDiscardDialog by remember { mutableStateOf(false) }

    fun doSave() {
        if (title.isBlank()) {
            Toast.makeText(context, "대회 이름을 입력해 주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        val record = RaceRecord(
            id = existing?.id ?: RecordStore.newId(),
            title = stripYears(title.trim()),
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
            entryFee = entryFee.trim(),
            eventFee = eventFee.trim(),
            eventNote = eventNote.trim(),
            bib = bib.trim(),
            startTime = startTime.trim(),
            mainImageIndex = mainImageIndex.coerceIn(0, (imagePaths.size - 1).coerceAtLeast(0)),
            bgImageIndex = bgImageIndex.coerceIn(0, (imagePaths.size - 1).coerceAtLeast(0)),
        )
        RecordStore.upsert(record)
        com.yuchoi.racecert.sync.DriveSync.requestSync(context)
        onDone(record.id)
    }

    // 저장하지 않은 변경사항이 있는지
    val dirty = if (existing == null) {
        title.isNotBlank() || recordTime.isNotBlank() || distance.isNotBlank() ||
            bib.isNotBlank() || entryFee.isNotBlank() || eventFee.isNotBlank() ||
            eventNote.isNotBlank() || location.isNotBlank() || weather.isNotBlank() ||
            bodyInfo.isNotBlank() || memo.isNotBlank() || ocrText.isNotBlank() ||
            startTimeManuallySet ||
            imagePaths.isNotEmpty() || dateManuallySet || typeManuallySet
    } else {
        title.trim() != existing.title || recordTime.trim() != existing.recordTime ||
            distance.trim() != existing.distance || bib.trim() != existing.bib ||
            entryFee.trim() != existing.entryFee || eventFee.trim() != existing.eventFee ||
            eventNote.trim() != existing.eventNote || location.trim() != existing.location ||
            weather.trim() != existing.weather || bodyInfo.trim() != existing.bodyInfo ||
            memo.trim() != existing.memo || ocrText.trim() != existing.ocrText ||
            imagePaths.toList() != existing.imagePaths || type != existing.type ||
            date != existing.date || bodyDate != existing.bodyDate ||
            startTime.trim() != existing.startTime ||
            mainImageIndex != existing.mainImageIndex || bgImageIndex != existing.bgImageIndex
    }

    fun attemptBack() {
        if (dirty) showDiscardDialog = true else onCancel()
    }

    BackHandler(enabled = true) { attemptBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existing == null) "기록 추가" else "기록 수정") },
                navigationIcon = {
                    IconButton(onClick = { attemptBack() }) {
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
                    modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
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

            // 미래 날짜면 '예정된 대회'로 안내 + D-day 표시
            val daysLeft = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), date)
            if (daysLeft > 0) {
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = androidx.compose.ui.graphics.Color(0xFFFFECB3),
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "🗓️ 예정된 대회로 등록돼요",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                "기록·사진은 대회를 마친 뒤 이 기록을 열어 추가하면 됩니다.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "D-$daysLeft",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            color = androidx.compose.ui.graphics.Color(0xFFE65100),
                        )
                    }
                }
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
                mainIndex = mainImageIndex,
                bgIndex = bgImageIndex,
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
                onRemove = { path ->
                    val removed = imagePaths.indexOf(path)
                    if (removed >= 0) {
                        imagePaths.removeAt(removed)
                        // 삭제로 인덱스가 밀리므로 대표·배경 선택을 보정
                        fun remap(sel: Int) = when {
                            sel == removed -> 0
                            sel > removed -> sel - 1
                            else -> sel
                        }
                        mainImageIndex = remap(mainImageIndex)
                        bgImageIndex = remap(bgImageIndex)
                    }
                },
                onPreview = { path -> previewPath = path },
                onSetMain = { index -> mainImageIndex = index },
                onSetBg = { index -> bgImageIndex = index },
                onRotate = { index ->
                    val path = imagePaths.getOrNull(index) ?: return@ImageStrip
                    scope.launch {
                        val rotated = RecordStore.rotateImage(path)
                        if (rotated != null && index < imagePaths.size) {
                            imagePaths[index] = rotated
                        } else {
                            Toast.makeText(context, "회전에 실패했어요.", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onMoveLeft = { index ->
                    if (index > 0) {
                        val tmp = imagePaths[index]
                        imagePaths[index] = imagePaths[index - 1]
                        imagePaths[index - 1] = tmp
                        // 순서가 바뀌면 대표·배경 선택도 따라 이동
                        fun swap(sel: Int) = when (sel) { index -> index - 1; index - 1 -> index; else -> sel }
                        mainImageIndex = swap(mainImageIndex)
                        bgImageIndex = swap(bgImageIndex)
                    }
                },
                onMoveRight = { index ->
                    if (index < imagePaths.size - 1) {
                        val tmp = imagePaths[index]
                        imagePaths[index] = imagePaths[index + 1]
                        imagePaths[index + 1] = tmp
                        fun swap(sel: Int) = when (sel) { index -> index + 1; index + 1 -> index; else -> sel }
                        mainImageIndex = swap(mainImageIndex)
                        bgImageIndex = swap(bgImageIndex)
                    }
                },
            )

            // 예정 대회(미래 날짜)면 대회명으로 웹 이미지 검색 버튼 제공
            if (date.isAfter(LocalDate.now())) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { searchRaceImages() },
                    enabled = !webSearchLoading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (webSearchLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("대회 사진 검색 중…")
                    } else {
                        Text("🔎 대회명으로 사진 검색 (웹, 최대 5장)")
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            // 첨부된 사진 전체를 OCR로 다시 읽기 (사진 추가 시 자동 실행되지만 수동 재실행도 지원)
            OutlinedButton(
                onClick = { scope.launch { ocrAndFill(imagePaths.toList(), rebuild = true) } },
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
                label = { Text("기록 (완주 시간, 예: 00:44:16 · 예정 대회는 비워두세요)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = startTime,
                onValueChange = { startTime = it; startTimeManuallySet = true },
                label = { Text("대회 시작 시간 (기본 08:00 · 서울이면 07:30)") },
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
                value = bib,
                onValueChange = { bib = it },
                label = { Text("배번호 (사진에서 자동 인식, 예: 11000)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = entryFee,
                onValueChange = { entryFee = it },
                label = { Text("기본 참가비 (실제 금액, 예: 40,000원)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = eventFee,
                onValueChange = { eventFee = it },
                label = { Text("이벤트 추가금 (증정품 등, 예: 300,000원)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = eventNote,
                onValueChange = { eventNote = it },
                label = { Text("이벤트 구성 / 증정품 (예: 러닝화, 대회복)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            // 기본 + 이벤트 추가금 총액 미리보기
            run {
                val base = entryFee.filter { it.isDigit() }.toLongOrNull() ?: 0L
                val event = eventFee.filter { it.isDigit() }.toLongOrNull() ?: 0L
                if (base + event > 0) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "총 참가비: ${"%,d".format(base + event)}원" +
                            if (event > 0) " (기본 ${"%,d".format(base)} + 이벤트 ${"%,d".format(event)})" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
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
                onClick = { searchPlaces() },
                enabled = !weatherLoading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (weatherLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("조회 중…")
                } else {
                    Text("☀️ 장소 선택 후 날씨 가져오기")
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
                onClick = { fetchBodyFromHealth() },
                enabled = !bodyFetching,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (bodyFetching) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.AutoAwesome, contentDescription = null)
                }
                Spacer(Modifier.width(8.dp))
                Text("헬스커넥트에서 몸 상태 가져오기 (삼성헬스·가민)")
            }
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
                modifier = Modifier.fillMaxWidth().height(280.dp),
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
                onClick = { doSave() },
                enabled = title.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (date.isAfter(LocalDate.now())) "예정 대회 저장" else "저장")
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

    if (showWebImageDialog && webImages.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showWebImageDialog = false },
            title = { Text("대회 사진 선택 (탭하면 추가)") },
            text = {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(webImages, key = { it.imageUrl }) { img ->
                        val bmp = rememberUrlBitmap(img.thumbnailUrl, reqSizePx = 400)
                        Box(
                            modifier = Modifier
                                .size(140.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { attachWebImage(img) },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (bmp != null) {
                                ForegroundImage(
                                    bitmap = bmp,
                                    contentDescription = img.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showWebImageDialog = false }) { Text("닫기") }
            },
        )
    }

    if (placeCandidates.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { placeCandidates = emptyList() },
            title = { Text("어느 지역인가요?") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                ) {
                    placeCandidates.forEach { place ->
                        TextButton(
                            onClick = { applyWeatherFor(place) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    place.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    place.displayName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { placeCandidates = emptyList() }) { Text("취소") }
            },
        )
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("저장하지 않고 나갈까요?") },
            text = { Text("입력한 내용이 저장되지 않았어요.") },
            confirmButton = {
                if (title.isNotBlank()) {
                    TextButton(onClick = {
                        showDiscardDialog = false
                        doSave()
                    }) { Text("저장하고 나가기") }
                } else {
                    TextButton(onClick = {
                        showDiscardDialog = false
                        onCancel()
                    }) { Text("나가기") }
                }
            },
            dismissButton = {
                Row {
                    if (title.isNotBlank()) {
                        TextButton(onClick = {
                            showDiscardDialog = false
                            onCancel()
                        }) { Text("저장 안 함") }
                    }
                    TextButton(onClick = { showDiscardDialog = false }) { Text("취소") }
                }
            },
        )
    }

    if (showDeviceGrid) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showDeviceGrid = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Card(modifier = Modifier.fillMaxWidth().padding(12.dp).height(560.dp)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "대회 무렵 사진 (${date.minusDays(7).format(formatter)} ~ ${date.format(formatter)})",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        when {
                            deviceLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                            devicePhotos.isEmpty() -> Text(
                                "이 기간에 촬영한 사진이 없어요.",
                                modifier = Modifier.align(Alignment.Center),
                            )
                            else -> LazyVerticalGrid(
                                columns = GridCells.Adaptive(96.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                gridItems(devicePhotos, key = { it.toString() }) { uri ->
                                    val selected = uri in selectedDevice
                                    Box(
                                        modifier = Modifier
                                            .size(96.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant)
                                            .clickable {
                                                if (selected) selectedDevice.remove(uri)
                                                else selectedDevice.add(uri)
                                            },
                                    ) {
                                        val bmp = rememberContentBitmap(context, uri, reqSizePx = 240)
                                        if (bmp != null) {
                                            ForegroundImage(
                                                bitmap = bmp,
                                                contentDescription = null,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize(),
                                            )
                                        }
                                        if (selected) {
                                            Icon(
                                                Icons.Filled.CheckCircle,
                                                contentDescription = "선택됨",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.align(Alignment.TopEnd).padding(2.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row {
                        TextButton(onClick = { showDeviceGrid = false }) { Text("취소") }
                        Spacer(Modifier.weight(1f))
                        Button(
                            onClick = {
                                val picked = selectedDevice.toList()
                                showDeviceGrid = false
                                handlePickedUris(picked)
                            },
                            enabled = selectedDevice.isNotEmpty(),
                        ) { Text("추가 (${selectedDevice.size})") }
                    }
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
                    TextButton(
                        onClick = {
                            showPhotoSourceDialog = false
                            openDeviceGrid()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("📅 대회 무렵 사진 (일주일 전~당일)") }
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
    mainIndex: Int,
    bgIndex: Int,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
    onPreview: (String) -> Unit,
    onSetMain: (Int) -> Unit,
    onSetBg: (Int) -> Unit,
    onRotate: (Int) -> Unit,
    onMoveLeft: (Int) -> Unit,
    onMoveRight: (Int) -> Unit,
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
        itemsIndexed(imagePaths, key = { _, path -> path }) { index, path ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
                    // 대표/배경으로 선택된 사진은 좌측 상단에 태그 표시
                    Column(modifier = Modifier.align(Alignment.TopStart).padding(3.dp)) {
                        if (index == mainIndex) CornerTag("대표", androidx.compose.ui.graphics.Color(0xFF1565C0))
                        if (index == bgIndex) CornerTag("배경", androidx.compose.ui.graphics.Color(0xFF6A1B9A))
                    }
                }
                // 대표(썸네일)·배경 지정
                Row(
                    modifier = Modifier.width(96.dp).padding(top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    SelectTag("대표", selected = index == mainIndex, modifier = Modifier.weight(1f)) { onSetMain(index) }
                    SelectTag("배경", selected = index == bgIndex, modifier = Modifier.weight(1f)) { onSetBg(index) }
                }
                // 순서 변경 / 회전 컨트롤
                Row(
                    modifier = Modifier.width(96.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    IconButton(onClick = { onMoveLeft(index) }, enabled = index > 0, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Filled.ChevronLeft, contentDescription = "왼쪽으로", modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = { onRotate(index) }, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Filled.RotateRight, contentDescription = "90도 회전", modifier = Modifier.size(18.dp))
                    }
                    IconButton(
                        onClick = { onMoveRight(index) },
                        enabled = index < imagePaths.size - 1,
                        modifier = Modifier.size(30.dp),
                    ) {
                        Icon(Icons.Filled.ChevronRight, contentDescription = "오른쪽으로", modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

/** 이미지 위 좌측 상단의 작은 태그 (대표/배경) */
@Composable
private fun CornerTag(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = androidx.compose.ui.graphics.Color.White,
        modifier = Modifier
            .padding(bottom = 2.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.85f))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

/** 대표/배경 지정 토글 버튼 */
@Composable
private fun SelectTag(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = fg)
    }
}
