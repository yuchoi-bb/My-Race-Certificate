package com.yuchoi.racecert.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.reflect.KClass

/**
 * 헬스커넥트에서 대회일 무렵의 몸 상태(몸무게·체지방·제지방량·기초대사량)를 읽어온다.
 *
 * InBody/가민커넥트/삼성헬스 등이 헬스커넥트로 보내둔 데이터를 우리 앱이 읽는 구조다.
 * (다른 앱의 데이터를 직접 읽는 공식 통로는 없고, 헬스커넥트가 표준 다리 역할)
 *
 * 어디서 막히는지 눈으로 확인할 수 있도록 각 단계에 숫자 코드를 둔다.
 */
object HealthConnectBody {

    /** 몸 상태 읽기 권한 묶음 */
    val PERMISSIONS: Set<String> = setOf(
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getReadPermission(BodyFatRecord::class),
        HealthPermission.getReadPermission(LeanBodyMassRecord::class),
        HealthPermission.getReadPermission(BasalMetabolicRateRecord::class),
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
        val leanKg: Double?,        // 제지방량(근육 관련)
        val bmrKcal: Double?,       // 기초대사량 (kcal/day)
        val date: LocalDate?,
    ) {
        val isEmpty: Boolean
            get() = weightKg == null && bodyFatPct == null && leanKg == null && bmrKcal == null
    }

    /** 그래프용 한 점 (측정 시각 · 값) */
    data class Point(val timeMs: Long, val value: Double)

    /** 기간 내 몸 상태 이력 (지표별 시계열) */
    data class BodyHistory(
        val weight: List<Point>,
        val bodyFat: List<Point>,
        val lean: List<Point>,
        val bmr: List<Point>,
    ) {
        val isEmpty: Boolean
            get() = weight.isEmpty() && bodyFat.isEmpty() && lean.isEmpty() && bmr.isEmpty()
    }

    /** 최근 [sinceDays]일간의 몸 상태 이력을 지표별 시계열로 읽어온다. (페이징으로 전체 조회) */
    suspend fun readHistory(context: Context, sinceDays: Long = 180): BodyHistory {
        val end = Instant.now()
        val start = end.minus(java.time.Duration.ofDays(sinceDays))
        val filter = TimeRangeFilter.between(start, end)
        val c = client(context)

        return BodyHistory(
            weight = readSeries(c, WeightRecord::class, filter) { it.time.toEpochMilli() to it.weight.inKilograms },
            bodyFat = readSeries(c, BodyFatRecord::class, filter) { it.time.toEpochMilli() to it.percentage.value },
            lean = readSeries(c, LeanBodyMassRecord::class, filter) { it.time.toEpochMilli() to it.mass.inKilograms },
            bmr = readSeries(c, BasalMetabolicRateRecord::class, filter) {
                it.time.toEpochMilli() to it.basalMetabolicRate.inKilocaloriesPerDay
            },
        )
    }

    /** 한 종류의 레코드를 페이지 끝까지 모두 읽어 (시각·값) 시계열로 만든다. */
    private suspend fun <T : Record> readSeries(
        c: HealthConnectClient,
        type: KClass<T>,
        filter: TimeRangeFilter,
        sel: (T) -> Pair<Long, Double>,
    ): List<Point> {
        val out = ArrayList<Point>()
        var token: String? = null
        do {
            val resp = c.readRecords(
                ReadRecordsRequest(recordType = type, timeRangeFilter = filter, pageToken = token),
            )
            resp.records.forEach { val (t, v) = sel(it); out.add(Point(t, v)) }
            token = resp.pageToken
        } while (token != null)
        return out.sortedBy { it.timeMs }
    }

    /**
     * 대회 전 ~ 대회일까지 범위에서 대회일에 가장 가까운 몸 상태를 읽어온다.
     * (대회를 마친 이후 측정값은 제외)
     */
    suspend fun readNear(context: Context, raceDate: LocalDate, windowDays: Long = 14): BodyReading {
        val zone = ZoneId.systemDefault()
        val start = raceDate.minusDays(windowDays).atStartOfDay(zone).toInstant()
        val end = raceDate.plusDays(1).atStartOfDay(zone).toInstant()
        val filter = TimeRangeFilter.between(start, end)
        val c = client(context)

        fun nearestDate(t: Instant) = abs(t.atZone(zone).toLocalDate().toEpochDay() - raceDate.toEpochDay())

        val w = c.readRecords(ReadRecordsRequest(WeightRecord::class, filter)).records
            .minByOrNull { nearestDate(it.time) }
        val f = c.readRecords(ReadRecordsRequest(BodyFatRecord::class, filter)).records
            .minByOrNull { nearestDate(it.time) }
        val lean = c.readRecords(ReadRecordsRequest(LeanBodyMassRecord::class, filter)).records
            .minByOrNull { nearestDate(it.time) }
        val bmr = c.readRecords(ReadRecordsRequest(BasalMetabolicRateRecord::class, filter)).records
            .minByOrNull { nearestDate(it.time) }

        val date = listOfNotNull(w?.time, f?.time, lean?.time, bmr?.time)
            .minByOrNull { nearestDate(it) }
            ?.atZone(zone)?.toLocalDate()

        return BodyReading(
            weightKg = w?.weight?.inKilograms,
            bodyFatPct = f?.percentage?.value,
            leanKg = lean?.mass?.inKilograms,
            bmrKcal = bmr?.basalMetabolicRate?.inKilocaloriesPerDay,
            date = date,
        )
    }
}
