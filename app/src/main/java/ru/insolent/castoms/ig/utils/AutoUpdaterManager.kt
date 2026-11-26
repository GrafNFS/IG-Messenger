package ru.insolent.castoms.ig.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.delay
import ru.insolent.castoms.ig.model.AppCheckVersion
import ru.insolent.castoms.ig.network.NetworkClient
import java.io.File
import java.io.FileOutputStream

/**
 * Автообновление APK.
 * 🌟 Скачивание с ДОКАЧКОЙ по Range (как медиа): частичный файл остаётся между
 * попытками и перезапусками; сервер отдаёт 206 на Range (res.sendFile).
 * Установка — напрямую в пакетный установщик (без chooser «открыть с помощью»),
 * с фолбэком на ACTION_VIEW.
 */
class AutoUpdaterManager(private val context: Context) {

    suspend fun checkForUpdate(): AppCheckVersion? {
        try {
            val response = NetworkClient.apiService.getCheckUpdate()
            if (response.isSuccessful && response.body() != null) {
                val dto = response.body()!!
                Log.d("AutoUpdaterManager", "latest version: ${dto.latestVersion}")
                val current = getCurrentAppVersionName()
                if (compareVersions(current, dto.latestVersion)) return dto
            }
        } catch (e: Exception) {
            Log.e("AutoUpdaterManager", "check failed", e)
        }
        return null
    }

    fun getCurrentAppVersionName(): String? {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        return packageInfo.versionName
    }

    private fun compareVersions(currentVersion: String?, latestVersion: String): Boolean {
        return currentVersion != latestVersion
    }

    /**
     * Скачивает APK с докачкой и ставит. [fileSize] — ожидаемый размер (из /api/updateapp).
     */
    suspend fun downloadApk(
        context: Context,
        fileSize: Long,
        apkName: String,
        onProgressUpdate: (Int) -> Unit
    ) {
        try {
            val downloadDir = File(context.getExternalFilesDir(null), "Download/version").apply { if (!exists()) mkdirs() }
            val newVersionFile = File(downloadDir, "$apkName.apk")

            // Уже полностью скачан ранее — сразу ставим
            if (newVersionFile.exists() && fileSize > 0 && newVersionFile.length() == fileSize) {
                Log.d("AutoUpdaterManager", "APK already complete (${newVersionFile.length()} bytes) — installing")
                installApk(context, newVersionFile)
                return
            }

            var attempt = 0
            val maxAttempts = 5
            var downloadSuccess = false

            while (attempt < maxAttempts && !downloadSuccess) {
                attempt++
                val resumeFrom = newVersionFile.length()
                val rangeHeader = if (resumeFrom > 0) "bytes=$resumeFrom-" else null
                Log.d("AutoUpdaterManager", "Download attempt $attempt/$maxAttempts for $apkName (resume from $resumeFrom)")

                val response = NetworkClient.apiService.downloadApk(apkName, rangeHeader)
                if (!response.isSuccessful || response.body() == null) {
                    if (response.code() == 416) {
                        Log.w("AutoUpdaterManager", "416 — локальный файл больше серверного, перекачиваем с нуля")
                        newVersionFile.delete()
                    } else {
                        Log.w("AutoUpdaterManager", "Download failed (attempt $attempt). Code: ${response.code()}")
                        delay(1000)
                    }
                    continue
                }

                // Только 206 означает докачку; 200 — сервер отдал с начала: перезаписываем
                val append = resumeFrom > 0 && response.code() == 206
                if (resumeFrom > 0 && !append) {
                    Log.w("AutoUpdaterManager", "Server ignored Range (code ${response.code()}), restart from zero")
                    newVersionFile.delete()
                }
                var totalBytes = newVersionFile.length()

                response.body()!!.byteStream().use { inputStream ->
                    FileOutputStream(newVersionFile, append).use { outputStream ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            outputStream.write(buffer, 0, bytesRead)
                            totalBytes += bytesRead
                            if (fileSize > 0) {
                                onProgressUpdate(((totalBytes.toFloat() / fileSize) * 100).toInt().coerceIn(0, 100))
                            }
                        }
                    }
                }

                if (fileSize > 0 && totalBytes != fileSize) {
                    Log.w("AutoUpdaterManager", "⚠️ Incomplete: expected $fileSize, have $totalBytes — will resume")
                    delay(1500)
                    continue
                }

                downloadSuccess = true
                Log.d("AutoUpdaterManager", "✅ Downloaded: $totalBytes bytes")
            }

            if (downloadSuccess) {
                installApk(context, newVersionFile)
            } else {
                Log.e("AutoUpdaterManager", "❌ Failed after $maxAttempts attempts")
                onProgressUpdate(0)
            }
        } catch (e: Exception) {
            Log.e("AutoUpdaterManager", "Download error: $e")
            onProgressUpdate(0)
        }
    }

    /** Установка НАПРЯМУЮ в пакетный установщик — без chooser. */
    fun installApk(context: Context, apkFile: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", apkFile
        )
        Log.d("AutoUpdaterManager", "File provider: $uri")

        val installerPackages = listOf(
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
            "com.samsung.android.packageinstaller",
            "miui.packageinstaller"
        )

        // Пробуем известные установщики — тогда не будет chooser
        for (pkg in installerPackages) {
            try {
                val pm = context.packageManager
                if (pm.getLaunchIntentForPackage(pkg) == null) continue
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setPackage(pkg)
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return
            } catch (e: Exception) {
                Log.w("AutoUpdaterManager", "Installer $pkg unavailable: ${e.message}")
            }
        }

        // Фолбэк: стандартный ACTION_VIEW (может показать chooser)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
