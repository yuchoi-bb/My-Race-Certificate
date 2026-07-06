package com.yuchoi.racecert.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate

/**
 * Open-Meteo(무료, API 키 불필요)로 대회 장소+날짜의 날씨를 가져온다.
 * - 지오코딩: 장소 이름 → 위경도
 * - 과거 날짜는 archive API, 최근/미래(예보 범위)는 forecast API 사용
 */
object WeatherService {

    suspend fun fetch(place: String, date: LocalDate): String? = withContext(Dispatchers.IO) {
        val geo = getJson(
            "https://geocoding-api.open-meteo.com/v1/search?name=" +
                URLEncoder.encode(place, "UTF-8") + "&count=1&language=ko&format=json",
        ) ?: return@withContext null
        val results = geo.optJSONArray("results") ?: return@withContext null
        if (results.length() == 0) return@withContext null
        val loc = results.getJSONObject(0)
        val lat = loc.optDouble("latitude")
        val lon = loc.optDouble("longitude")
        val resolvedName = loc.optString("name", place)

        // archive는 며칠 지연이 있으므로 최근 5일 이내/미래는 forecast API로
        val base = if (date.isBefore(LocalDate.now().minusDays(5))) {
            "https://archive-api.open-meteo.com/v1/archive"
        } else {
            "https://api.open-meteo.com/v1/forecast"
        }
        val daily = "weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum,wind_speed_10m_max"
        val weatherJson = getJson(
            "$base?latitude=$lat&longitude=$lon&start_date=$date&end_date=$date" +
                "&daily=$daily&timezone=auto",
        ) ?: return@withContext null
        val d = weatherJson.optJSONObject("daily") ?: return@withContext null
        val codes = d.optJSONArray("weather_code") ?: return@withContext null
        if (codes.length() == 0 || codes.isNull(0)) return@withContext null

        val desc = wmoDescription(codes.optInt(0))
        val tMax = d.optJSONArray("temperature_2m_max")?.optDouble(0)
        val tMin = d.optJSONArray("temperature_2m_min")?.optDouble(0)
        val rain = d.optJSONArray("precipitation_sum")?.optDouble(0)
        val wind = d.optJSONArray("wind_speed_10m_max")?.optDouble(0)

        buildString {
            append("$resolvedName · $desc")
            if (tMin != null && !tMin.isNaN() && tMax != null && !tMax.isNaN()) {
                append(", ${fmt(tMin)}~${fmt(tMax)}°C")
            }
            if (rain != null && !rain.isNaN()) append(", 강수 ${fmt(rain)}mm")
            if (wind != null && !wind.isNaN()) append(", 바람 ${fmt(wind)}km/h")
        }
    }

    private fun fmt(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else "%.1f".format(v)

    private fun wmoDescription(code: Int): String = when (code) {
        0 -> "맑음"
        1 -> "대체로 맑음"
        2 -> "구름 조금"
        3 -> "흐림"
        45, 48 -> "안개"
        in 51..57 -> "이슬비"
        in 61..67 -> "비"
        in 71..77 -> "눈"
        in 80..82 -> "소나기"
        85, 86 -> "소낙눈"
        in 95..99 -> "뇌우"
        else -> "날씨 정보"
    }

    private fun getJson(url: String): JSONObject? {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            if (connection.responseCode != 200) return null
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }
}
