package ru.insolent.castoms.ig.service

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import ru.insolent.castoms.ig.crypto.EncryptedPayload
import ru.insolent.castoms.ig.database.CryptoDatabase
import ru.insolent.castoms.ig.database.MessageStatus
import ru.insolent.castoms.ig.network.NetworkClient
import ru.insolent.castoms.ig.network.SocketManager
import ru.insolent.castoms.ig.utils.MessageManager
import java.io.File

class MediaUploadWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {
    private val db = CryptoDatabase.getDatabase(context)
    private val dao = db.messageDao()

    override suspend fun doWork(): Result {
        val messageId = inputData.getString("messageId") ?: run {
            Log.e("MediaUploadWorker", "❌ КРИТИЧЕСКАЯ ОШИБКА: messageId пришел null из инпут-даты")
            return Result.failure()
        }
        val contactId = inputData.getString("contactId") ?: run {
            Log.e("MediaUploadWorker", "❌ КРИТИЧЕСКАЯ ОШИБКА: contactId пришел null из инпут-даты")
            return Result.failure()
        }

        Log.d("MediaUploadWorker", "🚀 Воркер запущен для messageId: $messageId, contactId: $contactId")

        val messageManager = MessageManager(applicationContext)

        // 🌟 Даем небольшую паузу (200мс), чтобы тяжелый пакет данных
        // гарантированно успел записаться в базу данных в ChatActivity перед чтением
        delay(200)

        // 🌟 1. Проверяем получение Payload
        val encryptedPayloadJson = messageManager.getMessagePayload(messageId)
        if (encryptedPayloadJson == null) {
            Log.e("MediaUploadWorker", "❌ ОШИБКА СБОРКИ: encryptedPayload не найден в БД для messageId: $messageId")
            return Result.failure()
        }
        Log.d("MediaUploadWorker", "✅ Зашифрованный payload успешно прочитан из БД")

        // 🌟 2. Проверяем получение Вложений
        val attachments = messageManager.getAttachmentsForMessage(messageId)
        if (attachments.isEmpty()) {
            Log.e("MediaUploadWorker", "❌ ОШИБКА СБОРКИ: Вложения (attachments) НЕ найдены в БД для messageId: $messageId")
            return Result.failure()
        }
        Log.d("MediaUploadWorker", "✅ Найдено вложений в БД: ${attachments.size}")

        val payloadObject = com.google.gson.Gson().fromJson(encryptedPayloadJson, EncryptedPayload::class.java)
        val tempFilesList = mutableListOf<File>()

        try {
            val uploadJobs = coroutineScope {
                attachments.map { attachment ->
                    async {
                        val fileId = attachment.fileId
                        val file = File(applicationContext.cacheDir, "$fileId.enc")

                        Log.d("MediaUploadWorker", "🔍 Проверка файла на диске: ${file.absolutePath}")
                        if (!file.exists()) {
                            Log.e("MediaUploadWorker", "❌ Файл не найден на диске: ${file.absolutePath} для fileId: $fileId")
                            return@async false
                        }
                        Log.d("MediaUploadWorker", "📦 Файл найден. Размер: ${file.length()} байт. Начинаем uploadMedia...")

                        tempFilesList.add(file)

                        val requestBody = RequestBody.create(
                            "application/octet-stream".toMediaTypeOrNull(),
                            file
                        )
                        val part = MultipartBody.Part.createFormData("file", "$fileId.enc", requestBody)

                        try {
                            val response = NetworkClient.apiService.uploadMedia(fileId, part)
                            Log.d("MediaUploadWorker", "📡 Ответ сервера для $fileId: Code=${response.code()}, IsSuccessful=${response.isSuccessful}")
                            response.isSuccessful
                        } catch (e: Exception) {
                            Log.e("MediaUploadWorker", "❌ Сетевая ошибка при отправке файла: $fileId", e)
                            false
                        }
                    }
                }
            }

            val results = uploadJobs.awaitAll()
            val allSuccessful = results.all { it }

            if (!allSuccessful) {
                Log.e("MediaUploadWorker", "❌ Один или несколько файлов не смогли загрузиться на сервер")
                updateMessageStatus(contactId, messageId, MessageStatus.FAILED)
                return Result.failure()
            }

            Log.d("MediaUploadWorker", "📨 Все файлы на сервере. Отправляем сокет...")
            val isSocketSent = SocketManager.sendEncryptedMessage(contactId, payloadObject, messageId)
            Log.d("MediaUploadWorker", "🔌 Статус отправки сокета: $isSocketSent")

            if (isSocketSent) {
                updateMessageStatus(contactId, messageId, MessageStatus.SENT)
                Log.d("MediaUploadWorker", "🎉 Воркер успешно выполнил всю работу!")
                return Result.success()
            } else {
                updateMessageStatus(contactId, messageId, MessageStatus.FAILED)
                return Result.failure()
            }

        } catch (e: Exception) {
            Log.e("MediaUploadWorker", "💥 Исключение внутри блока обработки воркера", e)
            updateMessageStatus(contactId, messageId, MessageStatus.FAILED)
            return Result.failure()
        } finally {
            tempFilesList.forEach { file ->
                if (file.exists()) {
                    file.delete()
                    Log.d("MediaUploadWorker", "🗑️ Временный зашифрованный файл удален: ${file.name}")
                }
            }
        }
    }

    private suspend fun updateMessageStatus(chatId: String, messageId: String, status: MessageStatus) {
        // Вызовите ваш менеджер сообщений для обновления UI/БД
        // Например: AppDatabase.getInstance(applicationContext).messageDao().updateStatus(...)
        // Или через ваш синглтон messageManager:
        //messageManager.updateStatus(chatId, messageId, status)
        dao.updateMessageStatus(chatId, messageId, status)
    }
}
