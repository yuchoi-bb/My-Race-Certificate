package com.yuchoi.racecert.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    private fun recurringFile(context: Context) = File(context.filesDir, "recurring_purchases.json")
    private fun categoriesFile(context: Context) = File(context.filesDir, "purchase_categories.json")
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
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: return@withContext ImportResult.CantOpen
            // 전체를 메모리에 올리지 않고 스트림에서 바로 읽는다. 유효한 zip이 아니면
            // ZipInputStream이 ZipException을 던진다.
            val success = input.use { readZip(context, it) }
            if (success) {
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

    /**
     * Drive 동기화용: 백업을 캐시 폴더의 임시 파일로 만든다(전체를 메모리에 올리지 않음).
     * 이미지가 쌓여 zip이 수백MB가 될 수 있어, ByteArray로 들고 있으면 OutOfMemoryError가 난다.
     * 호출한 쪽에서 다 쓰고 나면 파일을 지워야 한다.
     */
    suspend fun exportToTempFile(context: Context): File = withContext(Dispatchers.IO) {
        val file = File(context.cacheDir, "drive-backup-${System.currentTimeMillis()}.zip")
        file.outputStream().use { os -> writeZip(context, os) }
        file
    }

    /** Drive 동기화용: 스트림에서 바로 zip을 읽어 복원한다(전체를 메모리에 올리지 않음). */
    suspend fun importStream(context: Context, input: InputStream): Boolean = withContext(Dispatchers.IO) {
        runCatching { readZip(context, input) }.getOrDefault(false)
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
            val recurringJson = recurringFile(context)
            if (recurringJson.exists()) {
                zip.putNextEntry(ZipEntry("recurring_purchases.json"))
                recurringJson.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            val categoriesJson = categoriesFile(context)
            if (categoriesJson.exists()) {
                zip.putNextEntry(ZipEntry("purchase_categories.json"))
                categoriesJson.inputStream().use { it.copyTo(zip) }
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
        var foundRecords = false
        var foundPurchases = false
        var foundRecurring = false
        var foundCategories = false
        ZipInputStream(input).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                when {
                    name == "records.json" -> {
                        dataFile(context).outputStream().use { zip.copyTo(it) }
                        foundRecords = true
                    }
                    name == "purchases.json" -> {
                        purchasesFile(context).outputStream().use { zip.copyTo(it) }
                        foundPurchases = true
                    }
                    name == "recurring_purchases.json" -> {
                        recurringFile(context).outputStream().use { zip.copyTo(it) }
                        foundRecurring = true
                    }
                    name == "purchase_categories.json" -> {
                        categoriesFile(context).outputStream().use { zip.copyTo(it) }
                        foundCategories = true
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
        // records.json·purchases.json·recurring_purchases.json·purchase_categories.json 중
        // 하나만 있어도 성공으로 처리한다.
        if (!foundRecords && !foundPurchases && !foundRecurring && !foundCategories) return false
        RecordStore.reload()
        PurchaseStore.reload()
        RecurringPurchaseStore.reload()
        PurchaseCategoryStore.reload()
        return true
    }
}
