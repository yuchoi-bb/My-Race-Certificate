package com.yuchoi.racecert.data

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.util.UUID

/**
 * 정기 지출(동호회 회비·강습료 등) 템플릿 저장소.
 * materializeDue()가 시작일부터 오늘까지 청구일이 지난 회차를 PurchaseStore에 채워 넣는다.
 * 이미 생성된 회차는 결정적인 id("recurring:<템플릿id>:<청구일>")로 건너뛰어 중복 생성을 막는다.
 */
object RecurringPurchaseStore {

    private lateinit var appContext: Context

    val items: SnapshotStateList<RecurringPurchase> = mutableStateListOf()

    private val dataFile: File get() = File(appContext.filesDir, "recurring_purchases.json")

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        load()
    }

    fun reload() = load()

    fun newId(): String = UUID.randomUUID().toString()

    fun find(id: String): RecurringPurchase? = items.firstOrNull { it.id == id }

    fun upsert(r: RecurringPurchase) {
        val index = items.indexOfFirst { it.id == r.id }
        if (index >= 0) items[index] = r else items.add(r)
        persist()
        materializeDue()
    }

    /** 템플릿만 지운다. 이미 생성된 지난 지출 내역은 구매 탭에 그대로 남는다. */
    fun delete(id: String) {
        val r = find(id) ?: return
        items.remove(r)
        persist()
    }

    /** 삭제된 항목(카테고리)을 쓰던 정기 지출 템플릿을 다른 항목으로 옮긴다. */
    fun reassignCategory(oldCategory: String, newCategory: String) {
        var changed = false
        items.forEachIndexed { index, r ->
            if (r.category == oldCategory) {
                items[index] = r.copy(category = newCategory)
                changed = true
            }
        }
        if (changed) persist()
    }

    /** 활성 템플릿마다 시작일~오늘 사이 청구일이 지난 회차를 구매 내역으로 생성한다. */
    fun materializeDue() {
        val today = LocalDate.now()
        items.filter { it.active }.forEach { r ->
            if (r.startDate.isAfter(today)) return@forEach
            var billing = firstBillingOnOrAfter(r.startDate, r.dayOfMonth)
            var count = 0
            while (!billing.isAfter(today)) {
                if (r.endDate != null && billing.isAfter(r.endDate)) break
                if (r.repeatCount != null && count >= r.repeatCount) break
                val pid = "recurring:${r.id}:${billing.toEpochDay()}"
                if (PurchaseStore.find(pid) == null) {
                    PurchaseStore.upsert(
                        Purchase(
                            id = pid,
                            dateEpochDay = billing.toEpochDay(),
                            category = r.category,
                            amount = r.amount,
                            vendor = r.label,
                            memo = r.memo,
                            receiptPath = "",
                            linkedRecordId = "",
                            createdAt = System.currentTimeMillis(),
                        ),
                    )
                }
                count++
                billing = clampDay(billing.plusMonths(1), r.dayOfMonth)
            }
        }
    }

    private fun clampDay(d: LocalDate, dayOfMonth: Int): LocalDate =
        d.withDayOfMonth(minOf(dayOfMonth, d.lengthOfMonth()))

    private fun firstBillingOnOrAfter(start: LocalDate, dayOfMonth: Int): LocalDate {
        var billing = clampDay(LocalDate.of(start.year, start.month, 1), dayOfMonth)
        if (billing.isBefore(start)) billing = clampDay(billing.plusMonths(1), dayOfMonth)
        return billing
    }

    private fun load() {
        items.clear()
        if (!dataFile.exists()) return
        runCatching {
            val array = JSONArray(dataFile.readText())
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                items.add(
                    RecurringPurchase(
                        id = obj.getString("id"),
                        label = obj.optString("label"),
                        category = PurchaseCategoryStore.normalize(obj.optString("category")),
                        amount = obj.optLong("amount"),
                        dayOfMonth = obj.optInt("dayOfMonth", 1),
                        startDateEpochDay = obj.optLong("startDateEpochDay"),
                        endDateEpochDay = if (obj.isNull("endDateEpochDay")) null else obj.optLong("endDateEpochDay"),
                        repeatCount = if (obj.isNull("repeatCount")) null else obj.optInt("repeatCount"),
                        memo = obj.optString("memo"),
                        active = obj.optBoolean("active", true),
                        createdAt = obj.optLong("createdAt"),
                    ),
                )
            }
        }
    }

    private fun persist() {
        val array = JSONArray()
        for (r in items) {
            val obj = JSONObject()
            obj.put("id", r.id)
            obj.put("label", r.label)
            obj.put("category", r.category)
            obj.put("amount", r.amount)
            obj.put("dayOfMonth", r.dayOfMonth)
            obj.put("startDateEpochDay", r.startDateEpochDay)
            obj.put("endDateEpochDay", r.endDateEpochDay ?: JSONObject.NULL)
            obj.put("repeatCount", r.repeatCount ?: JSONObject.NULL)
            obj.put("memo", r.memo)
            obj.put("active", r.active)
            obj.put("createdAt", r.createdAt)
            array.put(obj)
        }
        runCatching { dataFile.writeText(array.toString()) }
    }
}
