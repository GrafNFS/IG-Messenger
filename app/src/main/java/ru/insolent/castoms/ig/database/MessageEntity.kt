package ru.insolent.castoms.ig.database

import androidx.room.*
import ru.insolent.castoms.ig.crypto.MediaAttachment

@Entity(
    tableName = "messages",
    // ✅ Оставляем индекс только для chatId, чтобы быстро искать сообщения по чату
    indices = [Index(value = ["chatId"], name = "idx_messages_chatId")]
)
data class MessageEntity(
    @PrimaryKey // ✅ Делаем messageId единственным первичным ключом!
    @ColumnInfo(name = "messageId") val messageId: String,

    @ColumnInfo(name = "chatId") val chatId: String,
    @ColumnInfo(name = "senderId") val senderId: String,
    @ColumnInfo(name = "content") val content: String,
    @ColumnInfo(name = "encryptedPayload") val encryptedPayload: String?,
    @ColumnInfo(name = "timestamp") val timestamp: Long,
    @ColumnInfo(name = "status") val status: MessageStatus,
    @ColumnInfo(name = "isIncoming") val isIncoming: Boolean,

    @ColumnInfo(name = "replyToMessageId") val replyToMessageId: String? = null,
    @ColumnInfo(name = "replyToSenderName") val replyToSenderName: String? = null,
    @ColumnInfo(name = "replyToContent") val replyToContent: String? = null,

    @ColumnInfo(name = "isEdited") val isEdited: Boolean = false,
    @ColumnInfo(name = "isDeleted") val isDeleted: Boolean = false,

    // Тип сообщения теперь может быть "text", "image", "video" или "album" (если файлов > 1)
    @ColumnInfo(name = "messageType") val messageType: String = "text",

    // 🌟 НОВЫЕ ПОЛЯ ДЛЯ ПРЕВЬЮ ССЫЛОК
    @ColumnInfo(name = "linkTitle") val linkTitle: String? = null,
    @ColumnInfo(name = "linkDescription") val linkDescription: String? = null,
    @ColumnInfo(name = "linkImageUrl") val linkImageUrl: String? = null
)

// Статусы сообщения
enum class MessageStatus {
    SENDING,    // Отправляется на сервер
    SENT,       // Доставлено на сервер
    DELIVERED,  // Доставлено собеседнику
    READ,       // Прочитано собеседником
    FAILED      // Ошибка отправки
}