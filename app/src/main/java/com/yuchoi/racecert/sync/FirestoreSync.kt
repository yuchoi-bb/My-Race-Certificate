package com.yuchoi.racecert.sync

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.yuchoi.racecert.data.PurchaseCategoryStore
import com.yuchoi.racecert.data.PurchaseStore
import com.yuchoi.racecert.data.RecordStore
import com.yuchoi.racecert.data.RecurringPurchaseStore
import java.io.File

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

    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    // 우리가 마지막으로 이 값으로 밀어 올렸다는 걸 기억해, 원격 리스너가 우리 자신의
    // 쓰기를 다시 되돌려 받는(echo) 걸 걸러낸다.
    private val lastPushedAt = HashMap<String, Long>()
    private var listenersUid: String? = null

    fun isSignedIn(): Boolean = auth.currentUser != null

    /** Google 로그인에서 받은 idToken으로 Firebase 인증을 하고, 성공하면 실시간 리스너를 켠다. */
    fun signInWithGoogleIdToken(idToken: String?, context: Context, onDone: (Boolean) -> Unit = {}) {
        if (idToken == null) {
            onDone(false)
            return
        }
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(credential)
            .addOnSuccessListener {
                start(context)
                onDone(true)
            }
            .addOnFailureListener { onDone(false) }
    }

    fun signOut() {
        listenersUid = null
        lastPushedAt.clear()
        auth.signOut()
    }

    /** 로그인 상태라면 4개 문서에 대한 실시간 리스너를 켠다(이미 켜져 있으면 무시). */
    fun start(context: Context) {
        val uid = auth.currentUser?.uid ?: return
        if (listenersUid == uid) return
        listenersUid = uid
        val appContext = context.applicationContext
        for (slot in slots) {
            db.collection("users").document(uid).collection("appdata").document(slot.key)
                .addSnapshotListener { snap, _ ->
                    if (snap == null || !snap.exists()) return@addSnapshotListener
                    val updatedAt = snap.getLong("updatedAt") ?: return@addSnapshotListener
                    val json = snap.getString("json") ?: return@addSnapshotListener
                    if (updatedAt <= (lastPushedAt[slot.key] ?: 0L)) return@addSnapshotListener
                    lastPushedAt[slot.key] = updatedAt
                    runCatching { slot.file(appContext).writeText(json) }
                    slot.onReload()
                }
        }
    }

    /** 로그인 상태라면 로컬 4개 파일을 Firestore로 밀어 올린다. DriveSync.requestSync에서 함께 호출된다. */
    fun requestSync(context: Context) {
        val uid = auth.currentUser?.uid ?: return
        start(context)
        val appContext = context.applicationContext
        val now = System.currentTimeMillis()
        for (slot in slots) {
            val file = slot.file(appContext)
            if (!file.exists()) continue
            val json = runCatching { file.readText() }.getOrNull() ?: continue
            lastPushedAt[slot.key] = now
            db.collection("users").document(uid).collection("appdata").document(slot.key)
                .set(mapOf("json" to json, "updatedAt" to now))
        }
    }
}
