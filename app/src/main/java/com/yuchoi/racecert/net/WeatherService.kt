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

    /**
     * 선택한 좌표의 대회 날짜 날씨.
     * startTime(HH:mm)과 recordTime(HH:mm:ss)이 있으면 대회 진행 시간대(시작~완주)의
     * 시간별 날씨를 요약하고, 없으면 하루 전체 요약을 돌려준다.
     */
    suspend fun weatherAt(
        place: Place,
        date: LocalDate,
        startTime: String? = null,
        recordTime: String? = null,
    ): Result = withContext(Dispatchers.IO) {
        try {
            val base = if (date.isBefore(LocalDate.now().minusDays(5))) {
                "https://archive-api.open-meteo.com/v1/archive"
            } else {
                "https://api.open-meteo.com/v1/forecast"
            }

            val startHour = parseHour(startTime)
            if (startHour != null) {
                val duration = parseDurationHours(recordTime)
                val endHour = (startHour + (duration ?: 1.0)).coerceAtMost(23.999)
                val hourly = "temperature_2m,precipitation,weather_code,wind_speed_10m"
                val json = JSONObject(
                    httpGet(
                        "$base?latitude=${place.lat}&longitude=${place.lon}" +
                            "&start_date=$date&end_date=$date&hourly=$hourly&timezone=auto",
                    ),
                )
                val raceText = summarizeRaceWindow(json, place, startHour, endHour)
                if (raceText != null) return@withContext Result.Success(raceText)
                // 시간별 실패 시 일 전체로 폴백
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
            val tMax = d.optJSONArray("temperature_2m_max")?.optDouble(0)
            val tMin = d.optJSONArray("temperature_2m_min")?.optDouble(0)
            val rain = d.optJSONArray("precipitation_sum")?.optDouble(0)
            val wind = d.optJSONArray("wind_speed_10m_max")?.optDouble(0)

            val text = buildString {
                append("${place.name} · ${wmoEmoji(codes.optInt(0))}")
                if (tMin != null && !tMin.isNaN() && tMax != null && !tMax.isNaN()) {
                    append(" ${fmt(tMin)}~${fmt(tMax)}°C")
                }
                if (rain != null && !rain.isNaN()) append("  💧${fmt(rain)}mm")
                if (wind != null && !wind.isNaN()) append("  💨${fmt(wind)}km/h")
            }
            Result.Success(text)
        } catch (_: Exception) {
            Result.NetworkError
        }
    }

    /** 대회 진행 시간대(startHour~endHour)의 시간별 날씨 요약 */
    private fun summarizeRaceWindow(
        json: JSONObject,
        place: Place,
        startHour: Double,
        endHour: Double,
    ): String? {
        val hourly = json.optJSONObject("hourly") ?: return null
        val times = hourly.optJSONArray("time") ?: return null
        val temps = hourly.optJSONArray("temperature_2m")
        val precs = hourly.optJSONArray("precipitation")
        val codes = hourly.optJSONArray("weather_code")
        val winds = hourly.optJSONArray("wind_speed_10m")

        val from = kotlin.math.floor(startHour).toInt()
        val to = kotlin.math.ceil(endHour).toInt()
        var tMin = Double.MAX_VALUE
        var tMax = -Double.MAX_VALUE
        var precSum = 0.0
        var windMax = 0.0
        var worstCode = 0
        var count = 0
        for (i in 0 until times.length()) {
            val t = times.optString(i)                 // "yyyy-MM-ddTHH:mm"
            val hour = t.substringAfter('T').substringBefore(':').toIntOrNull() ?: continue
            if (hour < from || hour > to) continue
            count++
            temps?.optDouble(i)?.takeIf { !it.isNaN() }?.let { tMin = minOf(tMin, it); tMax = maxOf(tMax, it) }
            precs?.optDouble(i)?.takeIf { !it.isNaN() }?.let { precSum += it }
            winds?.optDouble(i)?.takeIf { !it.isNaN() }?.let { windMax = maxOf(windMax, it) }
            codes?.optInt(i)?.let { if (it > worstCode) worstCode = it }
        }
        if (count == 0) return null
        return buildString {
            append("${place.name} · ${wmoEmoji(worstCode)}")
            if (tMin != Double.MAX_VALUE) append(" ${fmt(tMin)}~${fmt(tMax)}°C")
            append("  💧${fmt(precSum)}mm")
            if (windMax > 0) append("  💨${fmt(windMax)}km/h")
            append("  🕘${twoDigit(from)}~${twoDigit(to)}시")
        }
    }

    /** "HH:mm" → 시(시작 시각의 정수 시간). 실패 시 null */
    private fun parseHour(startTime: String?): Double? {
        val m = Regex("""(\d{1,2}):(\d{2})""").find(startTime?.trim().orEmpty()) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        if (h !in 0..23 || min !in 0..59) return null
        return h + min / 60.0
    }

    /** "HH:mm:ss" → 시간(소수). 실패 시 null */
    private fun parseDurationHours(recordTime: String?): Double? {
        val m = Regex("""(\d{1,2}):(\d{2}):(\d{2})""").find(recordTime?.trim().orEmpty()) ?: return null
        return m.groupValues[1].toInt() + m.groupValues[2].toInt() / 60.0 + m.groupValues[3].toInt() / 3600.0
    }

    private fun twoDigit(h: Int): String = "%02d".format(h)

    private fun coordKey(lat: Double, lon: Double): String =
        "%.3f,%.3f".format(lat, lon)

    private fun fmt(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else "%.1f".format(v)

    private fun wmoEmoji(code: Int): String = when (code) {
        0 -> "☀️"
        1 -> "🌤️"
        2 -> "⛅"
        3 -> "☁️"
        45, 48 -> "🌫️"
        in 51..57 -> "🌦️"
        in 61..67 -> "🌧️"
        in 71..77 -> "❄️"
        in 80..82 -> "🌦️"
        85, 86 -> "🌨️"
        in 95..99 -> "⛈️"
        else -> "🌡️"
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
