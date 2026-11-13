package ru.insolent.castoms.ig.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.text.TextUtils.isEmpty
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ru.insolent.castoms.ig.ChatActivity
import ru.insolent.castoms.ig.R
import ru.insolent.castoms.ig.crypto.EncryptedPayload
import ru.insolent.castoms.ig.crypto.MessageCryptoManager
import ru.insolent.castoms.ig.network.NetworkClient
import ru.insolent.castoms.ig.utils.MessageManager
import ru.insolent.castoms.ig.utils.SessionManager

class MyFirebaseMessagingService : FirebaseMessagingService() {

    private val TAG = "FCMService"
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Метод вызывается, когда Firebase присылает пуш в фон
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.d(TAG, "📥 Получен фоновый пуш от Firebase!")

        // Проверяем наличие Data-полей
        if (remoteMessage.data.isNotEmpty()) {
            val from = remoteMessage.data["senderId"] ?: return
            val messageId = remoteMessage.data["messageId"] ?: return
            val timestamp = remoteMessage.data["timestamp"]?.toLongOrNull() ?: System.currentTimeMillis()
            val encryptedPayloadJson = remoteMessage.data["encryptedPayload"] ?: return
            val typeMsg = remoteMessage.data["typeMsg"] ?: return

            // Нам нельзя блокировать этот поток, запускаем обработку в корутине
            serviceScope.launch {
                processBackgroundPushMessage(from, messageId, timestamp, encryptedPayloadJson, typeMsg)
            }
        }
    }

    private suspend fun processBackgroundPushMessage(from: String, messageId: String, timestamp: Long, payloadJson: String, typeMsg: String) {
        try {
            val sessionManager = SessionManager(applicationContext)
            val myId = sessionManager.getUserId() ?: return

            // Инициализируем ваши менеджеры
            val cryptoManager = MessageCryptoManager(myId, applicationContext)
            val messageManager = MessageManager(applicationContext)

            if (payloadJson.isNotEmpty()) {
                // 1. Десериализуем зашифрованный payload, пришедший из пуша
                val payload = Gson().fromJson(payloadJson, EncryptedPayload::class.java)

                // 2. 🌟 РАСШИФРОВЫВАЕМ сообщение прямо в фоне, пока телефон спит!
                val messageContent = cryptoManager.decryptMessage(payload, from)
                val attachments = messageContent.attachments

                // 3. Определяем тип контента (наша старая отлаженная логика)
                val messageType = when {
                    attachments?.any { it.mediaType.startsWith("audio") } == true -> "audio"
                    attachments?.any { it.mediaType.startsWith("video") } == true -> "video"
                    !attachments.isNullOrEmpty() -> "image"
                    else -> "text"
                }

                // 4. 🌟 СОХРАНЯЕМ В БД ROOM
                messageManager.saveIncomingMessage(
                    chatId = from,
                    senderId = from,
                    content = messageContent.text,
                    timestamp = timestamp,
                    messageId = messageId,
                    messageType = messageType,
                    attachments = attachments
                )
                Log.d(TAG, "✅ Пуш расшифрован и сохранен в Room в фоне!")


                // 5. Показываем системную плашку уведомления, ЕСЛИ чат с этим пользователем сейчас НЕ открыт на экране
                if (ChatActivity.currentOpenChatId != from) {
                    showNotification(from, messageContent.text.ifEmpty { "Вложение: $messageType" })
                }
            }
            else {
                val notificationText = when {
                    typeMsg == "audio" -> "🎙️ Голосовое сообщение"
                    typeMsg == "video" -> "🎥 Видео"
                    typeMsg == "image" -> "📷 Фото"
                    else -> "Новое сообщение"
                }
                // 5. Показываем системную плашку уведомления, ЕСЛИ чат с этим пользователем сейчас НЕ открыт на экране
                if (ChatActivity.currentOpenChatId != from) {
                    showNotification(from, notificationText)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка расшифровки пуша (возможно, превышен лимит 4KB или сообщение уже получено через сокет)", e)

            // 🛡️ FALLBACK: Если не смогли расшифровать в фоне, всё равно покажем уведомление,
            // чтобы пользователь открыл чат и получил сообщение через сокет.
            if (ChatActivity.currentOpenChatId != from) {
                showNotification(from, "Новое сообщение")
            }
        }
    }

    private suspend fun showNotification(senderId: String, text: String) {
        val messageManager = MessageManager(applicationContext)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "chat_messages_channel"

        // Создаем канал уведомлений (для Android 8.0+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Входящие сообщения", NotificationManager.IMPORTANCE_HIGH)
            notificationManager.createNotificationChannel(channel)
        }

        val contact = messageManager.getContactById(senderId)
        val senderName = when (contact) {
            is ru.insolent.castoms.ig.database.ContactEntity -> contact.displayName ?: contact.username ?: "Неизвестный"
            else -> "Новое сообщение"
        }

        // Настраиваем открытие ChatActivity при тапе по пушу
        val intent = Intent(this, ChatActivity::class.java).apply {
            putExtra("EXTRA_CONTACT_ID", senderId)
            putExtra("EXTRA_CONTACT_NAME", senderName)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, senderId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(senderName)
            .setContentText(text)
            .setAutoCancel(true) // Удалять пуш после клика
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        notificationManager.notify(senderId.hashCode(), notification)
    }

    // Метод для обновления FCM токена на сервере при его изменении
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "🔥 Новый Firebase токен: $token")
        sendTokenToServer(token)
    }

    private fun sendTokenToServer(token: String) {
        val prefs = getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("fcm_token", token).apply()

        val userId = prefs.getString("user_id", null)
        val deviceId = prefs.getString("device_id", null)

        if (userId != null && deviceId != null) {
            serviceScope.launch {
                try {
                    NetworkClient.sendFcmTokenToServer(userId, deviceId, token)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send token to server", e)
                }
            }
        }
    }
}
