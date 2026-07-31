package com.yuchoi.racecert.data

import java.time.LocalDate

/**
 * 매달 같은 날짜에 반복되는 지출(동호회 회비·강습료 등) 템플릿.
 * 실제 개별 지출 건은 이 템플릿을 바탕으로 PurchaseStore에 자동 생성된다.
 */
data class RecurringPurchase(
    val id: String,
    val label: String,
    val category: String,
    val amount: Long,
    /** 매월 청구일 (1~28, 말일 문제 방지를 위해 28일까지만 허용) */
    val dayOfMonth: Int,
    val startDateEpochDay: Long,
    /** null = 종료일 없음(무제한) */
    val endDateEpochDay: Long? = null,
    /** null = 횟수 제한 없음 (무제한 또는 종료일 기준으로만 판단) */
    val repeatCount: Int? = null,
    val memo: String = "",
    val active: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val startDate: LocalDate get() = LocalDate.ofEpochDay(startDateEpochDay)
    val endDate: LocalDate? get() = endDateEpochDay?.let { LocalDate.ofEpochDay(it) }
}
