package com.yuchoi.racecert.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate

/**
 * Open-Meteo(무료, API 키 불필요)로 대회 장소+날짜의 날씨를 가져온다.
 * 실패 원인을 구분해 돌려주므로 UI에서 사용자에게 정확히 안내할 수 있다.
 */
object WeatherService {

    sealed interface Result {
        data class Success(val text: String) : Result
        /** 장소 이름으로 좌표를 못 찾음 */
        data object PlaceNotFound : Result
        /** 좌표는 찾았으나 해당 날짜 날씨 데이터가 없음 */
        data object NoWeatherData : Result
        /** 네트워크/서버 오류 */
        data object NetworkError : Result
    }

    private data class GeoPoint(val lat: Double, val lon: Double, val name: String)

    suspend fun fetch(place: String, date: LocalDate): Result = withContext(Dispatchers.IO) {
        try {
            val point = resolveLocation(place) ?: return@withContext Result.PlaceNotFound

            // archive는 며칠 지연이 있으므로 최근 5일 이내/미래는 forecast API로
            val base = if (date.isBefore(LocalDate.now().minusDays(5))) {
                "https://archive-api.open-meteo.com/v1/archive"
            } else {
                "https://api.open-meteo.com/v1/forecast"
            }
            val daily = "weather_code,temperature_2m_max,temperature_2m_min," +
                "precipitation_sum,wind_speed_10m_max"
            val weatherJson = getJson(
                "$base?latitude=${point.lat}&longitude=${point.lon}" +
                    "&start_date=$date&end_date=$date&daily=$daily&timezone=auto",
            )
            val d = weatherJson.optJSONObject("daily") ?: return@withContext Result.NoWeatherData
            val codes = d.optJSONArray("weather_code")
            if (codes == null || codes.length() == 0 || codes.isNull(0)) {
                return@withContext Result.NoWeatherData
            }

            val desc = wmoDescription(codes.optInt(0))
            val tMax = d.optJSONArray("temperature_2m_max")?.optDouble(0)
            val tMin = d.optJSONArray("temperature_2m_min")?.optDouble(0)
            val rain = d.optJSONArray("precipitation_sum")?.optDouble(0)
            val wind = d.optJSONArray("wind_speed_10m_max")?.optDouble(0)

            val text = buildString {
                append("${point.name} · $desc")
                if (tMin != null && !tMin.isNaN() && tMax != null && !tMax.isNaN()) {
                    append(", ${fmt(tMin)}~${fmt(tMax)}°C")
                }
                if (rain != null && !rain.isNaN()) append(", 강수 ${fmt(rain)}mm")
                if (wind != null && !wind.isNaN()) append(", 바람 ${fmt(wind)}km/h")
            }
            Result.Success(text)
        } catch (_: Exception) {
            Result.NetworkError
        }
    }

    /**
     * 장소 이름 → 좌표. 한국 지명에 맞춰 여러 후보를 시도한다:
     * 원문 → 행정/장소 접미사 제거본 순으로 조회하고, 한국(KR) 결과를 우선한다.
     */
    private fun resolveLocation(place: String): GeoPoint? {
        val candidates = buildList {
            add(place.trim())
            // "수원종합운동장" → "수원", "화성시" → "화성" 등 접미사 제거
            val stripped = place.trim()
                .replace(Regex("(특례시|광역시|특별자치시|특별시|시|군|구|읍|면|동|종합운동장|경기장|스타디움|월드컵경기장)$"), "")
                .trim()
            if (stripped.isNotEmpty() && stripped != place.trim()) add(stripped)
            // 공백이 있으면 첫 단어만 (예: "경기 수원" → "수원"은 아님; "수원 종합운동장" → "수원")
            val firstWord = place.trim().substringBefore(' ').trim()
            if (firstWord.isNotEmpty()) add(firstWord)
        }.distinct().filter { it.isNotEmpty() }

        for (name in candidates) {
            val geo = runCatching {
                getJson(
                    "https://geocoding-api.open-meteo.com/v1/search?name=" +
                        URLEncoder.encode(name, "UTF-8") + "&count=10&language=ko&format=json",
                )
            }.getOrNull() ?: continue
            val results = geo.optJSONArray("results") ?: continue
            if (results.length() == 0) continue
            // 한국(KR) 결과를 우선, 없으면 첫 번째
            var chosen = results.getJSONObject(0)
            for (i in 0 until results.length()) {
                val r = results.getJSONObject(i)
                if (r.optString("country_code") == "KR") {
                    chosen = r
                    break
                }
            }
            return GeoPoint(
                lat = chosen.optDouble("latitude"),
                lon = chosen.optDouble("longitude"),
                name = chosen.optString("name", name),
            )
        }
        return null
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

    /** HTTP GET → JSON. 비정상 응답/네트워크 실패는 예외로 던진다. */
    private fun getJson(url: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 12_000
            connection.readTimeout = 12_000
            connection.setRequestProperty("User-Agent", "MyRaceCertificate-Android")
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException("HTTP $code")
            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }
}
