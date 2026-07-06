package com.yuchoi.racecert.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * Health Connect에서 대회일 전후의 몸무게/체지방을 읽어온다.
 * 가민 커넥트 앱이 Health Connect에 데이터를 동기화해 두면(설정에서 켜기)
 * 가민 공식 API 없이도 당일 바디 컨디션을 가져올 수 있다.
 */
object HealthHelper {

    val permissions: Set<String> = setOf(
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getReadPermission(BodyFatRecord::class),
    )

    fun sdkStatus(context: Context): Int = HealthConnectClient.getSdkStatus(context)

    suspend fun hasPermissions(context: Context): Boolean {
        val client = HealthConnectClient.getOrCreate(context)
        return client.permissionController.getGrantedPermissions().containsAll(permissions)
    }

    /** 대회일 기준 ±3일에서 가장 가까운 몸무게/체지방을 요약 문자열로 반환 */
    suspend fun readBodyInfo(context: Context, raceDate: LocalDate): String? {
        val client = HealthConnectClient.getOrCreate(context)
        val zone = ZoneId.systemDefault()
        val start = raceDate.minusDays(3).atStartOfDay(zone).toInstant()
        val end = raceDate.plusDays(1).atStartOfDay(zone).toInstant()
        val target = raceDate.atTime(9, 0).atZone(zone).toInstant()

        val weight = client.readRecords(
            ReadRecordsRequest(WeightRecord::class, TimeRangeFilter.between(start, end)),
        ).records.minByOrNull { distance(it.time, target) }

        val bodyFat = client.readRecords(
            ReadRecordsRequest(BodyFatRecord::class, TimeRangeFilter.between(start, end)),
        ).records.minByOrNull { distance(it.time, target) }

        if (weight == null && bodyFat == null) return null

        val dateFmt = DateTimeFormatter.ofPattern("MM.dd")
        return buildString {
            weight?.let {
                append("몸무게 ${"%.1f".format(it.weight.inKilograms)}kg")
                append(" (${it.time.atZone(zone).toLocalDate().format(dateFmt)})")
            }
            bodyFat?.let {
                if (isNotEmpty()) append(" · ")
                append("체지방 ${"%.1f".format(it.percentage.value)}%")
            }
        }
    }

    private fun distance(a: Instant, b: Instant): Long =
        abs(Duration.between(a, b).seconds)
}
