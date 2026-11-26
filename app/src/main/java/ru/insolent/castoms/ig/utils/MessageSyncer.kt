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
            // ✅ Догоняем только хвост: сервер вернёт сообщения ПОЗЖЕ последнего
            //    локального (since = max timestamp в Room). Пустой чат → since = null →
            //    полная история (правильно при первом входе/переустановке).
            val since = messageManager.getLastMessageTimestamp(chatId)
            val response = NetworkClient.apiService.getChatHistory(chatId, myId, since)
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

                // ✅ Уже доставлялось ранее (по сокету) — не дешифруем повторно:
                //    иначе сессия бросает DuplicateMessageException (old counter)
                if (messageManager.getMessageById(messageId) != null) continue

                // 🌟 Системное сообщение: { system: true, text } — не шифруется
                val payloadObj0 = try {
                    com.google.gson.JsonParser.parseString(Gson().toJson(dto.payload)).asJsonObject
                } catch (e: Exception) { null }

                if (payloadObj0 != null && payloadObj0.has("system")) {
                    val sysText = payloadObj0.get("text").asString
                    try {
                        messageManager.saveIncomingMessage(
                            chatId = chatId, senderId = dto.senderId, content = sysText,
                            timestamp = dto.timestamp, messageId = messageId,
                            messageType = "system", attachments = null
                        )
                        newCount++
                    } catch (e: Exception) { /* дубликат */ }
                    continue
                }

                try {
                    val payloadJson = Gson().toJson(dto.payload)

                    // 🌟 Мульти-девайс контейнер: { fromDeviceId, payloads: [...] }.
                    //    Группа: entries {userId, deviceId}; личка: entries {deviceId}.
                    var isGroupMsg = false
                    val myDeviceId = cryptoManager.localDeviceId
                    val payload: EncryptedPayload? = run {
                        val obj = com.google.gson.JsonParser.parseString(payloadJson).asJsonObject
                        if (obj.has("payloads")) {
                            val arr = obj.getAsJsonArray("payloads")
                            if (arr.size() > 0 && arr.get(0).asJsonObject.has("userId")) {
                                isGroupMsg = true
                            }
                            val entry = if (isGroupMsg) {
                                arr.firstOrNull {
                                    val e = it.asJsonObject
                                    e.get("userId")?.asString == myId && e.get("deviceId")?.asInt == myDeviceId
                                }?.asJsonObject
                            } else {
                                arr.firstOrNull {
                                    it.asJsonObject.get("deviceId")?.asInt == myDeviceId
                                }?.asJsonObject
                            }
                            if (entry == null) {
                                // Не наш entry (мы — отправитель/не адресат) — пропуск
                                null
                            } else {
                                EncryptedPayload(entry.get("type").asInt, entry.get("data").asString)
                            }
                        } else {
                            Gson().fromJson(payloadJson, EncryptedPayload::class.java)
                        }
                    } ?: continue

                    // Дешифруем сессией с (отправитель, ЕГО устройство)
                    val fromDeviceId = payloadObj0?.get("fromDeviceId")?.asString?.toIntOrNull() ?: 1
                    val messageContent = decryptWithLazyInit(cryptoManager, payload!!, senderId, fromDeviceId)

                    val attachments = messageContent.attachments
                    val messageType = when {
                        attachments?.any { it.mediaType.startsWith("audio") } == true -> "audio"
                        attachments?.any { it.mediaType == "image/gif" } == true -> "gif"
                        attachments?.any { it.mediaType.startsWith("video") } == true -> "video"
                        !attachments.isNullOrEmpty() -> "image"
                        else -> "text"
                    }

                    try {
                        // ✅ chatId = чат, который догоняем (для групп это groupId,
                        //    НЕ senderId — иначе сообщение легло бы в чат отправителя)
                        messageManager.saveIncomingMessage(
                            chatId = chatId,
                            senderId = senderId,
                            content = messageContent.text,
                            timestamp = dto.timestamp,
                            messageId = messageId,
                            messageType = messageType,
                            attachments = attachments,
                            senderName = if (isGroupMsg) resolveSenderName(senderId, messageManager) else null
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
    /** Имя отправителя из локальных контактов */
    private suspend fun resolveSenderName(userId: String, messageManager: MessageManager): String? {
        val c = messageManager.getContactById(userId) as? ru.insolent.castoms.ig.database.ContactEntity
        return c?.displayName ?: c?.username
    }

    suspend fun decryptWithLazyInit(
        cryptoManager: MessageCryptoManager,
        payload: EncryptedPayload,
        senderId: String,
        senderDeviceId: Int
    ): MessageContent {
        return try {
            cryptoManager.decryptMessage(payload, senderId, senderDeviceId)
        } catch (e: Exception) {
            val needInit = e is org.signal.libsignal.protocol.NoSessionException ||
                    (e is IllegalStateException && e.message?.contains("Session not ready") == true)
            if (!needInit) throw e

            Log.d(TAG, "No session with $senderId:$senderDeviceId — lazy init from key bundle")
            val bundleResp = NetworkClient.apiService.getKeysBundle(senderId)
            val bundle = bundleResp.body()
            if (!bundleResp.isSuccessful || bundle == null) throw e

            cryptoManager.initializeSession(senderId, bundle)
            cryptoManager.decryptMessage(payload, senderId, senderDeviceId)
        }
    }
}
