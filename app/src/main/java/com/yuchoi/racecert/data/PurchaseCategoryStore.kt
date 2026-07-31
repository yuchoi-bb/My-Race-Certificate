package com.yuchoi.racecert.data

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import org.json.JSONArray
import java.io.File

/**
 * 구매 항목(카테고리) 이름 목록을 사용자가 추가·삭제할 수 있도록 관리하는 저장소.
 * "기타"는 항목이 삭제됐을 때의 대체 값으로도 쓰이므로 항상 남아 있고 삭제할 수 없다.
 */
object PurchaseCategoryStore {

    const val DEFAULT_CATEGORY = "기타"
    const val ENTRY_FEE_CATEGORY = "대회비"

    private val defaults = listOf(ENTRY_FEE_CATEGORY, "신발·의류", "장비", "영양", "교통·숙박", DEFAULT_CATEGORY)

    /** 예전 enum(PurchaseCategory) 이름으로 저장된 데이터를 새 이름으로 옮겨준다. */
    private val legacyNames = mapOf(
        "ENTRY_FEE" to ENTRY_FEE_CATEGORY,
        "SHOES_APPAREL" to "신발·의류",
        "GEAR" to "장비",
        "NUTRITION" to "영양",
        "TRAVEL" to "교통·숙박",
        "OTHER" to DEFAULT_CATEGORY,
    )

    fun normalize(raw: String): String {
        val mapped = legacyNames[raw] ?: raw
        return mapped.ifBlank { DEFAULT_CATEGORY }
    }

    private lateinit var appContext: Context

    val categories: SnapshotStateList<String> = mutableStateListOf()

    private val dataFile: File get() = File(appContext.filesDir, "purchase_categories.json")

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        load()
        if (categories.isEmpty()) {
            categories.addAll(defaults)
            persist()
        }
    }

    fun reload() = load()

    /** 새 항목을 추가한다. 빈 이름이거나 이미 있는 이름이면 무시한다. */
    fun add(label: String): Boolean {
        val trimmed = label.trim()
        if (trimmed.isBlank() || categories.contains(trimmed)) return false
        categories.add(trimmed)
        persist()
        return true
    }

    /** 항목을 삭제한다. 이 항목을 쓰던 구매·정기 지출은 "기타"로 옮겨진다. */
    fun delete(label: String) {
        if (label == DEFAULT_CATEGORY) return
        if (!categories.remove(label)) return
        persist()
        PurchaseStore.reassignCategory(label, DEFAULT_CATEGORY)
        RecurringPurchaseStore.reassignCategory(label, DEFAULT_CATEGORY)
    }

    private fun load() {
        categories.clear()
        if (!dataFile.exists()) return
        runCatching {
            val array = JSONArray(dataFile.readText())
            for (i in 0 until array.length()) categories.add(array.getString(i))
        }
    }

    private fun persist() {
        val array = JSONArray()
        categories.forEach { array.put(it) }
        runCatching { dataFile.writeText(array.toString()) }
    }
}
