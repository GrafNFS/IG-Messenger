package ru.insolent.castoms.ig.network

import android.content.Context
import ru.insolent.castoms.ig.crypto.CryptoManager
import ru.insolent.castoms.ig.crypto.EncryptedPayload
import ru.insolent.castoms.ig.utils.SessionManager
import ru.insolent.castoms.ig.workers.PreKeyRefillWorker
import android.util.Log
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.URISyntaxException

object SocketManager {

    private var socket: Socket? = null
    private val TAG = "SocketManager"
    private const val SERVER_URL = "https://messenger.insolent-castoms.ru"

    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Слушатель для входящих сообщений
    var onMessageReceived: ((from: String, payload: EncryptedPayload, messageId: String, timestamp: Long) -> Unit)? = null

    // Слушатель для подтверждения прочтения (две галочки)
    var onMessageRead: ((messageId: String, readBy: String) -> Unit)? = null
    var onMessageDelivered: ((messageId: String, deliveredBy: String) -> Unit)? = null
    var onUserStatusChanged: ((userId: String, isOnline: Boolean) -> Unit)? = null
    var onTypingIndicator: ((userId: String, chatId: String, isTyping: Boolean) -> Unit)? = null
    // Слушатель действий (редактирование/удаление)
    var onMessageAction: ((from: String, payload: EncryptedPayload, targetMessageId: String?) -> Unit)? = null

    // Слушатель для оффлайн-доставки
    var onOfflineMessageReceived: ((count: Int) -> Unit)? = null

    // Срабатывает при каждом успешном (пере)подключении сокета
    var onConnected: (() -> Unit)? = null

    /**
     * Инициализация контекстом приложения. Вызывать ОДИН РАЗ до connect().
     */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun connect(userId: String) {
        if (socket?.connected() == true) return

        if (!::appContext.isInitialized) {
            Log.e(TAG, "❌ SocketManager.init(context) not called before connect()!")
            return
        }

        // ✅ Токен обязателен: сервер отклоняет handshake без валидного JWT
        val token = SessionManager(appContext).getToken()
        if (token.isNullOrEmpty()) {
            Log.e(TAG, "❌ Cannot connect: no auth token. Login first.")
            return
        }

        try {
            val options = IO.Options().apply {
                transports = arrayOf("websocket")
                reconnection = true
                reconnectionAttempts = 10
                // ✅ Аутентификация сокета — сервер проверяет JWT на этапе handshake
                auth = mapOf("token" to token)
            }

            socket = IO.socket(SERVER_URL, options)
            socket?.connect()

            socket?.on(Socket.EVENT_CONNECT) {
                Log.d(TAG, "✅ Connected")
                // join_room больше не принимает userId — личность сервер берёт из токена
                socket?.emit("join_room", JSONObject())
                // Проверяем/пополняем запас one-time prekeys
                triggerPreKeyMaintenance(userId)
                // Уведомляем подписчиков (сервис запускает глобальный догон сообщений)
                onConnected?.invoke()
            }

            // Обработка ошибок подключения
            socket?.on(Socket.EVENT_CONNECT_ERROR) { args ->
                val errorMsg = args.joinToString()
                Log.e(TAG, "❌ Connection error: $errorMsg")

                // "unauthorized" — токен невалиден/протух. Реконнект бессмыслен,
                // он сам не починится — рвём сокет, чтобы не гонять попытки.
                if (errorMsg.contains("unauthorized", ignoreCase = true)) {
                    Log.w(TAG, "⚠️ Socket unauthorized — token invalid or expired. Disconnecting.")
                    socket?.disconnect()
                }
            }

            // 📥 При входящем сообщении
            socket?.on("private_message") { args ->
                val data = args[0] as JSONObject
                val from = data.getString("from")
                val payloadJson = data.getJSONObject("payload")

                val messageId = data.optString("messageId", "")
                val timestamp = data.optLong("timestamp", System.currentTimeMillis())
                val isOffline = data.optBoolean("isOffline", false)

                Log.d(TAG, "📨 Received from $from, msgId: $messageId, offline: $isOffline")

                val encrypted = EncryptedPayload(
                    type = payloadJson.getInt("type"),
                    data = payloadJson.getString("data")
                )

                onMessageReceived?.invoke(from, encrypted, messageId, timestamp)
            }

            socket?.on("message_delivered") { args ->
                val json = JSONObject(args[0].toString())
                val messageId = json.getString("messageId")
                val readBy = json.getString("deliveredBy")

                Log.d(TAG, "✅ Message $messageId delivered by $readBy")
                onMessageDelivered?.invoke(messageId, readBy)
            }

            socket?.on("message_read") { args ->
                val json = JSONObject(args[0].toString())
                val messageId = json.getString("messageId")
                val readBy = json.getString("readBy")

                Log.d(TAG, "✅ Message $messageId read by $readBy")
                onMessageRead?.invoke(messageId, readBy)
            }

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
                val targetId = json.optString("messageId", null)

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

    /** Ротация OTK при каждом подключении + страховочный периодический воркер */
    private fun triggerPreKeyMaintenance(userId: String) {
        scope.launch {
            try {
                CryptoManager(appContext).refillOneTimePreKeysIfNeeded(userId)
            } catch (e: Exception) {
                Log.e(TAG, "PreKey maintenance failed", e)
            }
        }
        PreKeyRefillWorker.schedulePeriodic(appContext)
    }

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

    fun sendEncryptedMessage(targetUserId: String, payload: EncryptedPayload, messageId: String, typeMsg: String): Boolean {
        if (socket?.connected() != true) return false

        val data = JSONObject().apply {
            put("to", targetUserId)
            put("typeMsg", typeMsg)
            put("messageId", messageId)
            put("payload", JSONObject().apply {
                put("type", payload.type)
                put("data", payload.data)
            })
        }
        socket?.emit("private_message", data)
        Log.d(TAG, "📤 Sent message $messageId to $targetUserId typeMsg $typeMsg")
        return true
    }

    fun sendDeliveryReceipt(chatId: String, messageId: String): Boolean {
        if (socket?.connected() != true) {
            Log.w(TAG, "Cannot send delivery receipt: socket disconnected")
            return false
        }

        val data = JSONObject().apply {
            put("chatId", chatId)
            put("messageId", messageId)
            put("timestamp", System.currentTimeMillis())
        }
        socket?.emit("delivery_receipt", data)
        Log.d(TAG, "📤 Emitted delivery_receipt for $messageId")
        return true
    }

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

    fun sendTypingStart(chatId: String): Boolean {
        if (socket?.connected() != true) return false
        socket?.emit("typing_start", JSONObject().put("chatId", chatId))
        return true
    }

    fun sendTypingStop(chatId: String): Boolean {
        if (socket?.connected() != true) return false
        socket?.emit("typing_stop", JSONObject().put("chatId", chatId))
        return true
    }

    fun isConnected(): Boolean = socket?.connected() == true

    fun disconnect() {
        socket?.disconnect()
        socket = null
    }
}
