package ru.insolent.castoms.ig.network

import ru.insolent.castoms.ig.crypto.EncryptedPayload
import android.util.Log
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.emitter.Emitter
import org.json.JSONObject
import java.net.URISyntaxException

object SocketManager {

    private var socket: Socket? = null
    private val TAG = "SocketManager"
    private const val SERVER_URL = "https://messenger.insolent-castoms.ru"

    // Слушатель для входящих сообщений
    var onMessageReceived: ((from: String, payload: EncryptedPayload, messageId: String, timestamp: Long) -> Unit)? = null

    // ✅ НОВЫЙ: Слушатель для подтверждения прочтения (две галочки)
    var onMessageRead: ((messageId: String, readBy: String) -> Unit)? = null
    var onMessageDelivered: ((messageId: String, deliveredBy: String) -> Unit)? = null
    var onUserStatusChanged: ((userId: String, isOnline: Boolean) -> Unit)? = null
    var onTypingIndicator: ((userId: String, chatId: String, isTyping: Boolean) -> Unit)? = null
    // Слушатель действий (редактирование/удаление)
    var onMessageAction: ((from: String, payload: EncryptedPayload, targetMessageId: String?) -> Unit)? = null




    // ✅ НОВЫЙ: Слушатель для оффлайн-доставки (можно использовать для лога)
    var onOfflineMessageReceived: ((count: Int) -> Unit)? = null

    fun connect(userId: String) {
        if (socket?.connected() == true) return

        try {
            val options = IO.Options().apply {
                transports = arrayOf("websocket")
                reconnection = true
                reconnectionAttempts = 5
            }

            socket = IO.socket(SERVER_URL, options)
            socket?.connect()

            socket?.on(Socket.EVENT_CONNECT) {
                Log.d(TAG, "✅ Connected")
                val data = JSONObject().put("userId", userId)
                socket?.emit("join_room", data)
            }

            // Обработка ошибок подключения
            socket?.on(Socket.EVENT_CONNECT_ERROR) { args ->
                Log.e(TAG, "❌ Connection error: ${args.joinToString()}")
            }

            // 📥 При входящем сообщении
            socket?.on("private_message") { args ->
                val data = args[0] as JSONObject
                val from = data.getString("from")
                val payloadJson = data.getJSONObject("payload")

                // ✅ messageId и timestamp теперь на верхнем уровне
                val messageId = data.optString("messageId", "")
                val timestamp = data.optLong("timestamp", System.currentTimeMillis())
                val isOffline = data.optBoolean("isOffline", false)

                Log.d(TAG, " SOCKET: Received message from $from, id=$messageId")

                val encrypted = EncryptedPayload(
                    type = payloadJson.getInt("type"),
                    data = payloadJson.getString("data")
                )

                Log.d(TAG, "📨 Received from $from, msgId: $messageId, offline: $isOffline")

                // Передаём все данные в UI
                onMessageReceived?.invoke(from, encrypted, messageId, timestamp)
            }

            socket?.on("message_delivered") { args ->
                val json = JSONObject(args[0].toString())
                val messageId = json.getString("messageId")
                val readBy = json.getString("deliveredBy")

                Log.d(TAG, "✅ Message $messageId delivery by $readBy")
                onMessageDelivered?.invoke(messageId, readBy)
            }

            // ✅ Обработка подтверждения прочтения (для двух галочек)
            socket?.on("message_read") { args ->
                val json = JSONObject(args[0].toString())
                val messageId = json.getString("messageId")
                val readBy = json.getString("readBy")

                Log.d(TAG, "✅ Message $messageId read by $readBy")
                onMessageRead?.invoke(messageId, readBy)
            }

            // ✅ Обработка пакетной доставки оффлайн-сообщений
            socket?.on("offline_messages_batch") { args ->
                val json = JSONObject(args[0].toString())
                val count = json.optInt("count", 0)
                Log.d(TAG, "📦 Received $count offline messages")
                onOfflineMessageReceived?.invoke(count)
            }

            socket?.on("user_status") { args ->
                val json = JSONObject(args[0].toString())
                val statusUserId = json.getString("userId")
                val isOnline = json.getBoolean("isOnline")

                Log.d(TAG, " User $statusUserId is ${if (isOnline) "online" else "offline"}")
                onUserStatusChanged?.invoke(statusUserId, isOnline)
            }

            socket?.on("user_typing") { args ->
                val json = JSONObject(args[0].toString())
                val typingUserId = json.getString("userId")
                val chatId = json.getString("chatId")
                val isTyping = json.getBoolean("isTyping")

                Log.d(TAG, "✍️ User $typingUserId typing in chat $chatId: $isTyping")
                onTypingIndicator?.invoke(typingUserId, chatId, isTyping)
            }

            socket?.on("message_action") { args ->
                val json = JSONObject(args[0].toString())
                val from = json.getString("from")
                val payloadJson = json.getJSONObject("payload")
                val targetId = json.optString("messageId", null) // ID целевого сообщения

                val payload = EncryptedPayload(
                    type = payloadJson.getInt("type"),
                    data = payloadJson.getString("data")
                )

                Log.d(TAG, "⚡ Received message_action from $from, target: $targetId")
                onMessageAction?.invoke(from, payload, targetId)
            }

        } catch (e: URISyntaxException) {
            Log.e(TAG, "Socket URI error", e)
        }
    }

    // Метод отправки действия
    fun sendMessageAction(chatId: String, payload: EncryptedPayload, targetMessageId: String?): Boolean {
        if (socket?.connected() != true) return false
        val data = JSONObject().apply {
            put("chatId", chatId)
            put("payload", JSONObject().apply {
                put("type", payload.type)
                put("data", payload.data)
            })
            if (targetMessageId != null) put("messageId", targetMessageId)
        }
        socket?.emit("message_action", data)
        return true
    }

    // 📤 Отправка зашифрованного сообщения
    fun sendEncryptedMessage(targetUserId: String, payload: EncryptedPayload, messageId: String, typeMsg: String): Boolean {
        if (socket?.connected() != true) return false

        // ✅ messageId на верхнем уровне, как ждёт сервер
        val data = JSONObject().apply {
            put("to", targetUserId)
            put("typeMsg", typeMsg)
            put("messageId", messageId)  // ✅ Здесь, а не внутри payload!
            put("payload", JSONObject().apply {
                put("type", payload.type)
                put("data", payload.data)
            })
        }
        socket?.emit("private_message", data)
        Log.d(TAG, "📤 Sent message $messageId to $targetUserId typeMsg $typeMsg")
        return true
    }

    // ✅ 1. Подтверждение ДОСТАВКИ на устройство (2 серые галочки)
    fun sendDeliveryReceipt(chatId: String, messageId: String): Boolean {
        if (socket?.connected() != true) {
            Log.w("SocketManager", "Cannot send delivery receipt: socket disconnected")
            return false
        }

        val data = JSONObject().apply {
            put("chatId", chatId)
            put("messageId", messageId)
            put("timestamp", System.currentTimeMillis())
        }
        socket?.emit("delivery_receipt", data)
        Log.d("SocketManager", "📤 Emitted delivery_receipt for $messageId")
        return true
    }

    // ✅ НОВЫЙ: Отправка подтверждения прочтения (для двух галочек)
    fun sendReadReceipt(chatId: String, messageId: String): Boolean {
        if (socket?.connected() != true) return false

        val data = JSONObject().apply {
            put("chatId", chatId)
            put("messageId", messageId)
            put("timestamp", System.currentTimeMillis())
        }
        socket?.emit("read_receipt", data)
        Log.d(TAG, "📤 Sent read receipt for $messageId")
        return true
    }

    // ✅ Метод отправки "начал печатать"
    fun sendTypingStart(chatId: String): Boolean {
        if (socket?.connected() != true) return false

        val data = JSONObject().apply {
            put("chatId", chatId)
        }
        socket?.emit("typing_start", data)
        return true
    }

    // ✅ Метод отправки "перестал печатать"
    fun sendTypingStop(chatId: String): Boolean {
        if (socket?.connected() != true) return false

        val data = JSONObject().apply {
            put("chatId", chatId)
        }
        socket?.emit("typing_stop", data)
        return true
    }

    // ✅ ИСПРАВЛЕНО: Теперь возвращает true когда подключено
    fun isConnected(): Boolean {
        return socket?.connected() == true
    }

    fun disconnect() {
        socket?.disconnect()
        socket = null
    }
}