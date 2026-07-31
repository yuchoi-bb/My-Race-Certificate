package com.yuchoi.racecert.data

import java.time.LocalDate

enum class PurchaseCategory(val label: String) {
    ENTRY_FEE("대회비"),
    SHOES_APPAREL("신발·의류"),
    GEAR("장비"),
    NUTRITION("영양"),
    TRAVEL("교통·숙박"),
    OTHER("기타");

    companion object {
        fun fromName(name: String?): PurchaseCategory =
            entries.firstOrNull { it.name == name } ?: OTHER
    }
}

/** 러닝 관련 지출 한 건 */
data class Purchase(
    val id: String,
    val dateEpochDay: Long,
    val category: PurchaseCategory,
    val amount: Long,
    val vendor: String = "",
    val memo: String = "",
    /** 영수증 사진 (선택, 내부 저장소 경로) */
    val receiptPath: String = "",
    /** 연결된 대회 기록 id (선택) */
    val linkedRecordId: String = "",
    val createdAt: Long = System.currentTimeMillis(),
) {
    val date: LocalDate get() = LocalDate.ofEpochDay(dateEpochDay)
}
