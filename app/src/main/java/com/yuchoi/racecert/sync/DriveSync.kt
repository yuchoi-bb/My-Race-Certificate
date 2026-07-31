package com.yuchoi.racecert.sync

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.yuchoi.racecert.R
import com.yuchoi.racecert.data.BackupManager
import com.yuchoi.racecert.data.RecordStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private const val TAG = "Drive"

/**
 * Google Drive 앱 전용 폴더(appDataFolder)에 백업 zip 한 개를 두고,
 * 같은 Google 계정을 쓰는 여러 기기 간에 마지막 변경 우선(last-writer-wins)으로 동기화한다.
 *
 * OAuth 클라이언트(패키지명 + SHA-1)는 Google Cloud Console에 1회 등록되어 있어야 한다.
 */
object DriveSync {

    private const val DRIVE_APPDATA = "https://www.googleapis.com/auth/drive.appdata"
    private const val BACKUP_NAME = "race-backup.zip"
    private const val PROP_UPDATED_AT = "updatedAt"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private fun log(msg: String) = SyncDebugLog.log(TAG, msg)
    private fun logError(msg: String, t: Throwable) = SyncDebugLog.logError(TAG, msg, t)

    fun signInOptions(context: Context): GoogleSignInOptions =
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            // Firestore(FirebaseAuth) 로그인에도 같은 버튼을 쓰기 위한 ID 토큰
            .requestIdToken(context.getString(R.string.default_web_client_id))
            .requestScopes(Scope(DRIVE_APPDATA))
            .build()

    fun client(context: Context): GoogleSignInClient =
        GoogleSignIn.getClient(context, signInOptions(context))

    fun lastAccount(context: Context): GoogleSignInAccount? =
        GoogleSignIn.getLastSignedInAccount(context)

    fun accountEmail(context: Context): String? = lastAccount(context)?.email

    fun accountFromIntent(data: Intent?): GoogleSignInAccount? =
        runCatching { GoogleSignIn.getSignedInAccountFromIntent(data).result }.getOrNull()

    /** 이미 로그인돼 있으면 화면 없이 최신 ID 토큰을 다시 받아온다(앱 시작 시 Firestore 인증용). */
    fun silentSignIn(context: Context, onResult: (GoogleSignInAccount?) -> Unit) {
        client(context).silentSignIn()
            .addOnSuccessListener { onResult(it) }
            .addOnFailureListener { onResult(null) }
    }

    fun signOut(context: Context, onDone: () -> Unit) {
        FirestoreSync.signOut()
        client(context).signOut().addOnCompleteListener { onDone() }
    }

    /** 로그인 직후/앱 시작/저장 후 호출. 백그라운드에서 동기화하고 결과를 콜백으로 알린다. */
    fun requestSync(context: Context, onResult: ((SyncResult) -> Unit)? = null) {
        val appContext = context.applicationContext
        log("requestSync() 호출됨")
        FirestoreSync.requestSync(appContext)
        val startedAt = System.currentTimeMillis()
        scope.launch {
            val result = mutex.withLock { runSync(appContext) }
            log("requestSync() 완료: $result (총 소요 ${System.currentTimeMillis() - startedAt}ms)")
            onResult?.let { cb -> withContext(Dispatchers.Main) { cb(result) } }
        }
    }

    enum class SyncResult { UPLOADED, DOWNLOADED, IN_SYNC, NOT_SIGNED_IN, ERROR }

    /** 마지막 ERROR의 구체적인 원인(화면에 보여줄 사람이 읽을 수 있는 문장). */
    var lastError: String? = null
        private set

    /** 업로드·다운로드처럼 네트워크에 크게 의존하는 작업을 일시적인 연결 오류 시 재시도한다. */
    private suspend fun <T> retryIO(times: Int = 3, initialDelayMs: Long = 1500, block: suspend () -> T): T {
        var delayMs = initialDelayMs
        repeat(times - 1) { attempt ->
            val t0 = System.currentTimeMillis()
            try {
                return block()
            } catch (e: java.io.IOException) {
                log("재시도 대상 IO 오류 (시도 ${attempt + 1}/${times}, ${System.currentTimeMillis() - t0}ms): ${e.message}")
                kotlinx.coroutines.delay(delayMs)
                delayMs *= 2
            }
        }
        return block()
    }

    private suspend fun runSync(context: Context): SyncResult {
        val syncStart = System.currentTimeMillis()
        log("runSync() 시작")
        val account = lastAccount(context)?.account ?: run {
            log("runSync(): 로그인된 계정 없음 (NOT_SIGNED_IN)")
            return SyncResult.NOT_SIGNED_IN
        }
        return try {
            var t0 = System.currentTimeMillis()
            log("GoogleAuthUtil.getToken 요청 시작 (최대 30초 대기)")
            val token = try {
                withTimeout(30_000) {
                    withContext(Dispatchers.IO) { GoogleAuthUtil.getToken(context, account, "oauth2:$DRIVE_APPDATA") }
                }
            } catch (e: TimeoutCancellationException) {
                log("GoogleAuthUtil.getToken 30초 초과로 타임아웃 (${System.currentTimeMillis() - t0}ms 경과)")
                throw java.io.IOException("토큰 요청이 30초 넘게 응답이 없었어요", e)
            }
            log("GoogleAuthUtil.getToken 완료 (${System.currentTimeMillis() - t0}ms)")

            t0 = System.currentTimeMillis()
            val remote = findBackup(token)
            log("findBackup 완료 (${System.currentTimeMillis() - t0}ms) remote=${if (remote == null) "없음" else "id=${remote.id} updatedAt=${remote.updatedAt}"}")
            val localAt = RecordStore.localUpdatedAt()
            log("localUpdatedAt=$localAt")
            val result = when {
                remote == null -> {
                    log("원격 백업 없음 → 업로드 시작")
                    t0 = System.currentTimeMillis()
                    retryIO { uploadBackup(context, token, existingId = null, updatedAt = localAt) }
                    log("업로드 완료 (${System.currentTimeMillis() - t0}ms)")
                    SyncResult.UPLOADED
                }
                remote.updatedAt > localAt -> {
                    log("원격이 더 최신 → 다운로드 시작")
                    t0 = System.currentTimeMillis()
                    val file = retryIO { downloadBackup(context, token, remote.id) }
                    log("다운로드 완료 (${System.currentTimeMillis() - t0}ms, ${file.length()}바이트)")
                    try {
                        t0 = System.currentTimeMillis()
                        file.inputStream().use { BackupManager.importStream(context, it) }
                        log("복원(importStream) 완료 (${System.currentTimeMillis() - t0}ms)")
                    } finally {
                        file.delete()
                    }
                    RecordStore.setLocalUpdatedAt(remote.updatedAt)
                    SyncResult.DOWNLOADED
                }
                localAt > remote.updatedAt -> {
                    log("로컬이 더 최신 → 업로드 시작")
                    t0 = System.currentTimeMillis()
                    retryIO { uploadBackup(context, token, existingId = remote.id, updatedAt = localAt) }
                    log("업로드 완료 (${System.currentTimeMillis() - t0}ms)")
                    SyncResult.UPLOADED
                }
                else -> {
                    log("이미 최신 상태")
                    SyncResult.IN_SYNC
                }
            }
            lastError = null
            log("runSync() 성공 종료: $result (전체 ${System.currentTimeMillis() - syncStart}ms)")
            result
        } catch (e: UserRecoverableAuthException) {
            // Drive 권한(scope) 재동의가 필요한 상태. 동의 화면을 새 태스크로 띄워
            // 사용자가 한 번 눌러 승인하면 다음 동기화부터는 정상 진행된다.
            lastError = "Google 계정 권한을 다시 승인해야 해요. 동의 화면을 띄웠어요 — 승인 후 다시 동기화해 주세요."
            logError("UserRecoverableAuthException, 동의 화면 실행 시도 (전체 ${System.currentTimeMillis() - syncStart}ms)", e)
            runCatching {
                e.intent?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }?.let { context.startActivity(it) }
                log("동의 화면 Intent 실행 요청 완료")
            }.onFailure { logError("동의 화면 Intent 실행 실패", it) }
            SyncResult.ERROR
        } catch (e: java.io.IOException) {
            // 3번 재시도 후에도 실패한 네트워크 문제. 대개 연결이 불안정해서 생긴다.
            lastError = "네트워크 연결이 불안정해서 실패했어요 (${e.javaClass.simpleName}: ${e.message ?: "메시지 없음"}). " +
                "와이파이가 안정적인 곳에서 다시 시도해 주세요."
            logError("IOException (전체 ${System.currentTimeMillis() - syncStart}ms)", e)
            SyncResult.ERROR
        } catch (e: Exception) {
            lastError = "${e.javaClass.simpleName}: ${e.message ?: "(메시지 없음)"}"
            logError("예상 못한 예외 (전체 ${System.currentTimeMillis() - syncStart}ms)", e)
            SyncResult.ERROR
        }
    }

    private data class RemoteBackup(val id: String, val updatedAt: Long)

    private fun findBackup(token: String): RemoteBackup? {
        val q = URLEncoder.encode("name='$BACKUP_NAME'", "UTF-8")
        val url = "https://www.googleapis.com/drive/v3/files?spaces=appDataFolder" +
            "&q=$q&fields=" + URLEncoder.encode("files(id,appProperties)", "UTF-8")
        val body = httpGet(url, token) ?: return null
        val files = JSONObject(body).optJSONArray("files") ?: return null
        if (files.length() == 0) return null
        val f = files.getJSONObject(0)
        val id = f.optString("id")
        val updatedAt = f.optJSONObject("appProperties")
            ?.optString(PROP_UPDATED_AT)?.toLongOrNull() ?: 0L
        return RemoteBackup(id, updatedAt)
    }

    private suspend fun uploadBackup(context: Context, token: String, existingId: String?, updatedAt: Long) {
        // 이미지가 쌓이면 zip이 수백MB가 될 수 있어, 전체를 메모리에 올리지 않고
        // 임시 파일에서 곧바로 요청 본문으로 스트리밍한다(OutOfMemoryError 방지).
        val t0 = System.currentTimeMillis()
        val zipFile = BackupManager.exportToTempFile(context)
        log("백업 zip 임시 파일 생성 완료 (${System.currentTimeMillis() - t0}ms, ${zipFile.length()}바이트)")
        try {
            val metadata = JSONObject().apply {
                if (existingId == null) {
                    put("name", BACKUP_NAME)
                    put("parents", org.json.JSONArray().put("appDataFolder"))
                }
                put("appProperties", JSONObject().put(PROP_UPDATED_AT, updatedAt.toString()))
            }
            val boundary = "----raceCertBoundary${System.currentTimeMillis()}"
            val header = (
                "--$boundary\r\n" +
                    "Content-Type: application/json; charset=UTF-8\r\n\r\n" +
                    metadata.toString() +
                    "\r\n--$boundary\r\n" +
                    "Content-Type: application/zip\r\n\r\n"
                ).toByteArray()
            val footer = "\r\n--$boundary--\r\n".toByteArray()
            val totalLength = header.size.toLong() + zipFile.length() + footer.size.toLong()

            val urlStr = if (existingId == null) {
                "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart"
            } else {
                "https://www.googleapis.com/upload/drive/v3/files/$existingId?uploadType=multipart"
            }
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = if (existingId == null) "POST" else "PATCH"
                conn.doOutput = true
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
                conn.connectTimeout = 20_000
                conn.readTimeout = 30_000
                conn.setFixedLengthStreamingMode(totalLength)
                val transferStart = System.currentTimeMillis()
                conn.outputStream.use { out ->
                    out.write(header)
                    zipFile.inputStream().use { it.copyTo(out) }
                    out.write(footer)
                }
                log("HTTP 요청 전송 완료 (${System.currentTimeMillis() - transferStart}ms, 총 ${totalLength}바이트)")
                // 토큰 만료(401)·권한/할당량(403) 등은 실패로 확실히 처리 (조용한 성공 방지)
                if (conn.responseCode !in 200..299) {
                    conn.errorStream?.use { it.readBytes() }
                    throw java.io.IOException("Drive upload failed: HTTP ${conn.responseCode}")
                }
                conn.inputStream.use { it.readBytes() }
            } finally {
                conn.disconnect()
            }
        } finally {
            zipFile.delete()
        }
    }

    private fun downloadBackup(context: Context, token: String, fileId: String): File {
        val conn = URL("https://www.googleapis.com/drive/v3/files/$fileId?alt=media")
            .openConnection() as HttpURLConnection
        val file = File(context.cacheDir, "drive-backup-download-${System.currentTimeMillis()}.zip")
        try {
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            // 전체를 메모리에 올리지 않고 응답을 곧바로 파일로 스트리밍한다.
            conn.inputStream.use { input -> file.outputStream().use { output -> input.copyTo(output) } }
            return file
        } finally {
            conn.disconnect()
        }
    }

    private fun httpGet(url: String, token: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }
}
