package com.yuchoi.racecert.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 사진을 긴 변 2048px · JPEG 85%로 축소해 같은 경로에 다시 저장한다 (저장 용량 절감).
 * 대회 기록증(대표 이미지)은 호출부에서 제외해 원본 화질을 유지한다.
 * 원본은 어차피 갤러리/Google Photos에 남아 있으므로 앱 사본만 줄이는 구조다.
 */
object ImageCompressor {

    private const val MAX_DIM = 2048
    private const val QUALITY = 85
    private const val MIN_BYTES = 1_500_000L // 이보다 작은 파일은 건드리지 않는다

    /** 파일이 크면 축소해 같은 경로에 덮어쓴다. 실제로 줄였으면 true. */
    suspend fun compressInPlace(path: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val src = File(path)
            if (!src.exists()) return@withContext false
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
            if (maxDim <= 0) return@withContext false
            if (maxDim <= MAX_DIM && src.length() <= MIN_BYTES) return@withContext false

            var sample = 1
            while (maxDim / sample > MAX_DIM) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            var bmp = BitmapFactory.decodeFile(path, opts) ?: return@withContext false

            // 재인코딩하면 EXIF가 사라지므로, 회전 정보를 픽셀에 직접 반영한다
            val rotation = when (
                ExifInterface(path).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            ) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (rotation != 0f) {
                val m = Matrix().apply { postRotate(rotation) }
                val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                if (rotated != bmp) bmp.recycle()
                bmp = rotated
            }

            val tmp = File(src.parentFile, src.name + ".tmp")
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
            bmp.recycle()
            // 실제로 작아졌을 때만 교체 (드물게 커지면 원본 유지)
            if (tmp.length() in 1 until src.length()) {
                tmp.renameTo(src)
                true
            } else {
                tmp.delete()
                false
            }
        }.getOrDefault(false)
    }
}
