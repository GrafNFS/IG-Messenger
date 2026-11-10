package ru.insolent.castoms.ig.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    /**
     * Получает список всех чатов с последними сообщениями
     */
    @Query("""
        SELECT 
            m.chatId as contactId,
            COALESCE(c.displayName, c.username, m.chatId) as contactName,
            m.content as lastMessage,
            m.messageType as messageType,
            m.timestamp as lastMessageTime,
            (SELECT COUNT(*) FROM messages WHERE chatId = m.chatId AND isIncoming = 1 AND status != 'READ') as unreadCount,
            COALESCE(c.isOnline, 0) as isOnline 
        FROM messages m
        LEFT JOIN contacts c ON m.chatId = c.id
        WHERE m.messageId IN (
            SELECT messageId FROM messages m2 
            WHERE m2.chatId = m.chatId 
            ORDER BY m2.timestamp DESC LIMIT 1
        )
        ORDER BY m.timestamp DESC
    """)
    fun getConversations(): Flow<List<Conversation>>

    /**
     * Считает количество непрочитанных сообщений в чате
     */
    @Query("""
        SELECT COUNT(*) FROM messages 
        WHERE chatId = :chatId AND isIncoming = 1 AND status = 'DELIVERED'
    """)
    suspend fun getUnreadCount(chatId: String): Int

    @Query("UPDATE contacts SET isOnline = :isOnline WHERE id = :userId")
    suspend fun updateContactOnlineStatus(userId: String, isOnline: Boolean)
}