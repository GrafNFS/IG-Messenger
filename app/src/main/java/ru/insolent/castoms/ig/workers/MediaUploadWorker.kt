package ru.insolent.castoms.ig.workers

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import ru.insolent.castoms.ig.crypto.EncryptedPayload
import ru.insolent.castoms.ig.database.CryptoDatabase
import ru.insolent.castoms.ig.database.MessageStatus
import ru.insolent.castoms.ig.network.NetworkClient
import ru.insolent.castoms.ig.network.SocketManager
import ru.insolent.castoms.ig.utils.MessageManager
import ru.insolent.castoms.ig.utils.ProgressRequestBody
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
        val typeMsg = inputData.getString("typeMsg") ?: run {
            Log.e("MediaUploadWorker", "❌ КРИТИЧЕСКАЯ ОШИБКА: typeMsg пришел null из инпут-даты")
            return Result.failure()
        }

        Log.d("MediaUploadWorker", "🚀 Воркер запущен для messageId: $messageId, contactId: $contactId")

        val messageManager = MessageManager(applicationContext)

        delay(200)

        val encryptedPayloadJson = messageManager.getMessagePayload(messageId)
        if (encryptedPayloadJson == null) {
            Log.e("MediaUploadWorker", "❌ ОШИБКА СБОРКИ: encryptedPayload не найден в БД для messageId: $messageId")
            return Result.failure()
        }

        val attachments = messageManager.getAttachmentsForMessage(messageId)
        if (attachments.isEmpty()) {
            Log.e("MediaUploadWorker", "❌ ОШИБКА СБОРКИ: Вложения (attachments) НЕ найдены в БД для messageId: $messageId")
            return Result.failure()
        }

        // 🌟 Групповое сообщение. MessageManager хранит EncryptedPayload как JSON:
        //    {"type":-1,"data":"{payloads:[...]}"} — распознаём обе формы, шлём group_message.
        val gson = com.google.gson.Gson()
        val groupPayloads: List<ru.insolent.castoms.ig.network.GroupPayload>? = try {
            val obj = com.google.gson.JsonParser.parseString(encryptedPayloadJson).asJsonObject
            val target = when {
                obj.has("payloads") -> obj
                obj.has("data") && obj.get("type")?.asInt == -1 ->
                    com.google.gson.JsonParser.parseString(obj.get("data").asString).asJsonObject
                else -> null
            }
            if (target != null && target.has("payloads")) {
                target.getAsJsonArray("payloads").map {
                    val e = it.asJsonObject
                    ru.insolent.castoms.ig.network.GroupPayload(
                        e.get("userId").asString, e.get("type").asInt, e.get("data").asString
                    )
                }
            } else null
        } catch (_: Exception) { null }
        val payloadObject = gson.fromJson(encryptedPayloadJson, EncryptedPayload::class.java)
        val tempFilesList = mutableListOf<File>()
        var uploadAndSendSucceeded = false   // ← НОВЫЙ ФЛАГ


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

                        tempFilesList.add(file)

                        // 🌟 ПРОГРЕСС ЗАГРУЗКИ: пишем % в Room → Flow → UI
                        val progressBody = ProgressRequestBody(
                            file,
                            "application/octet-stream".toMediaTypeOrNull(),
                        ) { percent ->
                            runBlocking {
                                try {
                                    messageManager.updateAttachmentUploadProgress(messageId, fileId, percent)
                                } catch (_: Exception) {}
                            }
                        }

                        val part = okhttp3.MultipartBody.Part.createFormData(
                            "file", "$fileId.enc", progressBody
                        )

                        try {
                            val response = NetworkClient.apiService.uploadMedia(fileId, part, file.length())
                            Log.d("MediaUploadWorker", "📡 Ответ сервера для $fileId: Code=${response.code()}, IsSuccessful=${response.isSuccessful}")
                            if (response.isSuccessful) {
                                runBlocking {
                                    try {
                                        messageManager.updateAttachmentUploadProgress(messageId, fileId, 100)
                                    } catch (_: Exception) {}
                                }
                            }
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
            val isSocketSent = if (groupPayloads != null) {
                SocketManager.sendGroupMessage(contactId, groupPayloads, messageId, typeMsg)
            } else {
                SocketManager.sendEncryptedMessage(contactId, payloadObject, messageId, typeMsg)
            }
            Log.d("MediaUploadWorker", "🔌 Статус отправки сокета: $isSocketSent")

            if (isSocketSent) {
                updateMessageStatus(contactId, messageId, MessageStatus.SENT)
                uploadAndSendSucceeded = true
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
            if (uploadAndSendSucceeded) {
                tempFilesList.forEach { file ->
                    if (file.exists()) {
                        file.delete()
                        Log.d("MediaUploadWorker", "🗑️ Временный зашифрованный файл удален: ${file.name}")
                    }
                }
            }
        }
    }

    private suspend fun updateMessageStatus(chatId: String, messageId: String, status: MessageStatus) {
        dao.updateMessageStatus(chatId, messageId, status)
    }
}