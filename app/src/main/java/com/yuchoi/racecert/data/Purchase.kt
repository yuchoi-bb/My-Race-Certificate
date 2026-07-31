package com.yuchoi.racecert.data

import java.time.LocalDate

/** 러닝 관련 지출 한 건 */
data class Purchase(
    val id: String,
    val dateEpochDay: Long,
    /** 항목(카테고리) 이름. PurchaseCategoryStore에서 사용자가 추가·삭제할 수 있다. */
    val category: String,
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
