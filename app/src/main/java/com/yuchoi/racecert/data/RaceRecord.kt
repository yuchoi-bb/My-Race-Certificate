package com.yuchoi.racecert.data

import java.time.LocalDate

enum class RaceType(val label: String) {
    MARATHON("마라톤"),
    TRIATHLON("철인3종"),
    GRANFONDO("그란폰도"),
    OTHER("기타");

    companion object {
        fun fromName(name: String?): RaceType =
            entries.firstOrNull { it.name == name } ?: OTHER
    }
}

/** 대회 기록 하나. 기록증 이미지는 앱 내부 저장소에 복사된 파일 경로로 보관한다. */
data class RaceRecord(
    val id: String,
    val title: String,
    val type: RaceType,
    val dateEpochDay: Long,
    val imagePaths: List<String>,
    val memo: String,
    val createdAt: Long,
    val recordTime: String = "",
    val distance: String = "",
    val ocrText: String = "",
    val location: String = "",
    val weather: String = "",
    val bodyInfo: String = "",
    val bodyDateEpochDay: Long = 0,
) {
    val date: LocalDate get() = LocalDate.ofEpochDay(dateEpochDay)

    /** 몸 상태를 잰 날짜 (미설정이면 null) */
    val bodyDate: LocalDate? get() = if (bodyDateEpochDay > 0) LocalDate.ofEpochDay(bodyDateEpochDay) else null

    /** 몸 상태 측정일이 대회일 기준 며칠 전/후인지 라벨 (예: "대회 -3일", "대회 +2일") */
    val bodyOffsetLabel: String
        get() {
            val bd = bodyDate ?: return ""
            return when (val diff = bd.toEpochDay() - dateEpochDay) {
                0L -> "대회 당일"
                else -> if (diff < 0) "대회 ${diff}일" else "대회 +${diff}일"
            }
        }

    companion object {
        const val MAX_IMAGES = 10
    }
}
