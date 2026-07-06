package com.yuchoi.racecert.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import androidx.core.content.ContextCompat

/**
 * DownloadManager로 APK를 받고, 완료되면 시스템 설치 화면을 띄운다.
 * 설치 UI는 안드로이드가 처리하므로 사용자는 "설치" 버튼만 누르면 된다.
 */
class UpdateInstaller(private val context: Context) {

    private var pendingDownloadId: Long = -1L
    private var registered = false

    private val onDownloadComplete = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id != pendingDownloadId) return
            val downloadManager = ctx.getSystemService(DownloadManager::class.java)
            val apkUri = downloadManager.getUriForDownloadedFile(id) ?: return
            val install = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(install)
        }
    }

    fun register() {
        if (registered) return
        ContextCompat.registerReceiver(
            context,
            onDownloadComplete,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED,
        )
        registered = true
    }

    fun unregister() {
        if (!registered) return
        context.unregisterReceiver(onDownloadComplete)
        registered = false
    }

    fun startDownload(info: UpdateInfo) {
        val request = DownloadManager.Request(Uri.parse(info.apkUrl))
            .setTitle(info.apkName)
            .setDescription("새 버전 v${info.versionName} 다운로드 중")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, info.apkName)
        val downloadManager = context.getSystemService(DownloadManager::class.java)
        pendingDownloadId = downloadManager.enqueue(request)
    }
}
