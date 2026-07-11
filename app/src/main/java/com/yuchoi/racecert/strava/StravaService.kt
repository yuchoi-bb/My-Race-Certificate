package com.yuchoi.racecert.strava

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.ZoneId

/** Strava 활동(러닝)을 대회 날짜 기준으로 조회한다. */
object StravaService {

    data class Run(
        val name: String,
        val distanceKm: Double,
        val elapsedSeconds: Int,
        val movingSeconds: Int,
        val startTime: String, // "HH:mm"
        val date: LocalDate,
    )

    /**
     * 어디서 막히는지 화면에 숫자 코드로 알린다.
     *  1 = Client Secret 미설정(서버) · 2 = 미연결(로그인 필요)
     *  5 = 그 날짜 러닝 없음 · 9 = 통신 오류 · 성공
     */
    sealed interface Result {
        data class Success(val run: Run) : Result
        data object NoSecret : Result
        data object NotConnected : Result
        data object NoActivity : Result
        data object NetworkError : Result
    }

    suspend fun runOnDate(context: Context, date: LocalDate): Result = withContext(Dispatchers.IO) {
        if (!StravaAuth.hasClientSecret()) return@withContext Result.NoSecret
        val token = StravaAuth.validToken(context) ?: return@withContext Result.NotConnected
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
                    return@withContext Result.NetworkError
                }
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }

            val arr = JSONArray(text)
            var best: Run? = null
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val type = o.optString("sport_type", o.optString("type"))
                if (!type.contains("Run", ignoreCase = true)) continue
                val startLocal = o.optString("start_date_local") // "2024-03-17T08:00:00Z"
                val d = runCatching { LocalDate.parse(startLocal.substring(0, 10)) }.getOrNull() ?: continue
                if (d != date) continue
                val run = Run(
                    name = o.optString("name"),
                    distanceKm = o.optDouble("distance", 0.0) / 1000.0,
                    elapsedSeconds = o.optInt("elapsed_time", 0),
                    movingSeconds = o.optInt("moving_time", 0),
                    startTime = runCatching { startLocal.substring(11, 16) }.getOrNull().orEmpty(),
                    date = d,
                )
                // 같은 날 여러 활동이면 가장 긴(대회일 가능성 높은) 것
                if (best == null || run.distanceKm > best.distanceKm) best = run
            }
            if (best == null) Result.NoActivity else Result.Success(best)
        } catch (_: Exception) {
            Result.NetworkError
        }
    }

    fun formatDuration(sec: Int): String =
        "%02d:%02d:%02d".format(sec / 3600, (sec % 3600) / 60, sec % 60)

    /** 거리(km) → 대회 거리 라벨 추정 */
    fun distanceLabel(km: Double): String = when {
        km >= 41 -> "풀코스"
        km in 20.0..23.0 -> "하프"
        else -> "%.1fkm".format(km)
    }
}
