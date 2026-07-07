package com.yuchoi.racecert.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/** 기기 갤러리(MediaStore)에서 특정 날짜 구간에 촬영된 사진을 조회한다. */
object DevicePhotos {

    /** raceDate 기준 [daysBefore]일 전부터 [daysAfter]일 후까지 촬영된 사진 URI 목록(최신순). */
    suspend fun photosAround(
        context: Context,
        raceDate: LocalDate,
        daysBefore: Long = 7,
        daysAfter: Long = 1,
    ): List<Uri> = withContext(Dispatchers.IO) {
        val zone = ZoneId.systemDefault()
        val startMs = raceDate.minusDays(daysBefore).atStartOfDay(zone).toInstant().toEpochMilli()
        val endMs = raceDate.plusDays(daysAfter).atStartOfDay(zone).toInstant().toEpochMilli()

        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media._ID)
        // DATE_TAKEN(ms)를 우선 쓰되, 없으면 DATE_ADDED(초)로 보정
        val takenMs = MediaStore.Images.Media.DATE_TAKEN
        val addedMs = "(${MediaStore.Images.Media.DATE_ADDED} * 1000)"
        val effective = "COALESCE($takenMs, $addedMs)"
        val selection = "$effective >= ? AND $effective < ?"
        val args = arrayOf(startMs.toString(), endMs.toString())
        val sortOrder = "$effective DESC"

        val uris = ArrayList<Uri>()
        runCatching {
            context.contentResolver.query(collection, projection, selection, args, sortOrder)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                while (c.moveToNext()) {
                    uris.add(ContentUris.withAppendedId(collection, c.getLong(idCol)))
                }
            }
        }
        uris
    }
}
