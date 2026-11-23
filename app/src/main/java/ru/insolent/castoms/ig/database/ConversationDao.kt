package ru.insolent.castoms.ig.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    /**
     * Чаты с сообщениями + пустые ГРУППЫ (группа = псевдо-контакт isGroup=1).
     * Для входящих сообщений в группах превью с префиксом «Имя: ».
     */
    @Query("""
        SELECT 
            m.chatId as contactId,
            COALESCE(c.displayName, c.username, m.chatId) as contactName,
            CASE 
                WHEN c.isGroup = 1 AND m.isIncoming = 1 AND m.senderName IS NOT NULL 
                THEN m.senderName || ': ' || m.content 
                ELSE m.content 
            END as lastMessage,
            m.messageType as messageType,
            m.timestamp as lastMessageTime,
            (SELECT COUNT(*) FROM messages WHERE chatId = m.chatId AND isIncoming = 1 AND status != 'READ') as unreadCount,
            c.avatarUrl as avatarUrl,
            COALESCE(c.isOnline, 0) as isOnline 
        FROM messages m
        LEFT JOIN contacts c ON m.chatId = c.id
        WHERE m.messageId IN (
            SELECT messageId FROM messages m2 
            WHERE m2.chatId = m.chatId 
            ORDER BY m2.timestamp DESC LIMIT 1
        )
        UNION ALL
        SELECT
            c.id as contactId,
            COALESCE(c.displayName, c.username, c.id) as contactName,
            NULL as lastMessage,
            NULL as messageType,
            0 as lastMessageTime,
            0 as unreadCount,
            c.avatarUrl as avatarUrl,
            COALESCE(c.isOnline, 0) as isOnline
        FROM contacts c
        WHERE c.isGroup = 1
          AND NOT EXISTS (SELECT 1 FROM messages msg WHERE msg.chatId = c.id)
        ORDER BY lastMessageTime DESC
    """)
    fun getConversations(): Flow<List<Conversation>>

    @Query("""
        SELECT COUNT(*) FROM messages 
        WHERE chatId = :chatId AND isIncoming = 1 AND status = 'DELIVERED'
    """)
    suspend fun getUnreadCount(chatId: String): Int

    @Query("UPDATE contacts SET isOnline = :isOnline WHERE id = :userId")
    suspend fun updateContactOnlineStatus(userId: String, isOnline: Boolean)
}
