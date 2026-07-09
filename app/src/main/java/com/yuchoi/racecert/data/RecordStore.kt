package com.yuchoi.racecert.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * 대회 기록을 앱 내부 저장소(JSON 파일 + 이미지 파일)로 관리하는 단순 저장소.
 * Compose가 관찰할 수 있도록 records를 SnapshotStateList로 노출한다.
 */
object RecordStore {

    private lateinit var appContext: Context

    /** UI가 관찰하는 기록 목록. 정렬은 화면에서 처리한다. */
    val records: SnapshotStateList<RaceRecord> = mutableStateListOf()

    private val dataFile: File get() = File(appContext.filesDir, "records.json")
    private val imagesDir: File
        get() = File(appContext.filesDir, "images").apply { if (!exists()) mkdirs() }
    private val syncMetaFile: File get() = File(appContext.filesDir, "sync_local.txt")

    /** 로컬 데이터가 마지막으로 바뀐 시각(epoch millis). Drive 동기화 비교에 사용. */
    fun localUpdatedAt(): Long = runCatching { syncMetaFile.readText().trim().toLong() }.getOrDefault(0L)

    /** 원격에서 복원한 직후 등, 로컬 기준 시각을 명시적으로 맞춘다. */
    fun setLocalUpdatedAt(millis: Long) {
        runCatching { syncMetaFile.writeText(millis.toString()) }
    }

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        load()
        // 앱 시작 시(진행 중인 편집 없음) 어떤 기록도 참조하지 않는 고아 이미지 정리
        pruneOrphanImages()
    }

    /** 어떤 기록도 참조하지 않는 이미지 파일을 삭제해 저장공간을 회수한다. */
    private fun pruneOrphanImages() {
        runCatching {
            val referenced = records.flatMap { it.imagePaths }.map { File(it).name }.toHashSet()
            imagesDir.listFiles()?.forEach { f ->
                if (f.name !in referenced) f.delete()
            }
        }
    }

    /** 백업 복원 후 디스크에서 다시 읽어 목록을 갱신한다. */
    fun reload() {
        load()
    }

    fun find(id: String): RaceRecord? = records.firstOrNull { it.id == id }

    fun newId(): String = UUID.randomUUID().toString()

    /** 기록을 추가하거나(같은 id 없으면) 기존 기록을 교체한다. */
    fun upsert(record: RaceRecord) {
        val index = records.indexOfFirst { it.id == record.id }
        if (index >= 0) records[index] = record else records.add(record)
        persist()
    }

    /** 기록과 그에 딸린 이미지 파일을 함께 삭제한다. */
    fun delete(id: String) {
        val record = find(id) ?: return
        records.remove(record)
        record.imagePaths.forEach { path -> runCatching { File(path).delete() } }
        persist()
    }

    /**
     * 선택한 이미지 URI들을 내부 저장소로 복사하고 저장된 파일 경로 목록을 돌려준다.
     * 갤러리(Google Photos 등)에서 고른 사진의 실제 사본을 앱이 보관하게 된다.
     */
    suspend fun importImages(uris: List<Uri>): List<String> = withContext(Dispatchers.IO) {
        val resolver = appContext.contentResolver
        val paths = mutableListOf<String>()
        for (uri in uris) {
            val dest = File(imagesDir, "${UUID.randomUUID()}.jpg")
            runCatching {
                resolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
            }.onSuccess {
                if (dest.exists() && dest.length() > 0) paths.add(dest.absolutePath)
            }
        }
        paths
    }

    /** 웹 이미지 URL을 내부 저장소로 다운로드하고 저장된 파일 경로를 반환한다. */
    suspend fun importImageUrl(url: String): String? = withContext(Dispatchers.IO) {
        val dest = File(imagesDir, "${UUID.randomUUID()}.jpg")
        runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36",
                )
                conn.connectTimeout = 15_000
                conn.readTimeout = 15_000
                conn.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } }
            } finally {
                conn.disconnect()
            }
        }
        if (dest.exists() && dest.length() > 0) dest.absolutePath else null
    }

    /** 이미지를 시계방향 90도 회전해 새 파일로 저장하고 경로를 반환한다. */
    suspend fun rotateImage(path: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val src = BitmapFactory.decodeFile(path) ?: return@withContext null
            val matrix = Matrix().apply { postRotate(90f) }
            val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
            val dest = File(imagesDir, "${UUID.randomUUID()}.jpg")
            dest.outputStream().use { rotated.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            if (src != rotated) src.recycle()
            rotated.recycle()
            if (dest.exists() && dest.length() > 0) dest.absolutePath else null
        }.getOrNull()
    }

    private fun load() {
        records.clear()
        if (!dataFile.exists()) return
        runCatching {
            val array = JSONArray(dataFile.readText())
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val imagesJson = obj.optJSONArray("imagePaths") ?: JSONArray()
                // 이미지 경로는 파일명만 취해 현재 기기의 images 폴더로 재매핑한다.
                // (다른 기기에서 복원해도 경로가 어긋나지 않도록)
                val images = (0 until imagesJson.length())
                    .map { imagesJson.getString(it) }
                    .map { File(imagesDir, File(it).name).absolutePath }
                records.add(
                    RaceRecord(
                        id = obj.getString("id"),
                        title = obj.optString("title"),
                        type = RaceType.fromName(obj.optString("type")),
                        dateEpochDay = obj.optLong("dateEpochDay"),
                        imagePaths = images,
                        memo = obj.optString("memo"),
                        createdAt = obj.optLong("createdAt"),
                        recordTime = obj.optString("recordTime"),
                        distance = obj.optString("distance"),
                        ocrText = obj.optString("ocrText"),
                        location = obj.optString("location"),
                        weather = obj.optString("weather"),
                        bodyInfo = obj.optString("bodyInfo"),
                        bodyDateEpochDay = obj.optLong("bodyDateEpochDay"),
                        entryFee = obj.optString("entryFee"),
                        eventFee = obj.optString("eventFee"),
                        eventNote = obj.optString("eventNote"),
                        bib = obj.optString("bib"),
                        startTime = obj.optString("startTime"),
                        mainImageIndex = obj.optInt("mainImageIndex"),
                        bgImageIndex = obj.optInt("bgImageIndex"),
                    )
                )
            }
        }
    }

    private fun persist() {
        val array = JSONArray()
        for (record in records) {
            val obj = JSONObject()
            obj.put("id", record.id)
            obj.put("title", record.title)
            obj.put("type", record.type.name)
            obj.put("dateEpochDay", record.dateEpochDay)
            obj.put("imagePaths", JSONArray(record.imagePaths))
            obj.put("memo", record.memo)
            obj.put("createdAt", record.createdAt)
            obj.put("recordTime", record.recordTime)
            obj.put("distance", record.distance)
            obj.put("ocrText", record.ocrText)
            obj.put("location", record.location)
            obj.put("weather", record.weather)
            obj.put("bodyInfo", record.bodyInfo)
            obj.put("bodyDateEpochDay", record.bodyDateEpochDay)
            obj.put("entryFee", record.entryFee)
            obj.put("eventFee", record.eventFee)
            obj.put("eventNote", record.eventNote)
            obj.put("bib", record.bib)
            obj.put("startTime", record.startTime)
            obj.put("mainImageIndex", record.mainImageIndex)
            obj.put("bgImageIndex", record.bgImageIndex)
            array.put(obj)
        }
        runCatching { dataFile.writeText(array.toString()) }
        // 로컬 변경 시각 갱신 (동기화 비교용)
        runCatching { syncMetaFile.writeText(System.currentTimeMillis().toString()) }
    }
}
