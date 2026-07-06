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
) {
    val date: LocalDate get() = LocalDate.ofEpochDay(dateEpochDay)

    companion object {
        const val MAX_IMAGES = 10
    }
}
