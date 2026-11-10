package ru.insolent.castoms.ig.utils

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.insolent.castoms.ig.model.AppCheckVersion
import ru.insolent.castoms.ig.network.NetworkClient
import java.io.File
import java.io.FileOutputStream

class AutoUpdaterManager(private val context: Context) {

    suspend fun checkForUpdate(): AppCheckVersion? {
        try {
            val response = NetworkClient.apiService.getCheckUpdate()
            if (response.isSuccessful && response.body() != null) {
                val dto = response.body()!!
                Log.d("latestversion", "latest version: ${dto.latestVersion}")
                val currentversion = getCurrentAppVersionName()
                val isupdateavailable = compareVersions(currentversion, dto.latestVersion)
                if (isupdateavailable) {
                    return dto
                } else return null
            }
        } catch (e: Exception) {
            return null
        }
        return null
    }

    fun getCurrentAppVersionName(): String? {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        return packageInfo.versionName
    }

    private fun compareVersions(currentVersion: String?, latestVersion: String): Boolean {
        return if (currentVersion.equals(latestVersion, false)) {
            false
        } else true
    }

    suspend fun downloadapk(
        context: Context,
        fileSize: Long,
        apkName: String,
        onProgressUpdate: (Int) -> Unit
    ) {
        try {
            val donwloadDir = File(context.getExternalFilesDir(null), "Download/version").apply { if (!exists()) mkdirs() }
            val newVersionFile = File(donwloadDir, "$apkName.apk")

            if (newVersionFile.exists() && newVersionFile.length() > 0) {
                installApk(context, newVersionFile)
            }
            var attempt = 0
            val maxAttempts = 5
            var downloadSuccess = false

            while (attempt < maxAttempts && !downloadSuccess) {
                attempt++
                Log.d("AutoUpdaterManager", "Download attempt $attempt/$maxAttempts for $newVersionFile")

                val response = NetworkClient.apiService.downloadApk(apkName)
                if (!response.isSuccessful || response.body() == null) {
                    Log.w("AutoUpdaterManager", "Download failed (attempt $attempt). Code: ${response.code()}")
                    delay(1000)
                    continue
                }

                var totalBytes = 0L
                val expectedSize = fileSize

                response.body()!!.byteStream().use { inputStream ->
                    FileOutputStream(newVersionFile).use { outputStream ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int

                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            outputStream.write(buffer, 0, bytesRead)
                            totalBytes += bytesRead

                            if (expectedSize > 0) {
                                val progress = ((totalBytes.toFloat() / expectedSize) * 100).toInt().coerceIn(0, 100)
                                onProgressUpdate(progress)
                            }
                        }
                    }
                }

                // ПРОВЕРКА ЦЕЛОСТНОСТИ
                if (expectedSize > 0 && totalBytes != expectedSize) {
                    Log.w("AutoUpdaterManager", "⚠️ File incomplete! Expected: $expectedSize, Downloaded: $totalBytes. Deleting and retrying...")
                    newVersionFile.delete()
                    delay(1500)
                    continue
                }

                downloadSuccess = true
                Log.d("AutoUpdaterManager", "✅ File downloaded successfully: $totalBytes bytes")
                if (downloadSuccess) {
                    installApk(context, newVersionFile)
                }
            }
        } catch (e: Exception) {
            Log.e("AutoUpdaterManager", "Download error: $e")
            onProgressUpdate(0)
        }


    }

    fun installApk(context: Context, apkName: File) {
        val uri: Uri =
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkName)
        Log.d("AutoUpdaterManager", "File provider: ${uri.toString()}")

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}