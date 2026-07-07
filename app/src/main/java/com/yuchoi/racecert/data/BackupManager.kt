package com.yuchoi.racecert.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 대회 기록(records.json)과 기록증 이미지들을 하나의 .zip으로 백업/복원한다.
 * SAF(파일 선택창)로 Google Drive 등에 저장하고, 다른 기기에서 그 파일을 불러와 복원한다.
 */
object BackupManager {

    private fun dataFile(context: Context) = File(context.filesDir, "records.json")
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
            val resolver = context.contentResolver
            resolver.openOutputStream(uri)?.use { os ->
                ZipOutputStream(os).use { zip ->
                    val json = dataFile(context)
                    if (json.exists()) {
                        zip.putNextEntry(ZipEntry("records.json"))
                        json.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                    imagesDir(context).listFiles()?.forEach { f ->
                        zip.putNextEntry(ZipEntry("images/${f.name}"))
                        f.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            } ?: return@runCatching false
            true
        }.getOrDefault(false)
    }

    suspend fun import(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val images = imagesDir(context)
            var foundJson = false
            resolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input).use { zip ->
                    var entry: ZipEntry? = zip.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        when {
                            name == "records.json" -> {
                                dataFile(context).outputStream().use { zip.copyTo(it) }
                                foundJson = true
                            }
                            name.startsWith("images/") && !entry.isDirectory -> {
                                val fn = name.substringAfter("images/")
                                if (fn.isNotBlank()) {
                                    File(images, fn).outputStream().use { zip.copyTo(it) }
                                }
                            }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }
            if (!foundJson) return@runCatching false
            RecordStore.reload()
            true
        }.getOrDefault(false)
    }
}
