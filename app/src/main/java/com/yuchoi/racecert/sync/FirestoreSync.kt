package com.yuchoi.racecert.sync

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.firestore.FirebaseFirestore
import com.yuchoi.racecert.data.PurchaseCategoryStore
import com.yuchoi.racecert.data.PurchaseStore
import com.yuchoi.racecert.data.RecordStore
import com.yuchoi.racecert.data.RecurringPurchaseStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "FirestoreSync"

/**
 * Firestore를 이용해 records.json·purchases.json·recurring_purchases.json·
 * purchase_categories.json을 계정별로 여러 기기에 실시간으로 맞춘다.
 *
 * 각 파일을 그대로 하나의 Firestore 문서(json 텍스트 + updatedAt)로 저장하고,
 * 스냅샷 리스너로 원격 변경을 즉시 받아 로컬 파일에 반영한다. Google Drive
 * 백업(zip, 이미지 포함)과는 별개로 동작하며 데이터(텍스트)만 실시간으로 맞춘다.
 *
 * 진단용: [debugLog]에 모든 단계를 사람이 읽을 수 있는 문장으로 남긴다(logcat 없이도
 * 화면에서 바로 무엇이 실패했는지 볼 수 있도록, SummaryScreen에 표시한다).
 */
object FirestoreSync {

    private data class Slot(val key: String, val label: String, val file: (Context) -> File, val onReload: () -> Unit)

    private val slots = listOf(
        Slot("records", "대회 기록", { File(it.filesDir, "records.json") }, { RecordStore.reload() }),
        Slot("purchases", "구매 내역", { File(it.filesDir, "purchases.json") }, { PurchaseStore.reload() }),
        Slot(
            "recurringPurchases",
            "정기 지출",
            { File(it.filesDir, "recurring_purchases.json") },
            { RecurringPurchaseStore.reload() },
        ),
        Slot(
            "purchaseCategories",
            "구매 항목",
            { File(it.filesDir, "purchase_categories.json") },
            { PurchaseCategoryStore.reload() },
        ),
    )

    /** 화면에 그대로 띄울 수 있는 진단 로그. 최신 항목이 맨 앞. */
    val debugLog: SnapshotStateList<String> = mutableStateListOf()

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.KOREAN)

    private fun log(msg: String) {
        val line = "[${timeFormat.format(Date())}] $msg"
        Log.d(TAG, msg)
        runCatching { FirebaseCrashlytics.getInstance().log("[$TAG] $msg") }
        debugLog.add(0, line)
        while (debugLog.size > 200) debugLog.removeAt(debugLog.size - 1)
    }

    private fun logError(msg: String, t: Throwable) {
        val detail = "$msg :: ${t.javaClass.simpleName}: ${t.message}"
        Log.e(TAG, detail, t)
        runCatching {
            FirebaseCrashlytics.getInstance().log("[$TAG] $detail")
            FirebaseCrashlytics.getInstance().recordException(t)
        }
        debugLog.add(0, "[${timeFormat.format(Date())}] ❌ $detail")
        while (debugLog.size > 200) debugLog.removeAt(debugLog.size - 1)
    }

    fun clearLog() = debugLog.clear()

    // FirebaseAuth/FirebaseFirestore 초기화 자체가 프로젝트 설정 문제로 실패할 수 있어,
    // 지연 초기화 시점의 예외도 절대 앱을 죽이지 않도록 runCatching으로 감싼다.
    private val auth: FirebaseAuth? by lazy {
        runCatching { FirebaseAuth.getInstance() }
            .onSuccess { log("FirebaseAuth 초기화 성공") }
            .onFailure { logError("FirebaseAuth 초기화 실패", it) }
            .getOrNull()
    }
    private val db: FirebaseFirestore? by lazy {
        runCatching { FirebaseFirestore.getInstance() }
            .onSuccess { log("FirebaseFirestore 초기화 성공") }
            .onFailure { logError("FirebaseFirestore 초기화 실패", it) }
            .getOrNull()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 우리가 마지막으로 이 값으로 밀어 올렸다는 걸 기억해, 원격 리스너가 우리 자신의
    // 쓰기를 다시 되돌려 받는(echo) 걸 걸러낸다.
    private val lastPushedAt = HashMap<String, Long>()
    private var listenersUid: String? = null

    fun isSignedIn(): Boolean = runCatching { auth?.currentUser != null }.getOrDefault(false)

    /** Google 로그인에서 받은 idToken으로 Firebase 인증을 하고, 성공하면 실시간 리스너를 켠다. */
    fun signInWithGoogleIdToken(idToken: String?, context: Context, onDone: (Boolean) -> Unit = {}) {
        log("signInWithGoogleIdToken 호출 (idToken ${if (idToken == null) "없음" else "있음, 길이 ${idToken.length}"})")
        if (idToken == null) {
            log("idToken이 null이라 Firebase 인증을 건너뜀 (Google 로그인 설정에 웹 클라이언트 ID가 안 잡혔을 수 있음)")
            onDone(false)
            return
        }
        runCatching {
            val credential = GoogleAuthProvider.getCredential(idToken, null)
            val firebaseAuth = auth
            if (firebaseAuth == null) {
                log("auth 인스턴스가 null이라 signInWithCredential 호출 안 함")
                onDone(false)
                return
            }
            log("auth.signInWithCredential 호출")
            firebaseAuth.signInWithCredential(credential)
                .addOnSuccessListener { result ->
                    log("Firebase 로그인 성공 uid=${result.user?.uid} email=${result.user?.email}")
                    start(context)
                    onDone(true)
                }
                .addOnFailureListener {
                    logError("signInWithCredential 실패", it)
                    onDone(false)
                }
        }.onFailure {
            logError("signInWithGoogleIdToken 도중 예외 발생", it)
            onDone(false)
        }
    }

    fun signOut() {
        log("signOut 호출")
        listenersUid = null
        lastPushedAt.clear()
        runCatching { auth?.signOut() }
    }

    /** 로그인 상태라면 4개 문서에 대한 실시간 리스너를 켠다(이미 켜져 있으면 무시). */
    fun start(context: Context) {
        runCatching {
            val firestore = db
            if (firestore == null) {
                log("start(): Firestore 인스턴스가 없어 리스너를 켤 수 없음")
                return
            }
            val uid = auth?.currentUser?.uid
            if (uid == null) {
                log("start(): 로그인된 사용자가 없어 리스너를 켤 수 없음")
                return
            }
            if (listenersUid == uid) {
                log("start(): uid=$uid 리스너가 이미 켜져 있어 건너뜀")
                return
            }
            listenersUid = uid
            val appContext = context.applicationContext
            log("start(): uid=$uid 로 ${slots.size}개 문서에 실시간 리스너 등록 시작")
            for (slot in slots) {
                firestore.collection("users").document(uid).collection("appdata").document(slot.key)
                    .addSnapshotListener { snap, err ->
                        if (err != null) {
                            logError("[${slot.label}] 리스너 오류(권한/규칙 문제일 수 있음)", err)
                            return@addSnapshotListener
                        }
                        runCatching {
                            if (snap == null || !snap.exists()) {
                                log("[${slot.label}] 원격 문서 없음 (아직 아무 기기도 업로드 안 함)")
                                return@runCatching
                            }
                            val updatedAt = snap.getLong("updatedAt")
                            val json = snap.getString("json")
                            if (updatedAt == null || json == null) {
                                log("[${slot.label}] 원격 문서 필드 누락 (updatedAt=$updatedAt, json 있음=${json != null})")
                                return@runCatching
                            }
                            val cache = if (snap.metadata.isFromCache) "캐시" else "서버"
                            if (updatedAt <= (lastPushedAt[slot.key] ?: 0L)) {
                                log("[${slot.label}] 스냅샷($cache) 수신: 내가 방금 올린 내용의 echo라 건너뜀 (updatedAt=$updatedAt)")
                                return@runCatching
                            }
                            log("[${slot.label}] 스냅샷($cache) 수신: 원격 변경 적용 시작 (updatedAt=$updatedAt, ${json.length}자)")
                            lastPushedAt[slot.key] = updatedAt
                            slot.file(appContext).writeText(json)
                            slot.onReload()
                            log("[${slot.label}] 원격 변경 적용 완료")
                        }.onFailure { logError("[${slot.label}] 원격 변경 적용 실패", it) }
                    }
            }
            log("start(): 리스너 등록 요청 완료")
        }.onFailure { logError("start() 도중 예외 발생", it) }
    }

    /** 로그인 상태라면 로컬 4개 파일을 Firestore로 밀어 올린다. DriveSync.requestSync에서 함께 호출된다. */
    fun requestSync(context: Context) {
        val appContext = context.applicationContext
        log("requestSync() 호출됨")
        scope.launch {
            runCatching {
                val firestore = db
                if (firestore == null) {
                    log("requestSync(): Firestore 인스턴스가 없어 중단")
                    return@launch
                }
                val uid = auth?.currentUser?.uid
                if (uid == null) {
                    log("requestSync(): 로그인 상태가 아니라 중단 (Google 로그인은 했지만 Firebase 인증이 아직 안 됐을 수 있음)")
                    return@launch
                }
                log("requestSync(): uid=$uid 로 진행")
                start(appContext)
                val now = System.currentTimeMillis()
                for (slot in slots) {
                    runCatching {
                        val file = slot.file(appContext)
                        if (!file.exists()) {
                            log("[${slot.label}] 로컬 파일 없음(${file.name}) - 업로드 건너뜀")
                            return@runCatching
                        }
                        val json = file.readText()
                        log("[${slot.label}] 로컬 파일 읽음 (${json.length}자) - Firestore로 업로드 시도")
                        lastPushedAt[slot.key] = now
                        firestore.collection("users").document(uid).collection("appdata").document(slot.key)
                            .set(mapOf("json" to json, "updatedAt" to now))
                            .addOnSuccessListener { log("[${slot.label}] 업로드 성공 (updatedAt=$now)") }
                            .addOnFailureListener { logError("[${slot.label}] 업로드 실패(Firestore 규칙/네트워크 확인)", it) }
                    }.onFailure { logError("[${slot.label}] 업로드 처리 중 예외 발생", it) }
                }
            }.onFailure { logError("requestSync() 도중 예외 발생", it) }
        }
    }
}
