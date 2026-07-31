package com.yuchoi.racecert.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 대회 기록(records.json)과 기록증 이미지들을 하나의 .zip으로 백업/복원한다.
 * SAF(파일 선택창)로 Google Drive 등에 저장하고, 다른 기기에서 그 파일을 불러와 복원한다.
 */
object BackupManager {

    private fun dataFile(context: Context) = File(context.filesDir, "records.json")
    private fun purchasesFile(context: Context) = File(context.filesDir, "purchases.json")
    private fun imagesDir(context: Context) = File(context.filesDir, "images").apply {
        if (!exists()) mkdirs()
    }

    /** 백업 파일 제안 이름 */
    fun suggestedFileName(): String {
        val ts = java.time.LocalDate.now().toString().replace("-", "")
        return "race-backup-$ts.zip"
    }

    suspend fun export(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { os ->
                writeZip(context, os)
            } ?: return@runCatching false
            true
        }.getOrDefault(false)
    }

    sealed interface ImportResult {
        data object Success : ImportResult
        data object CantOpen : ImportResult
        data object NotAZip : ImportResult
        data object NoRecords : ImportResult
        data object IoError : ImportResult
    }

    suspend fun import(context: Context, uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return@withContext ImportResult.CantOpen
        // zip 매직 바이트(PK) 확인
        if (bytes.size < 4 || bytes[0] != 'P'.code.toByte() || bytes[1] != 'K'.code.toByte()) {
            return@withContext ImportResult.NotAZip
        }
        try {
            if (readZip(context, ByteArrayInputStream(bytes))) {
                // 복원한 데이터를 '가장 최신'으로 표시해, 이후 Drive 자동 동기화가
                // 예전 Drive 데이터로 되돌려 덮어쓰지 않도록 한다 (다음 동기화 때 업로드됨).
                RecordStore.setLocalUpdatedAt(System.currentTimeMillis())
                ImportResult.Success
            } else {
                ImportResult.NoRecords
            }
        } catch (_: java.util.zip.ZipException) {
            ImportResult.NotAZip
        } catch (_: Exception) {
            ImportResult.IoError
        }
    }

    /** Drive 동기화용: 백업을 바이트 배열로 만든다. */
    suspend fun exportBytes(context: Context): ByteArray = withContext(Dispatchers.IO) {
        val bos = ByteArrayOutputStream()
        writeZip(context, bos)
        bos.toByteArray()
    }

    /** Drive 동기화용: 바이트 배열 백업을 복원한다. */
    suspend fun importBytes(context: Context, bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        runCatching { readZip(context, ByteArrayInputStream(bytes)) }.getOrDefault(false)
    }

    private fun writeZip(context: Context, os: OutputStream) {
        // 어떤 기록·구매 영수증도 참조하지 않는 고아 이미지(삭제·회전으로 버려진 파일)는
        // 백업에서 제외해 백업 zip과 Drive 동기화 용량이 계속 불어나지 않게 한다.
        val referenced = RecordStore.records
            .flatMap { it.imagePaths }
            .map { File(it).name }
            .toHashSet()
        referenced += PurchaseStore.referencedImageNames()
        ZipOutputStream(os).use { zip ->
            val json = dataFile(context)
            if (json.exists()) {
                zip.putNextEntry(ZipEntry("records.json"))
                json.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            val purchasesJson = purchasesFile(context)
            if (purchasesJson.exists()) {
                zip.putNextEntry(ZipEntry("purchases.json"))
                purchasesJson.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            imagesDir(context).listFiles()?.forEach { f ->
                if (f.name !in referenced) return@forEach
                zip.putNextEntry(ZipEntry("images/${f.name}"))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun readZip(context: Context, input: InputStream): Boolean {
        val images = imagesDir(context)
        val imagesRoot = images.canonicalPath + File.separator
        var foundJson = false
        ZipInputStream(input).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                when {
                    name == "records.json" -> {
                        dataFile(context).outputStream().use { zip.copyTo(it) }
                        foundJson = true
                    }
                    name == "purchases.json" -> {
                        purchasesFile(context).outputStream().use { zip.copyTo(it) }
                    }
                    name.startsWith("images/") && !entry.isDirectory -> {
                        // 파일명만 취하고, 최종 경로가 images 폴더 안인지 확인 (Zip Slip 방어)
                        val fn = File(name.substringAfter("images/")).name
                        val dest = File(images, fn)
                        if (fn.isNotBlank() && dest.canonicalPath.startsWith(imagesRoot)) {
                            dest.outputStream().use { zip.copyTo(it) }
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        if (!foundJson) return false
        RecordStore.reload()
        PurchaseStore.reload()
        return true
    }
}
