package com.yuchoi.racecert.sync

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
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

private const val TAG = "FirestoreSync"

/**
 * Firestore를 이용해 records.json·purchases.json·recurring_purchases.json·
 * purchase_categories.json을 계정별로 여러 기기에 실시간으로 맞춘다.
 *
 * 각 파일을 그대로 하나의 Firestore 문서(json 텍스트 + updatedAt)로 저장하고,
 * 스냅샷 리스너로 원격 변경을 즉시 받아 로컬 파일에 반영한다. Google Drive
 * 백업(zip, 이미지 포함)과는 별개로 동작하며 데이터(텍스트)만 실시간으로 맞춘다.
 */
object FirestoreSync {

    private data class Slot(val key: String, val file: (Context) -> File, val onReload: () -> Unit)

    private val slots = listOf(
        Slot("records", { File(it.filesDir, "records.json") }, { RecordStore.reload() }),
        Slot("purchases", { File(it.filesDir, "purchases.json") }, { PurchaseStore.reload() }),
        Slot(
            "recurringPurchases",
            { File(it.filesDir, "recurring_purchases.json") },
            { RecurringPurchaseStore.reload() },
        ),
        Slot(
            "purchaseCategories",
            { File(it.filesDir, "purchase_categories.json") },
            { PurchaseCategoryStore.reload() },
        ),
    )

    // FirebaseAuth/FirebaseFirestore 초기화 자체가 프로젝트 설정 문제로 실패할 수 있어,
    // 지연 초기화 시점의 예외도 절대 앱을 죽이지 않도록 runCatching으로 감싼다.
    private val auth: FirebaseAuth? by lazy {
        runCatching { FirebaseAuth.getInstance() }
            .onFailure { Log.e(TAG, "FirebaseAuth init failed", it) }
            .getOrNull()
    }
    private val db: FirebaseFirestore? by lazy {
        runCatching { FirebaseFirestore.getInstance() }
            .onFailure { Log.e(TAG, "FirebaseFirestore init failed", it) }
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
        if (idToken == null) {
            onDone(false)
            return
        }
        runCatching {
            val credential = GoogleAuthProvider.getCredential(idToken, null)
            auth?.signInWithCredential(credential)
                ?.addOnSuccessListener {
                    start(context)
                    onDone(true)
                }
                ?.addOnFailureListener {
                    Log.e(TAG, "signInWithCredential failed", it)
                    onDone(false)
                } ?: onDone(false)
        }.onFailure {
            Log.e(TAG, "signInWithGoogleIdToken threw", it)
            onDone(false)
        }
    }

    fun signOut() {
        listenersUid = null
        lastPushedAt.clear()
        runCatching { auth?.signOut() }
    }

    /** 로그인 상태라면 4개 문서에 대한 실시간 리스너를 켠다(이미 켜져 있으면 무시). */
    fun start(context: Context) {
        runCatching {
            val firestore = db ?: return
            val uid = auth?.currentUser?.uid ?: return
            if (listenersUid == uid) return
            listenersUid = uid
            val appContext = context.applicationContext
            for (slot in slots) {
                firestore.collection("users").document(uid).collection("appdata").document(slot.key)
                    .addSnapshotListener { snap, err ->
                        if (err != null) {
                            Log.e(TAG, "listener error for ${slot.key}", err)
                            return@addSnapshotListener
                        }
                        runCatching {
                            if (snap == null || !snap.exists()) return@runCatching
                            val updatedAt = snap.getLong("updatedAt") ?: return@runCatching
                            val json = snap.getString("json") ?: return@runCatching
                            if (updatedAt <= (lastPushedAt[slot.key] ?: 0L)) return@runCatching
                            lastPushedAt[slot.key] = updatedAt
                            slot.file(appContext).writeText(json)
                            slot.onReload()
                        }.onFailure { Log.e(TAG, "applying remote change for ${slot.key} failed", it) }
                    }
            }
        }.onFailure { Log.e(TAG, "start() failed", it) }
    }

    /** 로그인 상태라면 로컬 4개 파일을 Firestore로 밀어 올린다. DriveSync.requestSync에서 함께 호출된다. */
    fun requestSync(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            runCatching {
                val firestore = db ?: return@launch
                val uid = auth?.currentUser?.uid ?: return@launch
                start(appContext)
                val now = System.currentTimeMillis()
                for (slot in slots) {
                    runCatching {
                        val file = slot.file(appContext)
                        if (!file.exists()) return@runCatching
                        val json = file.readText()
                        lastPushedAt[slot.key] = now
                        firestore.collection("users").document(uid).collection("appdata").document(slot.key)
                            .set(mapOf("json" to json, "updatedAt" to now))
                            .addOnFailureListener { Log.e(TAG, "push ${slot.key} failed", it) }
                    }.onFailure { Log.e(TAG, "pushing ${slot.key} threw", it) }
                }
            }.onFailure { Log.e(TAG, "requestSync failed", it) }
        }
    }
}
