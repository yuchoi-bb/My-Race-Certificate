package com.yuchoi.racecert.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

/**
 * 헬스커넥트에서 대회일 무렵의 몸무게·체지방을 읽어온다.
 *
 * 가민커넥트/삼성헬스 등이 헬스커넥트로 보내둔 데이터를 우리 앱이 읽는 구조다.
 * (다른 앱의 데이터를 직접 읽는 공식 통로는 없고, 헬스커넥트가 표준 다리 역할)
 *
 * 어디서 막히는지 눈으로 확인할 수 있도록 각 단계에 숫자 코드를 둔다.
 */
object HealthConnectBody {

    /** 몸무게·체지방 읽기 권한 묶음 */
    val PERMISSIONS: Set<String> = setOf(
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getReadPermission(BodyFatRecord::class),
    )

    /**
     * 헬스커넥트 사용 가능 상태.
     *  1 = 이 기기에서 헬스커넥트 사용 불가(미지원)
     *  2 = 헬스커넥트 앱 설치/업데이트 필요
     *  3 = 사용 가능
     */
    fun availability(context: Context): Int = when (HealthConnectClient.getSdkStatus(context)) {
        HealthConnectClient.SDK_UNAVAILABLE -> 1
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> 2
        else -> 3
    }

    fun client(context: Context): HealthConnectClient = HealthConnectClient.getOrCreate(context)

    /** 필요한 읽기 권한이 모두 허용됐는지 */
    suspend fun hasPermissions(context: Context): Boolean =
        client(context).permissionController.getGrantedPermissions().containsAll(PERMISSIONS)

    /** 읽어온 몸 상태 값 */
    data class BodyReading(
        val weightKg: Double?,
        val bodyFatPct: Double?,
        val date: LocalDate?,
    ) {
        val isEmpty: Boolean get() = weightKg == null && bodyFatPct == null
    }

    /**
     * 대회일 ±[windowDays]일 범위에서 대회일에 가장 가까운 몸무게/체지방을 읽어온다.
     */
    suspend fun readNear(context: Context, raceDate: LocalDate, windowDays: Long = 14): BodyReading {
        val zone = ZoneId.systemDefault()
        val start = raceDate.minusDays(windowDays).atStartOfDay(zone).toInstant()
        val end = raceDate.plusDays(windowDays + 1).atStartOfDay(zone).toInstant()
        val filter = TimeRangeFilter.between(start, end)
        val c = client(context)

        val weights = c.readRecords(ReadRecordsRequest(WeightRecord::class, filter)).records
        val fats = c.readRecords(ReadRecordsRequest(BodyFatRecord::class, filter)).records

        fun daysFrom(epochDay: Long) = abs(epochDay - raceDate.toEpochDay())

        val w = weights.minByOrNull { daysFrom(it.time.atZone(zone).toLocalDate().toEpochDay()) }
        val f = fats.minByOrNull { daysFrom(it.time.atZone(zone).toLocalDate().toEpochDay()) }

        return BodyReading(
            weightKg = w?.weight?.inKilograms,
            bodyFatPct = f?.percentage?.value,
            date = w?.time?.atZone(zone)?.toLocalDate() ?: f?.time?.atZone(zone)?.toLocalDate(),
        )
    }
}
