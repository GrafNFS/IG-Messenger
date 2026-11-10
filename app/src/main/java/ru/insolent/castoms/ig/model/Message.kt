package ru.insolent.castoms.ig.model

import ru.insolent.castoms.ig.database.MessageStatus

data class Message(
    val messageId: String,
    val text: String,
    val isSentByMe: Boolean, // true - мое, false - собеседника
    val time: String,
    val status: MessageStatus
)