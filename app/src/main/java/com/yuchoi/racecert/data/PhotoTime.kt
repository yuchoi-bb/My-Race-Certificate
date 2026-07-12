package com.yuchoi.racecert.data

import android.content.Context
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

/** 사진 URI의 촬영 시각을 읽어 시간순 정렬을 돕는다. */
object PhotoTime {

    /**
     * URI 목록을 촬영 시각(오름차순)으로 정렬한다.
     * 촬영 시각을 못 읽은 사진은 원래 순서를 유지하며 뒤로 보낸다(안정 정렬).
     */
    suspend fun sortByCaptureTime(context: Context, uris: List<Uri>): List<Uri> =
        withContext(Dispatchers.IO) {
            uris.map { it to captureMillis(context, it) }
                .withIndex()
                .sortedWith(
                    compareBy(
                        { it.value.second == null },            // 시각 없는 것은 뒤로
                        { it.value.second ?: Long.MAX_VALUE },   // 촬영 시각 오름차순
                        { it.index },                           // 동일 시 원래 순서 유지
                    ),
                )
                .map { it.value.first }
        }

    /** 촬영 시각(epoch millis). EXIF 우선, 없으면 MediaStore DATE_TAKEN. 실패 시 null. */
    fun captureMillis(context: Context, uri: Uri): Long? {
        exifMillis(context, uri)?.let { return it }
        return mediaStoreMillis(context, uri)
    }

    private fun exifMillis(context: Context, uri: Uri): Long? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val exif = ExifInterface(input)
            val raw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                ?: return null
            // "yyyy:MM:dd HH:mm:ss"
            SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(raw)?.time
        }
    }.getOrNull()

    private fun mediaStoreMillis(context: Context, uri: Uri): Long? = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED),
            null, null, null,
        )?.use { c ->
            if (!c.moveToFirst()) return null
            val takenIdx = c.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
            val taken = if (takenIdx >= 0 && !c.isNull(takenIdx)) c.getLong(takenIdx) else 0L
            if (taken > 0) return taken
            val addedIdx = c.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
            val added = if (addedIdx >= 0 && !c.isNull(addedIdx)) c.getLong(addedIdx) else 0L
            if (added > 0) added * 1000 else null // DATE_ADDED는 초 단위
        }
    }.getOrNull()
}
