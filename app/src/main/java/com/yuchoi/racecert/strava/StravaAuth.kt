package com.yuchoi.racecert.strava

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.yuchoi.racecert.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Strava OAuth2 (Authorization Code). 사용자는 앱의 "Strava 연결" 버튼으로 본인 계정에 로그인·승인하고,
 * 앱은 받은 코드를 토큰으로 바꿔 저장한다. 이후 만료되면 refresh token으로 자동 갱신한다.
 *
 * Client ID는 공개값(코드), Client Secret은 CI에서 GitHub Secret으로 주입(BuildConfig).
 */
object StravaAuth {

    private val CLIENT_ID = BuildConfig.STRAVA_CLIENT_ID
    private val CLIENT_SECRET = BuildConfig.STRAVA_CLIENT_SECRET
    const val REDIRECT_URI = "racecert://localhost"
    private const val SCOPE = "activity:read_all"
    private const val PREFS = "strava"

    /** Client Secret이 빌드에 주입됐는지 (GitHub Secret 설정 여부) */
    fun hasClientSecret(): Boolean = CLIENT_SECRET.isNotBlank()

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isConnected(context: Context): Boolean =
        prefs(context).getString("refresh_token", null) != null

    fun athleteName(context: Context): String? = prefs(context).getString("athlete", null)

    /** 브라우저(또는 Strava 앱)로 로그인·승인 화면을 여는 인텐트 */
    fun authorizeIntent(): Intent {
        val url = "https://www.strava.com/oauth/mobile/authorize" +
            "?client_id=$CLIENT_ID" +
            "&redirect_uri=" + URLEncoder.encode(REDIRECT_URI, "UTF-8") +
            "&response_type=code" +
            "&approval_prompt=auto" +
            "&scope=$SCOPE"
        return Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun disconnect(context: Context) {
        prefs(context).edit().clear().apply()
    }

    /** authorization code → 토큰 저장. 성공 여부 반환. */
    suspend fun exchangeCode(context: Context, code: String): Boolean = withContext(Dispatchers.IO) {
        if (CLIENT_SECRET.isBlank()) return@withContext false
        val body = "client_id=$CLIENT_ID&client_secret=$CLIENT_SECRET" +
            "&code=$code&grant_type=authorization_code"
        val json = postToken(body) ?: return@withContext false
        saveTokens(context, json)
        prefs(context).getString("refresh_token", null) != null
    }

    /** 유효한 access token (만료 임박 시 refresh). 없으면 null. */
    suspend fun validToken(context: Context): String? = withContext(Dispatchers.IO) {
        val p = prefs(context)
        val refresh = p.getString("refresh_token", null) ?: return@withContext null
        val expiresAt = p.getLong("expires_at", 0)
        val now = System.currentTimeMillis() / 1000
        if (now < expiresAt - 60) return@withContext p.getString("access_token", null)
        if (CLIENT_SECRET.isBlank()) return@withContext null
        val body = "client_id=$CLIENT_ID&client_secret=$CLIENT_SECRET" +
            "&grant_type=refresh_token&refresh_token=$refresh"
        val json = postToken(body) ?: return@withContext null
        saveTokens(context, json)
        json.optString("access_token").ifBlank { null }
    }

    private fun saveTokens(context: Context, json: JSONObject) {
        val e = prefs(context).edit()
        json.optString("access_token").takeIf { it.isNotBlank() }?.let { e.putString("access_token", it) }
        json.optString("refresh_token").takeIf { it.isNotBlank() }?.let { e.putString("refresh_token", it) }
        if (json.has("expires_at")) e.putLong("expires_at", json.optLong("expires_at"))
        json.optJSONObject("athlete")?.let { a ->
            val name = listOfNotNull(
                a.optString("firstname").ifBlank { null },
                a.optString("lastname").ifBlank { null },
            ).joinToString(" ")
            if (name.isNotBlank()) e.putString("athlete", name)
        }
        e.apply()
    }

    private fun postToken(body: String): JSONObject? {
        val conn = URL("https://www.strava.com/oauth/token").openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.outputStream.use { it.write(body.toByteArray()) }
            if (conn.responseCode !in 200..299) {
                conn.errorStream?.use { it.readBytes() }
                return null
            }
            JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }
}
