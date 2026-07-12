package com.yuchoi.racecert.strava

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Strava 활동을 대회 날짜 기준으로 조회한다. (단일 러닝 / 철인3종 다중 활동) */
object StravaService {

    data class Run(
        val name: String,
        val sportType: String,
        val distanceKm: Double,
        val elapsedSeconds: Int,
        val movingSeconds: Int,
        val startTime: String, // "HH:mm"
        val startEpoch: Long,  // 활동 시작 UTC epoch(sec). 철인3종 합산에 사용
        val date: LocalDate,
        val avgHr: Double?,
        val maxHr: Double?,
        val avgCadenceSpm: Int?,   // 분당 걸음 수 (Strava cadence × 2)
        val elevationGainM: Double?,
        val polyline: String,
        val startLat: Double?,     // 시작 위치 (날씨 조회용)
        val startLng: Double?,
    ) {
        /** 평균 페이스 (mm'ss"/km). 거리·이동시간 기반. */
        val paceLabel: String?
            get() {
                if (distanceKm <= 0 || movingSeconds <= 0) return null
                val secPerKm = (movingSeconds / distanceKm).toInt()
                return "%d'%02d\"/km".format(secPerKm / 60, secPerKm % 60)
            }

        /** 카드/상세에 표시할 상세 요약(있는 항목만). */
        fun metricsSummary(): String = buildString {
            append("${sportEmoji(sportType)} $sportType")
            append(" · 📏 %.2fkm".format(distanceKm))
            paceLabel?.let { append(" · ⏱️ $it") }
            if (avgHr != null) {
                append(" · ❤️ 평균 ${avgHr.toInt()}")
                if (maxHr != null) append("/최대 ${maxHr.toInt()}")
            }
            avgCadenceSpm?.let { append(" · 👟 ${it}spm") }
            elevationGainM?.let { append(" · ⛰️ 고도 ${it.toInt()}m") }
        }
    }

    /**
     * 어디서 막히는지 화면에 숫자 코드로 알린다.
     *  1 = Client Secret 미설정(서버) · 2 = 미연결(로그인 필요)
     *  5 = 그 날짜 활동 없음 · 9 = 통신 오류 · 성공
     */
    sealed interface Result {
        data class Success(val run: Run) : Result
        data object NoSecret : Result
        data object NotConnected : Result
        data object NoActivity : Result
        data object NetworkError : Result
    }

    /** 철인3종 등 다중 활동 조회 결과 */
    sealed interface MultiResult {
        data class Success(val activities: List<Run>) : MultiResult
        data object NoSecret : MultiResult
        data object NotConnected : MultiResult
        data object NoActivity : MultiResult
        data object NetworkError : MultiResult
    }

    /** 대회일의 러닝 1건(가장 긴 것). */
    suspend fun runOnDate(context: Context, date: LocalDate): Result = withContext(Dispatchers.IO) {
        when (val f = fetchActivities(context, date)) {
            Fetch.NoSecret -> Result.NoSecret
            Fetch.NotConnected -> Result.NotConnected
            Fetch.NetworkError -> Result.NetworkError
            is Fetch.Ok -> {
                val best = f.runs
                    .filter { it.sportType.contains("Run", ignoreCase = true) }
                    .maxByOrNull { it.distanceKm }
                if (best == null) Result.NoActivity else Result.Success(best)
            }
        }
    }

    /** 대회일의 모든 활동(수영·바이크·러닝 등)을 시작 시간순으로. */
    suspend fun activitiesOnDate(context: Context, date: LocalDate): MultiResult = withContext(Dispatchers.IO) {
        when (val f = fetchActivities(context, date)) {
            Fetch.NoSecret -> MultiResult.NoSecret
            Fetch.NotConnected -> MultiResult.NotConnected
            Fetch.NetworkError -> MultiResult.NetworkError
            is Fetch.Ok -> {
                val acts = f.runs.sortedBy { it.startEpoch }
                if (acts.isEmpty()) MultiResult.NoActivity else MultiResult.Success(acts)
            }
        }
    }

    /** 철인3종 여러 활동을 한 기록으로 합친 결과 */
    data class TriResult(
        val startTime: String,
        val totalSeconds: Int,
        val distanceLabel: String,
        val info: String,
        val polyline: String,
        val startLat: Double?,
        val startLng: Double?,
    )

    /** 수영/바이크/러닝 활동들을 합산해 하나의 대회 기록으로 매핑한다. */
    fun combineTriathlon(activities: List<Run>): TriResult {
        val sorted = activities.sortedBy { it.startEpoch }
        val first = sorted.first()
        val minStart = sorted.filter { it.startEpoch > 0 }.minOfOrNull { it.startEpoch }
        val maxEnd = sorted.filter { it.startEpoch > 0 }.maxOfOrNull { it.startEpoch + it.elapsedSeconds }
        val total = if (minStart != null && maxEnd != null && maxEnd > minStart) {
            (maxEnd - minStart).toInt()
        } else {
            sorted.sumOf { it.elapsedSeconds }
        }
        val info = sorted.joinToString("\n") { legLine(it) } + "\n🏁 합계 ${formatDuration(total)}"
        val distanceLabel = sorted.joinToString(" / ") { "%.1f".format(it.distanceKm) } + "km"
        // 선택된 모든 종목의 경로를 줄바꿈으로 이어 붙여 전체 지도를 그린다
        val allRoutes = sorted.map { it.polyline }.filter { it.isNotBlank() }.joinToString("\n")
        return TriResult(
            startTime = first.startTime,
            totalSeconds = total,
            distanceLabel = distanceLabel,
            info = info,
            polyline = allRoutes,
            startLat = first.startLat,
            startLng = first.startLng,
        )
    }

    private fun legLine(a: Run): String = buildString {
        append("${sportEmoji(a.sportType)} ${sportLabel(a.sportType)} %.2fkm · ${formatDuration(a.elapsedSeconds)}".format(a.distanceKm))
        a.avgHr?.let { append(" · ❤️ ${it.toInt()}") }
    }

    // ── 내부 조회/파싱 ──

    private sealed interface Fetch {
        data class Ok(val runs: List<Run>) : Fetch
        data object NoSecret : Fetch
        data object NotConnected : Fetch
        data object NetworkError : Fetch
    }

    private suspend fun fetchActivities(context: Context, date: LocalDate): Fetch = withContext(Dispatchers.IO) {
        if (!StravaAuth.hasClientSecret()) return@withContext Fetch.NoSecret
        val token = StravaAuth.validToken(context) ?: return@withContext Fetch.NotConnected
        val zone = ZoneId.systemDefault()
        val after = date.minusDays(1).atStartOfDay(zone).toEpochSecond()
        val before = date.plusDays(2).atStartOfDay(zone).toEpochSecond()
        try {
            val url = "https://www.strava.com/api/v3/athlete/activities" +
                "?after=$after&before=$before&per_page=50"
            val conn = URL(url).openConnection() as HttpURLConnection
            val text = try {
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.connectTimeout = 15_000
                conn.readTimeout = 15_000
                if (conn.responseCode !in 200..299) {
                    conn.errorStream?.use { it.readBytes() }
                    return@withContext Fetch.NetworkError
                }
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }

            val arr = JSONArray(text)
            val runs = ArrayList<Run>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val startLocal = o.optString("start_date_local") // "2024-03-17T08:00:00Z"
                val d = runCatching { LocalDate.parse(startLocal.substring(0, 10)) }.getOrNull() ?: continue
                if (d != date) continue
                runs.add(parseRun(o, startLocal, d))
            }
            Fetch.Ok(runs)
        } catch (_: Exception) {
            Fetch.NetworkError
        }
    }

    private fun parseRun(o: JSONObject, startLocal: String, d: LocalDate): Run {
        fun dbl(key: String): Double? = o.optDouble(key, Double.NaN).takeIf { !it.isNaN() }
        val cadence = dbl("average_cadence")
        val sll = o.optJSONArray("start_latlng")
        val hasStart = sll != null && sll.length() >= 2
        return Run(
            name = o.optString("name"),
            sportType = o.optString("sport_type", o.optString("type")),
            distanceKm = o.optDouble("distance", 0.0) / 1000.0,
            elapsedSeconds = o.optInt("elapsed_time", 0),
            movingSeconds = o.optInt("moving_time", 0),
            startTime = runCatching { startLocal.substring(11, 16) }.getOrNull().orEmpty(),
            startEpoch = runCatching { Instant.parse(o.optString("start_date")).epochSecond }.getOrDefault(0L),
            date = d,
            avgHr = dbl("average_heartrate"),
            maxHr = dbl("max_heartrate"),
            avgCadenceSpm = cadence?.let { (it * 2).toInt() },
            elevationGainM = dbl("total_elevation_gain"),
            polyline = o.optJSONObject("map")?.optString("summary_polyline").orEmpty(),
            startLat = if (hasStart) sll!!.optDouble(0) else null,
            startLng = if (hasStart) sll!!.optDouble(1) else null,
        )
    }

    fun formatDuration(sec: Int): String =
        "%02d:%02d:%02d".format(sec / 3600, (sec % 3600) / 60, sec % 60)

    /** 거리(km) → 대회 거리 라벨 추정 */
    fun distanceLabel(km: Double): String = when {
        km >= 41 -> "풀코스"
        km in 20.0..23.0 -> "하프"
        else -> "%.1fkm".format(km)
    }

    fun sportEmoji(type: String): String = when {
        type.contains("Swim", ignoreCase = true) -> "🏊"
        type.contains("Ride", ignoreCase = true) || type.contains("Bike", ignoreCase = true) ||
            type.contains("Cycl", ignoreCase = true) -> "🚴"
        type.contains("Run", ignoreCase = true) -> "🏃"
        else -> "🏅"
    }

    fun sportLabel(type: String): String = when {
        type.contains("Swim", ignoreCase = true) -> "수영"
        type.contains("Ride", ignoreCase = true) || type.contains("Bike", ignoreCase = true) ||
            type.contains("Cycl", ignoreCase = true) -> "사이클"
        type.contains("Run", ignoreCase = true) -> "러닝"
        else -> type
    }
}
