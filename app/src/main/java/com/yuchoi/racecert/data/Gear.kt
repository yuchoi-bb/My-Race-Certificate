package com.yuchoi.racecert.data

/** 준비물 체크리스트 한 항목 */
data class GearItem(
    val id: String,
    val section: String,
    val label: String,
    val checked: Boolean = false,
)

/** 준비물 섹션(고정 3단계: 착용 → 대회용 작은가방 → 큰 가방) */
object GearSections {
    const val WEAR = "착용"
    const val BAG = "대회용 작은가방"
    const val AFTER = "큰 가방 (대회 후)"
    val ORDER = listOf(WEAR, BAG, AFTER)
}
