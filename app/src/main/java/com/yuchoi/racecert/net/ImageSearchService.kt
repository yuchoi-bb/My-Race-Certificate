package com.yuchoi.racecert.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 대회명으로 웹 이미지를 검색한다(키 불필요, DuckDuckGo 비공식 엔드포인트).
 * 예정 대회처럼 기록증 사진이 아직 없을 때 대표 이미지를 고르는 용도.
 * 비공식 API라 실패할 수 있으므로 실패 시 빈 목록을 돌려준다.
 */
object ImageSearchService {

    data class WebImage(
        val thumbnailUrl: String,
        val imageUrl: String,
        val title: String,
    )

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"

    suspend fun search(query: String, limit: Int = 5): List<WebImage> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()
        try {
            val vqd = fetchVqd(q) ?: return@withContext emptyList()
            val url = "https://duckduckgo.com/i.js?l=kr-kr&o=json&q=" +
                URLEncoder.encode(q, "UTF-8") + "&vqd=$vqd&f=,,,&p=1"
            val body = httpGet(url, referer = "https://duckduckgo.com/")
            val arr = JSONObject(body).optJSONArray("results") ?: return@withContext emptyList()
            val out = ArrayList<WebImage>()
            var i = 0
            while (i < arr.length() && out.size < limit) {
                val o = arr.getJSONObject(i)
                val thumb = o.optString("thumbnail")
                val image = o.optString("image")
                if (thumb.isNotBlank() && image.isNotBlank()) {
                    out.add(WebImage(thumb, image, o.optString("title")))
                }
                i++
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun fetchVqd(query: String): String? {
        val html = httpGet(
            "https://duckduckgo.com/?q=" + URLEncoder.encode(query, "UTF-8") + "&iax=images&ia=images",
            referer = "https://duckduckgo.com/",
        )
        return Regex("""vqd=["']?([\d-]+)["']?""").find(html)?.groupValues?.get(1)
    }

    private fun httpGet(url: String, referer: String? = null): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 12_000
            connection.readTimeout = 12_000
            connection.setRequestProperty("User-Agent", UA)
            connection.setRequestProperty("Accept", "*/*")
            if (referer != null) connection.setRequestProperty("Referer", referer)
            connection.instanceFollowRedirects = true
            if (connection.responseCode !in 200..299) return ""
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
