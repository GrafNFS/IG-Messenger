package ru.insolent.castoms.ig.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    // === Вставка/Обновление ===
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessages(messages: List<MessageEntity>)

    // ✅ Для одиночной вставки (при получении): REPLACE, чтобы обновлять статус
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    // === Чтение ===
    @Query("""
        SELECT * FROM messages 
        WHERE chatId = :chatId 
        ORDER BY timestamp ASC
    """)
    suspend fun getMessagesForChat(chatId: String): List<MessageEntity>

    @Query("""
        SELECT * FROM messages 
        WHERE chatId = :chatId AND status = :status
        ORDER BY timestamp ASC
    """)
    fun getMessagesByStatus(chatId: String, status: MessageStatus): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE chatId = :chatId AND messageId = :messageId")
    suspend fun getMessage(chatId: String, messageId: String): MessageEntity?

    // === Обновление статуса ===
    @Query("""
        UPDATE messages 
        SET status = :newStatus 
        WHERE chatId = :chatId AND messageId = :messageId
    """)
    suspend fun updateMessageStatus(chatId: String, messageId: String, newStatus: MessageStatus)

    // === Удаление ===
    @Query("DELETE FROM messages WHERE chatId = :chatId AND messageId = :messageId")
    suspend fun deleteMessage(chatId: String, messageId: String)

    @Query("DELETE FROM messages WHERE chatId = :chatId")
    suspend fun deleteAllMessagesForChat(chatId: String)

    // ✅ 1. Считаем непрочитанные входящие сообщения для конкретного чата
    @Query("SELECT COUNT(*) FROM messages WHERE chatId = :chatId AND isIncoming = 1 AND status != 'READ' and isDeleted = 0")
    suspend fun getUnreadCount(chatId: String): Int

    // ✅ 1. Получить все непрочитанные входящие сообщения в чате
    @Query("SELECT * FROM messages WHERE chatId = :chatId AND isIncoming = 1 AND status != 'READ' and isDeleted = 0")
    suspend fun getUnreadMessages(chatId: String): List<MessageEntity>

    // ✅ 2. Найти chatId по messageId (нужно для глобального обновления статуса)
    @Query("SELECT chatId FROM messages WHERE messageId = :messageId LIMIT 1")
    suspend fun getChatIdByMessageId(messageId: String): String?

    // ✅ 2. Помечаем все входящие сообщения в чате как прочитанные
    @Query("UPDATE messages SET status = 'READ' WHERE chatId = :chatId AND isIncoming = 1")
    suspend fun markChatAsRead(chatId: String)

    @Query("SELECT * FROM messages WHERE messageId = :messageId LIMIT 1")
    suspend fun getMessageById(messageId: String): MessageEntity?

    @Query("UPDATE messages SET content = :newText, isEdited = 1 WHERE messageId = :messageId")
    suspend fun updateMessageText(messageId: String, newText: String)

    @Query("UPDATE messages SET isDeleted = 1, content = '', encryptedPayload = '' WHERE messageId = :messageId")
    suspend fun markMessageAsDeleted(messageId: String)

    @Query("UPDATE messages SET encryptedPayload = :payload WHERE messageId = :messageId")
    suspend fun updateMessagePayload(messageId: String, payload: String)


    // ✅ Массовая вставка вложений (удобно при отправке нескольких фото)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAttachments(attachments: List<AttachmentEntity>)
    @Query("UPDATE attachments SET localFilePath = :localPath WHERE messageId = :messageId AND fileId = :fileId")
    suspend fun updateAttachmentLocalPath(messageId: String, fileId: String, localPath: String)

    @Query("UPDATE attachments SET downloadProgress = :progress WHERE messageId = :messageId AND fileId = :fileId")
    suspend fun updateAttachmentDownloadProgress(messageId: String, fileId: String, progress: Int)

    @Query("UPDATE attachments SET uploadProgress = :progress WHERE messageId = :messageId AND fileId = :fileId")
    suspend fun updateAttachmentUploadProgress(messageId: String, fileId: String, progress: Int)
    @Transaction
    @Query("SELECT * FROM messages WHERE chatId = :chatId  and isDeleted = 0 ORDER BY timestamp ASC")
    fun observeMessagesWithAttachments(chatId: String): Flow<List<MessageWithAttachments>>

    // 1. Получение списка вложений по ID сообщения, чтобы воркер узнал все fileId
    @Query("SELECT * FROM attachments WHERE messageId = :messageId") // замените attachments_table на имя вашей таблицы вложений
    suspend fun getAttachmentsForMessage(messageId: String): List<AttachmentEntity>

    // 2. Получение JSON-строки зашифрованного payload, чтобы отправить его в сокет
    @Query("SELECT encryptedPayload FROM messages WHERE messageId = :messageId") // замените messages_table на имя вашей таблицы сообщений
    suspend fun getMessagePayload(messageId: String): String?
}

// Data class для удобного получения сообщения + его файлов
data class MessageWithAttachments(
    @Embedded val message: MessageEntity,
    @Relation(
        parentColumn = "messageId",
        entityColumn = "messageId"
    )
    val attachments: List<AttachmentEntity>
)