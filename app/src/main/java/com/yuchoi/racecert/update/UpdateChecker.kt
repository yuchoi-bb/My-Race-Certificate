package com.yuchoi.racecert.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
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
 * GitHub Releases에서 최신 릴리스를 조회해 앱 자체 업데이트 정보를 가져온다.
 * 태그 형식은 v1.0.<versionCode> 로 CI(build-release.yml)와 약속되어 있다.
 *
 * GitHub의 /releases/latest 포인터는 두 빌드가 거의 동시에 배포되면 순서가
 * 꼬여 더 낮은 버전을 가리킬 수 있다. 그래서 목록을 받아 versionCode가 가장
 * 큰(진짜 최신) 릴리스를 직접 고른다.
 */
object UpdateChecker {

    private const val RELEASES_URL =
        "https://api.github.com/repos/yuchoi-bb/My-Race-Certificate/releases?per_page=30"

    suspend fun fetchLatest(): UpdateInfo? = withContext(Dispatchers.IO) {
        val connection = URL(RELEASES_URL).openConnection() as HttpURLConnection
        try {
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            if (connection.responseCode != 200) return@withContext null

            val releases = JSONArray(connection.inputStream.bufferedReader().use { it.readText() })

            var best: UpdateInfo? = null
            for (i in 0 until releases.length()) {
                val release = releases.getJSONObject(i)
                if (release.optBoolean("draft") || release.optBoolean("prerelease")) continue
                val candidate = parseRelease(release) ?: continue
                if (best == null || candidate.versionCode > best!!.versionCode) {
                    best = candidate
                }
            }
            best
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    /** 릴리스 JSON 하나 → UpdateInfo (태그에서 versionCode 파싱, .apk 자산이 있어야 함) */
    private fun parseRelease(release: JSONObject): UpdateInfo? {
        val tag = release.optString("tag_name")
        val versionCode = tag.substringAfterLast('.').toIntOrNull() ?: return null

        val assets = release.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.optString("name")
            if (name.endsWith(".apk")) {
                return UpdateInfo(
                    versionName = tag.removePrefix("v"),
                    versionCode = versionCode,
                    apkUrl = asset.optString("browser_download_url"),
                    apkName = name,
                    releaseNotes = release.optString("body"),
                )
            }
        }
        return null
    }
}
