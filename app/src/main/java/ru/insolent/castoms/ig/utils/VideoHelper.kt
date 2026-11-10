package ru.insolent.castoms.ig.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.abedelazizshe.lightcompressorlibrary.CompressionListener
import com.abedelazizshe.lightcompressorlibrary.VideoCompressor
import com.abedelazizshe.lightcompressorlibrary.VideoQuality
import com.abedelazizshe.lightcompressorlibrary.config.Configuration
import com.abedelazizshe.lightcompressorlibrary.config.AppSpecificStorageConfiguration // 🌟 1. Новый импорт
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object VideoHelper {

    /**
     * Сжимает видео и возвращает путь к сжатому файлу
     */
    suspend fun compressVideo(
        context: Context,
        uri: Uri,
        outputFile: File
    ): File = suspendCancellableCoroutine { cont ->

        // 🌟 1. Уникальное имя БЕЗ расширения .mp4 (библиотека сама добавит расширение и суффикс)
        val tempVideoName = "compressed_${System.currentTimeMillis()}"

        // 🌟 2. ПРИНУДИТЕЛЬНО создаем папку во внутреннем хранилище перед запуском сжатия
        val subFolder = "compressed_videos"
        val cacheFolder = File(context.filesDir, subFolder)
        if (!cacheFolder.exists()) {
            cacheFolder.mkdirs()
        }

        VideoCompressor.start(
            context = context,
            uris = listOf(uri),
            isStreamable = true,
            appSpecificStorageConfiguration = AppSpecificStorageConfiguration(
                subFolderName = subFolder
            ),
            configureWith = Configuration(
                quality = VideoQuality.MEDIUM,
                isMinBitrateCheckEnabled = true,
                videoBitrateInMbps = 4,
                disableAudio = false,
                keepOriginalResolution = false,
                videoNames = listOf(tempVideoName)
            ),
            listener = object : CompressionListener {
                override fun onProgress(index: Int, percent: Float) {}

                override fun onStart(index: Int) {}

                override fun onSuccess(index: Int, size: Long, path: String?) {
                    Log.d("VideoHelper", "🎉 LightCompressor итоговый путь: $path")

                    // 🌟 3. Вычисляем точный файл на диске.
                    // Если библиотека вернула null или упала проверка, мы собираем путь вручную:
                    val realPath = path ?: File(cacheFolder, "${tempVideoName}.mp4_temp").absolutePath
                    val compressedFile = File(realPath)

                    // Дополнительная проверка на случай, если библиотека сохранила БЕЗ суффикса _temp
                    val backupFile = File(cacheFolder, "${tempVideoName}.mp4")

                    val fileToCopy = when {
                        compressedFile.exists() -> compressedFile
                        backupFile.exists() -> backupFile
                        else -> null
                    }

                    if (fileToCopy != null) {
                        try {
                            // Копируем сжатый файл в результирующий outputFile чата
                            fileToCopy.copyTo(outputFile, overwrite = true)
                            fileToCopy.delete() // Очищаем кэш библиотеки
                            Log.d("VideoHelper", "✅ Видео успешно сжато и скопировано в: ${outputFile.absolutePath}")
                            cont.resume(outputFile)
                        } catch (e: Exception) {
                            Log.e("VideoHelper", "❌ Ошибка копирования сжатого видео", e)
                            cont.resumeWithException(e)
                        }
                    } else {
                        Log.e("VideoHelper", "❌ Файл не найден на диске по путям: ${compressedFile.absolutePath} или ${backupFile.absolutePath}")
                        cont.resumeWithException(Exception("Сжатый видеофайл физически отсутствует на диске"))
                    }
                }

                override fun onFailure(index: Int, failureMessage: String) {
                    cont.resumeWithException(Exception("Compression failed: $failureMessage"))
                }

                override fun onCancelled(index: Int) {
                    cont.cancel()
                }
            }
        )
    }


    /**
     * Генерирует превью-кадр из видео (Base64)
     */
    fun generateVideoThumbnail(context: Context, uri: Uri): String? {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)

            val bitmap = retriever.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            retriever.release()

            bitmap?.let {
                val scale = Math.max(
                    it.width.toFloat() / 100,
                    it.height.toFloat() / 100
                ).toInt().coerceAtLeast(1)

                val scaledBitmap = Bitmap.createScaledBitmap(
                    it,
                    it.width / scale,
                    it.height / scale,
                    true
                )

                val byteArrayOutputStream = ByteArrayOutputStream()
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 30, byteArrayOutputStream)
                Base64.encodeToString(byteArrayOutputStream.toByteArray(), Base64.NO_WRAP)
            }
        } catch (e: Exception) {
            Log.e("VideoHelper", "Failed to generate thumbnail", e)
            null
        }
    }

    /**
     * Получает длительность видео в миллисекундах
     */
    fun getVideoDuration(context: Context, uri: Uri): Long {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            retriever.release()
            duration?.toLongOrNull() ?: 0
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Проверяет, является ли файл GIF
     */
    fun isGif(context: Context, uri: Uri): Boolean {
        return try {
            val mimeType = context.contentResolver.getType(uri)
            mimeType?.contains("gif") == true
        } catch (e: Exception) {
            false
        }
    }
}
