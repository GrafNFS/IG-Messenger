package ru.insolent.castoms.ig.utils

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import ru.insolent.castoms.ig.crypto.EncryptedPayload
import ru.insolent.castoms.ig.crypto.MediaAttachment
import ru.insolent.castoms.ig.crypto.MediaCrypto
import ru.insolent.castoms.ig.database.AttachmentEntity // ✅ Новый импорт
import ru.insolent.castoms.ig.database.ContactEntity
import ru.insolent.castoms.ig.database.Conversation
import ru.insolent.castoms.ig.database.CryptoDatabase
import ru.insolent.castoms.ig.database.MessageEntity
import ru.insolent.castoms.ig.database.MessageStatus
import ru.insolent.castoms.ig.database.MessageWithAttachments
import ru.insolent.castoms.ig.network.NetworkClient
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class MessageManager(private val context: Context) {
    private val db = CryptoDatabase.getDatabase(context)
    private val dao = db.messageDao()
    private val daoChat = db.conversationDao()
    private val contactDao = db.contactDao()

    /**
     * Сохраняет исходящее сообщение перед отправкой
     */
    suspend fun saveOutgoingMessage(
        chatId: String,
        content: String,
        myId: String,
        encryptedPayload: EncryptedPayload,
        status: MessageStatus,
        replyToMessageId: String? = null,
        replyToSenderName: String? = null,
        replyToContent: String? = null,
        messageType: String = "text",
        attachments: List<AttachmentEntity>? = null, // ✅ Принимает список вложений
        uploadProgress: Int = 0,
        messageId: String = UUID.randomUUID().toString()
    ): String {
        // Внутри MessageManager.kt перед созданием MessageEntity:
        val foundUrl = LinkPreviewHelper.extractUrl(content)
        var title: String? = null
        var description: String? = null
        var imageUrl: String? = null

        if (foundUrl != null) {
            // Делаем сетевой запрос за мета-данными сайта
            val preview = LinkPreviewHelper.fetchPreview(foundUrl)
            if (preview != null) {
                title = preview.title
                description = preview.description
                imageUrl = preview.imageUrl
            }
        }

        val payloadJson = Gson().toJson(encryptedPayload)

        // ✅ Создаем сообщение БЕЗ полей медиа (они теперь в AttachmentEntity)
        val message = MessageEntity(
            chatId = chatId,
            messageId = messageId,
            senderId = myId,
            content = content,
            encryptedPayload = payloadJson,
            timestamp = System.currentTimeMillis(),
            status = status,
            isIncoming = false, // ✅ ИСПРАВЛЕНО: исходящее сообщение должно быть false!
            replyToMessageId = replyToMessageId,
            replyToSenderName = replyToSenderName,
            replyToContent = replyToContent,
            messageType = messageType,
            linkTitle = title,
            linkDescription = description,
            linkImageUrl = imageUrl
        )
        dao.insertMessage(message)

        // ✅ Если есть вложения, сохраняем их в отдельную таблицу
        attachments?.let { atts ->
            val attachmentEntities = atts.map { att ->
                AttachmentEntity(
                    messageId = messageId,
                    fileId = att.fileId,
                    encryptedKey = att.encryptedKey,
                    mediaType = att.mediaType,
                    fileName = att.fileName,
                    fileSize = att.fileSize,
                    localFilePath = att.localFilePath,
                    thumbnailBase64 = att.thumbnailBase64,
                    downloadProgress = 0,
                    uploadProgress = uploadProgress
                )
            }
            dao.insertAttachments(attachmentEntities)
        }

        return messageId
    }

    /**
     * Сохраняет входящее расшифрованное сообщение
     */
    suspend fun saveIncomingMessage(
        chatId: String,
        senderId: String,
        content: String,
        timestamp: Long = System.currentTimeMillis(),
        messageId: String,
        replyToMessageId: String? = null,
        replyToSenderName: String? = null,
        replyToContent: String? = null,
        messageType: String = "text",
        attachments: List<MediaAttachment>? = null // ✅ Принимает список вложений
    ): String {

        val foundUrl = LinkPreviewHelper.extractUrl(content)
        var title: String? = null
        var description: String? = null
        var imageUrl: String? = null

        if (foundUrl != null) {
            // Делаем сетевой запрос за мета-данными сайта
            val preview = LinkPreviewHelper.fetchPreview(foundUrl)
            if (preview != null) {
                title = preview.title
                description = preview.description
                imageUrl = preview.imageUrl
            }
        }
        // ✅ Создаем сообщение БЕЗ полей медиа
        val message = MessageEntity(
            chatId = chatId,
            messageId = messageId,
            senderId = senderId,
            content = content,
            encryptedPayload = null,
            timestamp = timestamp,
            status = MessageStatus.DELIVERED,
            isIncoming = true, // ✅ ИСПРАВЛЕНО: входящее сообщение должно быть true
            replyToMessageId = replyToMessageId,
            replyToSenderName = replyToSenderName,
            replyToContent = replyToContent,
            messageType = messageType,
            linkTitle = title,
            linkDescription = description,
            linkImageUrl = imageUrl
        )
        dao.insertMessage(message)

        // ✅ Если есть вложения, сохраняем их в отдельную таблицу
        attachments?.let { atts ->
            val attachmentEntities = atts.map { att ->
                AttachmentEntity(
                    messageId = messageId,
                    fileId = att.fileId,
                    encryptedKey = att.encryptedKey,
                    mediaType = att.mediaType,
                    fileName = att.fileName,
                    fileSize = att.fileSize,
                    thumbnailBase64 = att.thumbnailBase64,
                    localFilePath = null,
                    downloadProgress = 0,
                    uploadProgress = 0
                )
            }
            dao.insertAttachments(attachmentEntities)
        }

        return messageId
    }

    /**
     * Скачивает и расшифровывает медиафайл
     */
    suspend fun downloadAndDecryptMedia(
        context: Context,
        fileId: String,
        encryptedKeyBase64: String,
        mediaType: String,
        fileName: String,
        messageId: String,
        expectedFileSize: Long,
        onProgress: (Int) -> Unit = {}
    ): String? {
        return try {
            val keyBytes = android.util.Base64.decode(encryptedKeyBase64, android.util.Base64.NO_WRAP)
            val aesKey = MediaCrypto.bytesToKey(keyBytes)

            val mediaDir = File(context.getExternalFilesDir(null), "media").apply { if (!exists()) mkdirs() }
            val encryptedFile = File(mediaDir, "$fileId.enc")
            val extension = if (mediaType.startsWith("image/")) "jpg" else "mp4"
            val decryptedFile = File(mediaDir, "$fileId.$extension")

            // Проверяем, не расшифрован ли файл уже
            if (decryptedFile.exists() && decryptedFile.length() > 0) {
                Log.d("MediaManager", "File already decrypted and valid: ${decryptedFile.absolutePath}")
                updateAttachmentLocalPath(messageId, fileId, decryptedFile.absolutePath)
                return decryptedFile.absolutePath
            }

            var attempt = 0
            val maxAttempts = 5
            var downloadSuccess = false

            while (attempt < maxAttempts && !downloadSuccess) {
                attempt++
                Log.d("MediaManager", "Download attempt $attempt/$maxAttempts for $fileId")

                val response = NetworkClient.apiService.downloadMedia(fileId)
                if (!response.isSuccessful || response.body() == null) {
                    Log.w("MediaManager", "Download failed (attempt $attempt). Code: ${response.code()}")
                    delay(1000)
                    continue
                }

                var totalBytes = 0L
                val expectedSize = expectedFileSize

                response.body()!!.byteStream().use { inputStream ->
                    FileOutputStream(encryptedFile).use { outputStream ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int

                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            outputStream.write(buffer, 0, bytesRead)
                            totalBytes += bytesRead

                            if (expectedSize > 0) {
                                val progress = ((totalBytes.toFloat() / expectedSize) * 50).toInt().coerceIn(0, 50)
                                onProgress(progress)
                                // ✅ Обновляем прогресс КОНКРЕТНОГО вложения
                                updateAttachmentDownloadProgress(messageId, fileId, progress)
                            }
                        }
                    }
                }

                // ПРОВЕРКА ЦЕЛОСТНОСТИ
                if (expectedSize > 0 && totalBytes != expectedSize) {
                    Log.w("MediaManager", "⚠️ File incomplete! Expected: $expectedSize, Downloaded: $totalBytes. Deleting and retrying...")
                    encryptedFile.delete()
                    delay(1500)
                    continue
                }

                downloadSuccess = true
                Log.d("MediaManager", "✅ File downloaded successfully: $totalBytes bytes")
            }

            if (!downloadSuccess) {
                Log.e("MediaManager", "❌ Failed to download complete file after $maxAttempts attempts")
                encryptedFile.delete()
                updateAttachmentDownloadProgress(messageId, fileId, -1)
                return null
            }

            // Расшифровка
            onProgress(75)
            updateAttachmentDownloadProgress(messageId, fileId, 75)
            MediaCrypto.decryptFile(encryptedFile, decryptedFile, aesKey)
            onProgress(100)
            updateAttachmentDownloadProgress(messageId, fileId, 100)

            encryptedFile.delete()
            val localPath = decryptedFile.absolutePath

            // ✅ Обновляем путь КОНКРЕТНОГО вложения в БД
            updateAttachmentLocalPath(messageId, fileId, localPath)

            Log.d("MediaManager", "✅ Media decrypted to: $localPath")
            return localPath

        } catch (e: Exception) {
            Log.e("MediaManager", "Failed to download/decrypt media", e)
            updateAttachmentDownloadProgress(messageId, fileId, -1)
            null
        }
    }

    // =========================================================
    // ✅ НОВЫЕ МЕТОДЫ ДЛЯ РАБОТЫ С ВЛОЖЕНИЯМИ (AttachmentEntity)
    // =========================================================

    suspend fun updateAttachmentLocalPath(messageId: String, fileId: String, localPath: String) {
        Log.d("MediaManager", "📝 Updating DB: Setting localFilePath = '$localPath' for attachment fileId = $fileId in message $messageId")
        dao.updateAttachmentLocalPath(messageId, fileId, localPath)
    }

    suspend fun updateAttachmentDownloadProgress(messageId: String, fileId: String, progress: Int) {
        dao.updateAttachmentDownloadProgress(messageId, fileId, progress)
    }

    suspend fun updateAttachmentUploadProgress(messageId: String, fileId: String, progress: Int) {
        dao.updateAttachmentUploadProgress(messageId, fileId, progress)
    }

    // =========================================================
    // ✅ СТАРЫЕ МЕТОДЫ (адаптированные или оставленные для совместимости)
    // =========================================================

    suspend fun getUnreadMessages(chatId: String): List<MessageEntity> {
        return dao.getUnreadMessages(chatId)
    }

    suspend fun getChatIdByMessageId(messageId: String): String? {
        return dao.getChatIdByMessageId(messageId)
    }

    suspend fun updateMessageStatus(chatId: String, messageId: String, status: MessageStatus) {
        dao.updateMessageStatus(chatId, messageId, status)
    }

    suspend fun saveMessagesBatch(messages: List<MessageEntity>) {
        dao.insertMessages(messages)
    }

    suspend fun getChatHistory(chatId: String): List<MessageEntity> {
        return dao.getMessagesForChat(chatId)
    }

    suspend fun getAttachmentsForMessage(messageId: String): List<AttachmentEntity> {
        return dao.getAttachmentsForMessage(messageId) // Измените на имя вашего DAO метода, который возвращает List<AttachmentEntity> по messageId
    }

    suspend fun getMessagePayload(messageId: String): String? {
        return dao.getMessagePayload(messageId) // Метод возвращает поле encryptedPayload (строку JSON) из таблицы сообщений по messageId
    }

    // ✅ Наблюдение за историей чата вместе с вложениями
    fun observeChatHistoryWithAttachments(chatId: String): Flow<List<MessageWithAttachments>> {
        return dao.observeMessagesWithAttachments(chatId)
    }

    // ✅ Массовое сохранение вложений (для syncMessages)
    suspend fun saveAttachmentsBatch(attachments: List<AttachmentEntity>) {
        dao.insertAttachments(attachments)
    }

    suspend fun getMessageById(messageId: String): MessageEntity? {
        return dao.getMessageById(messageId)
    }

    suspend fun updateMessageText(messageId: String, newText: String) {
        dao.updateMessageText(messageId, newText)
    }

    suspend fun deleteMessage(messageId: String) {
        dao.markMessageAsDeleted(messageId)
    }

    suspend fun updateStatus(chatId: String, messageId: String, status: MessageStatus) {
        dao.updateMessageStatus(chatId, messageId, status)
    }

    fun getPendingMessages(chatId: String): Flow<List<MessageEntity>> {
        return dao.getMessagesByStatus(chatId, MessageStatus.SENDING)
    }

    suspend fun getContactById(senderId: String): ContactEntity? {
        return contactDao.getContactById(senderId)
    }

    fun getConversations(): Flow<List<Conversation>> {
        return daoChat.getConversations()
    }

    suspend fun saveContact(id: String, username: String, displayName: String?, avatarUrl: String? = null, isOnline: Boolean) {
        val contact = ContactEntity(
            id = id,
            username = username,
            displayName = displayName,
            avatarUrl = avatarUrl,
            lastSeen = null,
            isOnline = isOnline
        )
        contactDao.insertContact(contact)
    }

    suspend fun getUnreadCount(chatId: String): Int {
        return dao.getUnreadCount(chatId)
    }

    suspend fun markChatAsRead(chatId: String) {
        dao.markChatAsRead(chatId)
    }

    suspend fun updateContactOnlineStatus(userId: String, isOnline: Boolean) {
        Log.d("MessageManager", "🔄 updateContactOnlineStatus: userId=$userId, isOnline=$isOnline")
        daoChat.updateContactOnlineStatus(userId, isOnline)
    }

    suspend fun updateMessagePayload(messageId: String, payload: EncryptedPayload) {
        dao.updateMessagePayload(messageId, Gson().toJson(payload))
    }
}