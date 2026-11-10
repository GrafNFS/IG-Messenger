package ru.insolent.castoms.ig.model

data class ChatItem(
    val chatId: String, // Уникальный идентификатор чата
    val name: String,
    val lastMessage: String,
    val time: String,
    val avatarResId: Int,
    val isOnline: Boolean = false,
    val isUnread: Boolean = false
)