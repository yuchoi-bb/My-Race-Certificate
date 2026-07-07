package com.yuchoi.racecert.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate

/**
 * 대회 장소 후보 검색(지오코딩) + 좌표별 날씨 조회.
 * - 지오코딩: Nominatim(OSM) + Open-Meteo. 도로/철도 등은 빼고 도시·행정지역을 우선.
 *   여러 후보를 돌려주므로 사용자가 원하는 지역을 고를 수 있다.
 * - 날씨: Open-Meteo (무료·키 불필요). 과거는 archive, 최근/미래는 forecast.
 */
object WeatherService {

    /** 선택 가능한 장소 후보 */
    data class Place(
        val name: String,        // 짧은 이름 (예: 용인시)
        val displayName: String, // 전체 이름 (예: 용인시, 경기도, 대한민국)
        val lat: Double,
        val lon: Double,
    )

    sealed interface Result {
        data class Success(val text: String) : Result
        data object NoWeatherData : Result
        data object NetworkError : Result
    }

    /** 장소 이름 → 후보 목록(최대 8개). 도시·행정지역 우선, 도로/철도 등은 제외. */
    suspend fun searchPlaces(query: String): List<Place> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()
        val out = LinkedHashMap<String, Place>()

        // 1) Nominatim(OSM) — 한글 지명·장소명에 강함
        runCatching {
            val body = httpGet(
                "https://nominatim.openstreetmap.org/search?q=" +
                    URLEncoder.encode(q, "UTF-8") +
                    "&format=jsonv2&limit=12&accept-language=ko",
            )
            val arr = JSONArray(body)
            val items = (0 until arr.length()).map { arr.getJSONObject(it) }
            // 도로/철도/수로/경로는 제외 (도시·행정경계·지명 우선)
            val roadClasses = setOf("highway", "railway", "waterway", "route", "aeroway")
            val filtered = items.filter { it.optString("class") !in roadClasses }
            for (o in filtered.ifEmpty { items }) {
                val lat = o.optString("lat").toDoubleOrNull() ?: continue
                val lon = o.optString("lon").toDoubleOrNull() ?: continue
                val name = o.optString("name").ifBlank {
                    o.optString("display_name").substringBefore(',')
                }
                val disp = o.optString("display_name").ifBlank { name }
                out.putIfAbsent(coordKey(lat, lon), Place(name, disp, lat, lon))
            }
        }

        // 2) Open-Meteo 지오코딩 — 도시 위주라 보조 후보로 추가
        runCatching {
            val body = httpGet(
                "https://geocoding-api.open-meteo.com/v1/search?name=" +
                    URLEncoder.encode(q, "UTF-8") + "&count=5&language=ko&format=json",
            )
            val results = JSONObject(body).optJSONArray("results")
            if (results != null) {
                for (i in 0 until results.length()) {
                    val r = results.getJSONObject(i)
                    val lat = r.optDouble("latitude")
                    val lon = r.optDouble("longitude")
                    if (lat.isNaN() || lon.isNaN()) continue
                    val name = r.optString("name")
                    val admin = listOfNotNull(
                        r.optString("admin1").ifBlank { null },
                        r.optString("country").ifBlank { null },
                    ).joinToString(", ")
                    val disp = if (admin.isBlank()) name else "$name, $admin"
                    out.putIfAbsent(coordKey(lat, lon), Place(name, disp, lat, lon))
                }
            }
        }

        out.values.take(8).toList()
    }

    /** 선택한 좌표의 대회 날짜 날씨 */
    suspend fun weatherAt(place: Place, date: LocalDate): Result = withContext(Dispatchers.IO) {
        try {
            val base = if (date.isBefore(LocalDate.now().minusDays(5))) {
                "https://archive-api.open-meteo.com/v1/archive"
            } else {
                "https://api.open-meteo.com/v1/forecast"
            }
            val daily = "weather_code,temperature_2m_max,temperature_2m_min," +
                "precipitation_sum,wind_speed_10m_max"
            val weatherJson = JSONObject(
                httpGet(
                    "$base?latitude=${place.lat}&longitude=${place.lon}" +
                        "&start_date=$date&end_date=$date&daily=$daily&timezone=auto",
                ),
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
                append("${place.name} · $desc")
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

    private fun coordKey(lat: Double, lon: Double): String =
        "%.3f,%.3f".format(lat, lon)

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

    private fun httpGet(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 12_000
            connection.readTimeout = 12_000
            connection.setRequestProperty("User-Agent", "MyRaceCertificate-Android (race cert app)")
            connection.instanceFollowRedirects = true
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException("HTTP $code")
            return body
        } finally {
            connection.disconnect()
        }
    }
}
