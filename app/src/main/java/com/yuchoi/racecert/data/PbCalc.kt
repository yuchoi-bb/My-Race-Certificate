package com.yuchoi.racecert.data

/**
 * 종목 + 거리 그룹별 개인 최고 기록(PB) 계산. 요약 화면과 기록 목록(카드 PB 배지)이 공유한다.
 */
object PbCalc {

    /** 종목+거리 그룹의 최고 기록(PB) 한 건 */
    data class PbEntry(
        val type: RaceType,
        val distanceLabel: String,
        val seconds: Int,
        val record: RaceRecord,
    )

    /** "HH:MM:SS" → 초. 형식이 아니면 null */
    fun parseTimeSeconds(t: String): Int? {
        val m = Regex("""^(\d{1,2}):(\d{2}):(\d{2})$""").find(t.trim()) ?: return null
        val (h, min, s) = m.destructured
        return h.toInt() * 3600 + min.toInt() * 60 + s.toInt()
    }

    /** 거리 문자열을 PB 그룹용으로 정규화 (10Km/10K/10km → 10km, HALF/하프 → 하프 …) */
    fun normalizeDistance(raw: String): String {
        val d = raw.trim().uppercase().replace(" ", "")
        return when {
            d.isEmpty() -> "거리 미입력"
            d.contains("하프") || d.contains("HALF") -> "하프"
            d.contains("풀") || d.contains("FULL") || d.contains("42.195") || d.contains("42K") -> "풀코스"
            else -> {
                val km = Regex("""(\d{1,3}(?:\.\d+)?)K""").find(d)?.groupValues?.get(1)
                if (km != null) "${km}km" else raw.trim()
            }
        }
    }

    /** 그룹별 PB 목록 (종목→기록순 정렬) */
    fun compute(records: List<RaceRecord>): List<PbEntry> =
        records.mapNotNull { r ->
            val sec = parseTimeSeconds(r.recordTime) ?: return@mapNotNull null
            PbEntry(r.type, normalizeDistance(r.distance), sec, r)
        }
            .groupBy { it.type to it.distanceLabel }
            .map { (_, entries) -> entries.minBy { it.seconds } }
            .sortedWith(compareBy({ it.type.ordinal }, { it.seconds }))

    /** PB에 해당하는 기록 id 집합 (카드에 PB 배지 표시용) */
    fun bestRecordIds(records: List<RaceRecord>): Set<String> =
        compute(records).map { it.record.id }.toHashSet()
}
