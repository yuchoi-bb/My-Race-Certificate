package com.yuchoi.racecert.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val versionName: String,
    val versionCode: Int,
    val apkUrl: String,
    val apkName: String,
    val releaseNotes: String,
)

/**
 * GitHub Releases의 최신 릴리스를 조회해 앱 자체 업데이트 정보를 가져온다.
 * 태그 형식은 v1.0.<versionCode> 로 CI(build-release.yml)와 약속되어 있다.
 */
object UpdateChecker {

    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/yuchoi-bb/My-Race-Certificate/releases/latest"

    suspend fun fetchLatest(): UpdateInfo? = withContext(Dispatchers.IO) {
        val connection = URL(LATEST_RELEASE_URL).openConnection() as HttpURLConnection
        try {
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            if (connection.responseCode != 200) return@withContext null

            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val tag = json.optString("tag_name")
            val versionCode = tag.substringAfterLast('.').toIntOrNull() ?: return@withContext null

            val assets = json.optJSONArray("assets") ?: return@withContext null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name")
                if (name.endsWith(".apk")) {
                    return@withContext UpdateInfo(
                        versionName = tag.removePrefix("v"),
                        versionCode = versionCode,
                        apkUrl = asset.optString("browser_download_url"),
                        apkName = name,
                        releaseNotes = json.optString("body"),
                    )
                }
            }
            null
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }
}
