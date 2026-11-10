package ru.insolent.castoms.ig.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface

object ImageHelper {
    private val TAG = "MessageHelper"
    fun generateThumbnailBase64(context: Context, uri: Uri): String? {
        return try {
            // 1. Декодируем только размеры, чтобы не грузить большое фото в память
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }

            // 2. Вычисляем масштаб, чтобы получить картинку ~100x100 пикселей
            val maxSize = 100
            val scale = Math.max(
                options.outWidth.toFloat() / maxSize,
                options.outHeight.toFloat() / maxSize
            ).toInt().coerceAtLeast(1)

            // 3. Декодируем уже с уменьшением (inSampleSize)
            val bitmapOptions = BitmapFactory.Options().apply { inSampleSize = scale }
            val bitmap = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bitmapOptions)
            }

            // 4. Сжимаем в JPEG с низким качеством (30%) для экономии места
            bitmap?.let {
                val byteArrayOutputStream = ByteArrayOutputStream()
                it.compress(Bitmap.CompressFormat.JPEG, 30, byteArrayOutputStream)
                val imageBytes = byteArrayOutputStream.toByteArray()

                // 5. Кодируем в Base64
                Base64.encodeToString(imageBytes, Base64.NO_WRAP)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate thumbnail", e)
            null
        }
    }

    suspend fun getCorrectlyOrientedBitmap(filePath: String): Bitmap? {
        // 1. Сначала декодируем исходный битмап
        val originalBitmap = BitmapFactory.decodeFile(filePath) ?: return null

        // 2. Читаем EXIF данные из файла
        val exif = try {
            ExifInterface(filePath)
        } catch (e: Exception) {
            return originalBitmap // Если не удалось прочитать, возвращаем как есть
        }

        // 3. Определяем текущую ориентацию
        val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)

        // 4. Если поворот не нужен, возвращаем оригинал
        if (orientation == ExifInterface.ORIENTATION_NORMAL) {
            return originalBitmap
        }

        // 5. Создаем матрицу для поворота
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            else -> return originalBitmap
        }

        // 6. Создаем и возвращаем повернутый битмап
        return try {
            Bitmap.createBitmap(originalBitmap, 0, 0, originalBitmap.width, originalBitmap.height, matrix, true)
        } catch (e: Exception) {
            originalBitmap // В случае ошибки возвращаем оригинал
        }
    }
}