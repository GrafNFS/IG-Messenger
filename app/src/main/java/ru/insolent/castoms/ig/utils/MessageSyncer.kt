package ru.insolent.castoms.ig.utils

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.insolent.castoms.ig.crypto.EncryptedPayload
import ru.insolent.castoms.ig.crypto.MessageContent
import ru.insolent.castoms.ig.crypto.MessageCryptoManager
import ru.insolent.castoms.ig.network.NetworkClient
import ru.insolent.castoms.ig.network.SocketManager

/**
 * Единый пайплайн догона сообщений с сервера:
 * дешифровка (с ленивой инициализацией сессии) → сохранение в Room →
 * фоновое скачивание вложений → delivery receipt → опциональное уведомление.
 *
 * Используется:
 *  - MessageReceiverService — глобальный догон ВСЕХ чатов при старте приложения;
 *  - ChatActivity.syncMessages — догон одного чата при его открытии.
 */
object MessageSyncer {

    private const val TAG = "MessageSyncer"

    /**
     * @return количество НОВЫХ сохранённых сообщений
     */
    suspend fun syncChat(
        context: Context,
        cryptoManager: MessageCryptoManager,
        messageManager: MessageManager,
        chatId: String,
        myId: String,
        showNotification: Boolean = false,
        onNewMessage: (suspend (messageId: String, from: String, content: MessageContent, messageType: String) -> Unit)? = null,
        downloadingFiles: MutableSet<String> = mutableSetOf()
    ): Int = withContext(Dispatchers.IO) {
        var newCount = 0
        try {
            val response = NetworkClient.apiService.getChatHistory(chatId, myId)
            if (!response.isSuccessful || response.body() == null) {
                Log.w(TAG, "History for $chatId failed: ${response.code()}")
                return@withContext 0
            }

            // Сервер отдаёт в порядке timestamp asc — дешифруем последовательно,
            // это важно для ratchet-цепочки сессии
            for (dto in response.body()!!) {
                val messageId = dto.id ?: continue
                val senderId = dto.senderId.takeIf { it.isNotBlank() } ?: continue
                if (senderId == myId) continue // свои исходящие уже есть локально

                try {
                    val payloadJson = Gson().toJson(dto.payload)
                    val payload = Gson().fromJson(payloadJson, EncryptedPayload::class.java)
                    val messageContent = decryptWithLazyInit(cryptoManager, payload, senderId)

                    val attachments = messageContent.attachments
                    val messageType = when {
                        attachments?.any { it.mediaType.startsWith("audio") } == true -> "audio"
                        attachments?.any { it.mediaType == "image/gif" } == true -> "gif"
                        attachments?.any { it.mediaType.startsWith("video") } == true -> "video"
                        !attachments.isNullOrEmpty() -> "image"
                        else -> "text"
                    }

                    try {
                        messageManager.saveIncomingMessage(
                            chatId = senderId,
                            senderId = senderId,
                            content = messageContent.text,
                            timestamp = dto.timestamp,
                            messageId = messageId,
                            messageType = messageType,
                            attachments = attachments
                        )
                        newCount++
                    } catch (e: Exception) {
                        // Дубликат в БД — сообщение уже обрабатывали
                        continue
                    }

                    // Фоновое скачивание медиа (не блокирует догон остальных сообщений)
                    attachments?.forEach { attachment ->
                        val fileId = attachment.fileId
                        if (!downloadingFiles.add(fileId)) return@forEach
                        launch {
                            try {
                                val localPath = messageManager.downloadAndDecryptMedia(
                                    context = context,
                                    fileId = fileId,
                                    encryptedKeyBase64 = attachment.encryptedKey,
                                    mediaType = attachment.mediaType ?: "image/jpeg",
                                    fileName = attachment.fileName ?: "file",
                                    messageId = messageId,
                                    expectedFileSize = attachment.fileSize ?: 0L,
                                    onProgress = { progress ->
                                        // Room Flow перерисует UI с новым прогрессом
                                        launch {
                                            try {
                                                messageManager.updateAttachmentDownloadProgress(messageId, fileId, progress)
                                            } catch (_: Exception) {}
                                        }
                                    }
                                )
                                if (localPath != null) {
                                    NetworkClient.apiService.deleteMedia(fileId)
                                }
                            } finally {
                                downloadingFiles.remove(fileId)
                            }
                        }
                    }

                    SocketManager.sendDeliveryReceipt(senderId, messageId)

                    if (showNotification) {
                        onNewMessage?.invoke(messageId, senderId, messageContent, messageType)
                    }

                } catch (e: org.signal.libsignal.protocol.DuplicateMessageException) {
                    // Уже обработано сессией ранее — пропускаем
                } catch (e: Exception) {
                    Log.w(TAG, "Could not process message $messageId from $senderId", e)
                }
            }

            if (newCount > 0) {
                Log.i(TAG, "📥 Synced $newCount new messages for chat $chatId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed for chat $chatId", e)
        }
        newCount
    }

    /**
     * Дешифрует сообщение; если сессии с отправителем нет — лениво инициализирует
     * её по key bundle с сервера и повторяет попытку (важно после переустановки
     * приложения, когда Room-сессии пусты, а история на сервере есть).
     */
    private suspend fun decryptWithLazyInit(
        cryptoManager: MessageCryptoManager,
        payload: EncryptedPayload,
        senderId: String
    ): MessageContent {
        return try {
            cryptoManager.decryptMessage(payload, senderId)
        } catch (e: Exception) {
            val needInit = e is org.signal.libsignal.protocol.NoSessionException ||
                    (e is IllegalStateException && e.message?.contains("Session not ready") == true)
            if (!needInit) throw e

            Log.d(TAG, "No session with $senderId — lazy init from key bundle")
            val bundleResp = NetworkClient.apiService.getKeysBundle(senderId)
            val bundle = bundleResp.body()
            if (!bundleResp.isSuccessful || bundle == null) throw e

            cryptoManager.initializeSession(senderId, bundle)
            cryptoManager.decryptMessage(payload, senderId)
        }
    }
}
