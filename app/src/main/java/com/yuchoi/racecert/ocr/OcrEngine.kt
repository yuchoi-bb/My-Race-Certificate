package com.yuchoi.racecert.ocr

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/**
 * 온디바이스 텍스트 인식(ML Kit). 한국어 인식기는 한글 + 라틴 문자를 함께 처리하므로
 * 국내/해외 기록증(영문 혼용)에 두루 쓸 수 있다. 네트워크 불필요.
 */
object OcrEngine {

    private val recognizer =
        TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())

    /** 이미지 파일 하나의 전체 인식 텍스트를 반환한다. 실패 시 빈 문자열. */
    suspend fun recognize(context: Context, path: String): String =
        suspendCancellableCoroutine { cont ->
            try {
                val image = InputImage.fromFilePath(context, Uri.fromFile(File(path)))
                recognizer.process(image)
                    .addOnSuccessListener { result -> cont.resume(result.text) }
                    .addOnFailureListener { cont.resume("") }
            } catch (_: Exception) {
                cont.resume("")
            }
        }
}
