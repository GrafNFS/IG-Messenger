package ru.insolent.castoms.ig.database

import androidx.room.*

// Данные для отображения в списке чатов
data class Conversation(
    val contactId: String,
    val contactName: String,
    val lastMessage: String?,
    val messageType: String?,
    val lastMessageTime: Long?,
    val unreadCount: Int,
    val avatarUrl: String?,
    val isOnline: Boolean = false
)