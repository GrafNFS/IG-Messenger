package ru.insolent.castoms.ig.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import ru.insolent.castoms.ig.ChatActivity
import ru.insolent.castoms.ig.MainActivity
import ru.insolent.castoms.ig.R
import ru.insolent.castoms.ig.crypto.EncryptedPayload
import ru.insolent.castoms.ig.crypto.MessageCryptoManager
import ru.insolent.castoms.ig.database.MessageStatus
import ru.insolent.castoms.ig.network.NetworkClient
import ru.insolent.castoms.ig.network.SocketManager
import ru.insolent.castoms.ig.utils.MessageManager
import ru.insolent.castoms.ig.utils.SessionManager
import java.io.File

class MessageReceiverService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val TAG = "MessageReceiverService"
    private val NOTIFICATION_CHANNEL_ID = "chat_push_channel"

    private lateinit var cryptoManager: MessageCryptoManager
    private lateinit var messageManager: MessageManager
    private lateinit var sessionManager: SessionManager

    private val downloadingFiles = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    // 🌟 1. Добавляем Binder для связи службы с жизненным циклом приложения
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): MessageReceiverService = this@MessageReceiverService
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.d(TAG, "Service Bound (Приложение открыто)")
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        sessionManager = SessionManager(applicationContext)
        val myId = sessionManager.getUserId() ?: ""
        cryptoManager = MessageCryptoManager(myId, applicationContext)
        messageManager = MessageManager(applicationContext)

        Log.d(TAG, "Service created, setting up global socket listeners...")

        // ✅ 1. Слушаем входящие сообщения
        SocketManager.onMessageReceived = { from, payload, messageId, timestamp ->
            serviceScope.launch { handleIncomingMessage(from, payload, messageId, timestamp) }
        }

        // ✅ 2. Слушаем доставку
        SocketManager.onMessageDelivered = { messageId, deliveredBy ->
            serviceScope.launch {
                val chatId = messageManager.getChatIdByMessageId(messageId)
                if (chatId != null) {
                    messageManager.updateMessageStatus(chatId, messageId, MessageStatus.DELIVERED)
                }
            }
        }

        // ✅ 3. Слушаем прочтение
        SocketManager.onMessageRead = { messageId, readBy ->
            serviceScope.launch {
                val chatId = messageManager.getChatIdByMessageId(messageId)
                if (chatId != null) {
                    messageManager.updateMessageStatus(chatId, messageId, MessageStatus.READ)
                }
            }
        }

        // ✅ 4. Слушаем действия (редактирование/удаление)
        SocketManager.onMessageAction = { from, payload, targetMessageId ->
            serviceScope.launch {
                try {
                    val actionContent = cryptoManager.decryptMessage(payload, from)
                    if (targetMessageId != null && actionContent.action != null) {
                        when (actionContent.action) {
                            "edit" -> messageManager.updateMessageText(targetMessageId, actionContent.text)
                            "delete" -> messageManager.deleteMessage(targetMessageId)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to process message_action", e)
                }
            }
        }
    }

    private suspend fun handleIncomingMessage(from: String, payload: EncryptedPayload, messageId: String, timestamp: Long) {
        try {
            val messageContent = cryptoManager.decryptMessage(payload, from)
            val attachments = messageContent.attachments

            val messageType = when {
                messageContent?.attachments?.any { it.mediaType.startsWith("audio") } == true -> "audio"
                messageContent.attachments?.any { it.mediaType == "image/gif" } == true -> "gif"
                messageContent.attachments?.any { it.mediaType.startsWith("video") } == true -> "video"
                !messageContent.attachments.isNullOrEmpty() -> "image"
                else -> "text"
            }

            // Сохраняем в БД Room
            messageManager.saveIncomingMessage(
                chatId = from, senderId = from, content = messageContent.text,
                timestamp = timestamp, messageId = messageId, messageType = messageType, attachments = attachments
            )

            // Фоновое скачивание медиа файлов
            attachments?.forEach { attachment ->
                val fileId = attachment.fileId
                if (!downloadingFiles.add(fileId)) return@forEach

                serviceScope.launch {
                    try {
                        val localPath = messageManager.downloadAndDecryptMedia(
                            context = this@MessageReceiverService, fileId = fileId,
                            encryptedKeyBase64 = attachment.encryptedKey, mediaType = attachment.mediaType ?: "image/jpeg",
                            fileName = attachment.fileName ?: "file", messageId = messageId, expectedFileSize = attachment.fileSize ?: 0L,
                            onProgress = {}
                        )

                        if (localPath != null) {
                            messageManager.updateAttachmentLocalPath(messageId, fileId, localPath)
                            NetworkClient.apiService.deleteMedia(fileId)
                        }
                    } finally {
                        downloadingFiles.remove(fileId)
                    }
                }
            }

            SocketManager.sendDeliveryReceipt(from, messageId)

            // Показываем обычное пуш-уведомление через NotificationManager, только если чат закрыт
            if (ChatActivity.currentOpenChatId != from) {
                val contact = messageManager.getContactById(from)
                val notificationText = when {
                    messageContent.text.isNotEmpty() -> messageContent.text
                    messageType == "audio" -> "🎙️ Голосовое сообщение"
                    messageType == "video" -> "🎥 Видео"
                    messageType == "image" -> "📷 Фото"
                    else -> "Новое сообщение"
                }
                showNewMessageNotification(messageId, contact, notificationText, from)
            }

        } catch (e: org.signal.libsignal.protocol.DuplicateMessageException) {
            Log.w(TAG, "Duplicate message bypassed")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process message", e)
        }
    }

    private fun showNewMessageNotification(messageId: String, contact: Any?, messageText: String, senderId: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, "Сообщения", NotificationManager.IMPORTANCE_HIGH)
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(this, ChatActivity::class.java).apply {
            putExtra("EXTRA_CONTACT_ID", senderId)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, messageId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val senderName = when (contact) {
            is ru.insolent.castoms.ig.database.ContactEntity -> contact.displayName ?: contact.username ?: "Собеседник"
            else -> "Новое сообщение"
        }

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(senderName)
            .setContentText(messageText)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(messageId.hashCode(), notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed, cleaning up listeners...")
        serviceScope.cancel()
        SocketManager.onMessageReceived = null
        SocketManager.onMessageDelivered = null
        SocketManager.onMessageRead = null
        SocketManager.onMessageAction = null
    }
}
