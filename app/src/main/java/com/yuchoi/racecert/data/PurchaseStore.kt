package com.yuchoi.racecert.data

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 러닝 관련 지출(구매) 내역을 앱 내부 저장소(JSON)에 보관하는 저장소.
 * 영수증 사진은 RecordStore와 같은 images 폴더를 공유하므로, 반드시
 * RecordStore.init()보다 먼저 초기화해야 시작 시 고아 이미지 정리에서
 * 영수증 사진이 잘못 삭제되지 않는다.
 */
object PurchaseStore {

    private lateinit var appContext: Context

    val purchases: SnapshotStateList<Purchase> = mutableStateListOf()

    private val dataFile: File get() = File(appContext.filesDir, "purchases.json")
    private val imagesDir: File
        get() = File(appContext.filesDir, "images").apply { if (!exists()) mkdirs() }

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        load()
    }

    fun reload() = load()

    fun newId(): String = UUID.randomUUID().toString()

    fun find(id: String): Purchase? = purchases.firstOrNull { it.id == id }

    fun upsert(purchase: Purchase) {
        val index = purchases.indexOfFirst { it.id == purchase.id }
        if (index >= 0) purchases[index] = purchase else purchases.add(purchase)
        persist()
    }

    fun delete(id: String) {
        val p = find(id) ?: return
        purchases.remove(p)
        if (p.receiptPath.isNotBlank()) runCatching { File(p.receiptPath).delete() }
        persist()
    }

    /** 영수증으로 참조 중인 이미지 파일명 (RecordStore 고아 이미지 정리·백업에서 함께 쓰임) */
    fun referencedImageNames(): Set<String> =
        purchases.mapNotNull { it.receiptPath.takeIf { p -> p.isNotBlank() } }
            .map { File(it).name }
            .toHashSet()

    private fun load() {
        purchases.clear()
        if (!dataFile.exists()) return
        runCatching {
            val array = JSONArray(dataFile.readText())
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                // 영수증 경로도 파일명만 취해 현재 기기의 images 폴더로 재매핑
                val rawReceipt = obj.optString("receiptPath")
                val receipt = if (rawReceipt.isBlank()) "" else File(imagesDir, File(rawReceipt).name).absolutePath
                purchases.add(
                    Purchase(
                        id = obj.getString("id"),
                        dateEpochDay = obj.optLong("dateEpochDay"),
                        category = PurchaseCategory.fromName(obj.optString("category")),
                        amount = obj.optLong("amount"),
                        vendor = obj.optString("vendor"),
                        memo = obj.optString("memo"),
                        receiptPath = receipt,
                        linkedRecordId = obj.optString("linkedRecordId"),
                        createdAt = obj.optLong("createdAt"),
                    ),
                )
            }
        }
    }

    private fun persist() {
        val array = JSONArray()
        for (p in purchases) {
            val obj = JSONObject()
            obj.put("id", p.id)
            obj.put("dateEpochDay", p.dateEpochDay)
            obj.put("category", p.category.name)
            obj.put("amount", p.amount)
            obj.put("vendor", p.vendor)
            obj.put("memo", p.memo)
            obj.put("receiptPath", p.receiptPath)
            obj.put("linkedRecordId", p.linkedRecordId)
            obj.put("createdAt", p.createdAt)
            array.put(obj)
        }
        runCatching { dataFile.writeText(array.toString()) }
    }
}
