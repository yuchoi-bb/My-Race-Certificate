package com.yuchoi.racecert.sync

import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.google.firebase.crashlytics.FirebaseCrashlytics
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Drive·Firestore 동기화 과정을 시각과 함께 화면에 보여주는 공용 디버그 로그.
 * SummaryScreen에 그대로 표시해, adb 없이도 어느 단계에서 얼마나 걸렸는지 볼 수 있다.
 */
object SyncDebugLog {

    val entries: SnapshotStateList<String> = mutableStateListOf()

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.KOREAN)

    fun clear() = entries.clear()

    fun log(tag: String, msg: String) {
        val line = "[${timeFormat.format(Date())}] [$tag] $msg"
        Log.d(tag, msg)
        runCatching { FirebaseCrashlytics.getInstance().log(line) }
        entries.add(0, line)
        trim()
    }

    fun logError(tag: String, msg: String, t: Throwable) {
        val detail = "$msg :: ${t.javaClass.simpleName}: ${t.message}"
        Log.e(tag, detail, t)
        runCatching {
            FirebaseCrashlytics.getInstance().log("[$tag] $detail")
            FirebaseCrashlytics.getInstance().recordException(t)
        }
        entries.add(0, "[${timeFormat.format(Date())}] [$tag] ❌ $detail")
        trim()
    }

    private fun trim() {
        while (entries.size > 300) entries.removeAt(entries.size - 1)
    }
}
