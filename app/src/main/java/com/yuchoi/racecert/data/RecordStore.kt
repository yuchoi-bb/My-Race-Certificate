package com.yuchoi.racecert.data

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
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

    private fun load() {
        records.clear()
        if (!dataFile.exists()) return
        runCatching {
            val array = JSONArray(dataFile.readText())
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val imagesJson = obj.optJSONArray("imagePaths") ?: JSONArray()
                val images = (0 until imagesJson.length()).map { imagesJson.getString(it) }
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
            array.put(obj)
        }
        runCatching { dataFile.writeText(array.toString()) }
    }
}
