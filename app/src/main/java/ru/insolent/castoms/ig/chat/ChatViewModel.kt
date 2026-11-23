package ru.insolent.castoms.ig.chat

import android.app.Application
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.insolent.castoms.ig.crypto.EncryptedPayload
import ru.insolent.castoms.ig.crypto.MediaAttachment
import ru.insolent.castoms.ig.crypto.MediaCrypto
import ru.insolent.castoms.ig.crypto.MessageContent
import ru.insolent.castoms.ig.crypto.MessageCryptoManager
import ru.insolent.castoms.ig.database.AttachmentEntity
import ru.insolent.castoms.ig.database.MessageEntity
import ru.insolent.castoms.ig.database.MessageStatus
import ru.insolent.castoms.ig.database.MessageWithAttachments
import ru.insolent.castoms.ig.network.GroupPayload
import ru.insolent.castoms.ig.network.NetworkClient
import ru.insolent.castoms.ig.network.SocketManager
import ru.insolent.castoms.ig.utils.GroupManager
import ru.insolent.castoms.ig.workers.MediaUploadWorker
import ru.insolent.castoms.ig.utils.ImageHelper
import ru.insolent.castoms.ig.utils.MessageManager
import ru.insolent.castoms.ig.utils.MessageSyncer
import ru.insolent.castoms.ig.utils.SessionManager
import ru.insolent.castoms.ig.utils.VideoHelper
import java.io.File
import java.util.UUID

/**
 * ViewModel чата. Забирает из ChatActivity всю бизнес-логику:
 * шифрование, отправку, синхронизацию, статусы, тайпинг.
 *
 * 🌟 ГОТОВНОСТЬ К ГРУППАМ: вся логика оперирует chatId. Сейчас для личных
 * чатов chatId = id собеседника. Для групп chatId = id группы — ViewModel
 * менять не придётся (поменяется только источник session/keys и серверная
 * часть доставки).
 */
@OptIn(FlowPreview::class)
class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val context = app.applicationContext
    private val sessionManager = SessionManager(context)
    private val messageManager = MessageManager(context)

    /** id текущего пользователя */
    val myId: String = sessionManager.getUserId() ?: ""

    // Инициализируется setup() из Activity
    private var chatId: String = ""
    private var contactName: String = ""

    private lateinit var cryptoManager: MessageCryptoManager
    private lateinit var groupManager: GroupManager
    private var isGroup = false

    // === Состояние для UI ===
    private val _sessionReady = MutableStateFlow(false)
    val sessionReady: StateFlow<Boolean> = _sessionReady

    private val _peerOnline = MutableStateFlow<Boolean?>(null)
    val peerOnline: StateFlow<Boolean?> = _peerOnline

    private val _peerTyping = MutableStateFlow(false)
    val peerTyping: StateFlow<Boolean> = _peerTyping

    private val groupTypingIds = mutableSetOf<String>()
    private val groupTypingJobs = mutableMapOf<String, kotlinx.coroutines.Job>()
    private val _groupTypingNames = MutableStateFlow<List<String>>(emptyList())
    val groupTypingNames: StateFlow<List<String>> = _groupTypingNames

    /** Сообщения чата (Room Flow) */
    val messages: Flow<List<MessageWithAttachments>> by lazy {
        messageManager.observeChatHistoryWithAttachments(chatId)
    }

    private var typingStartSent = false
    private var typingStopJob: kotlinx.coroutines.Job? = null

    fun setup(chatId: String, contactName: String, isGroup: Boolean = false) {
        this.chatId = chatId
        this.contactName = contactName
        this.isGroup = isGroup
        this.cryptoManager = MessageCryptoManager(myId, context)
        this.groupManager = GroupManager(context)
    }

    // ==================== ГРУППЫ: pairwise-шифрование ====================

    /** Сессия с участником, с ленивой инициализацией по key bundle */
    private suspend fun encryptWithLazySession(content: MessageContent, remoteUserId: String): EncryptedPayload {
        return try {
            cryptoManager.encryptMessage(content, remoteUserId)
        } catch (e: Exception) {
            val needInit = e is org.signal.libsignal.protocol.NoSessionException ||
                    (e is IllegalStateException && e.message?.contains("Session not ready") == true)
            if (!needInit) throw e
            val bundleResp = NetworkClient.apiService.getKeysBundle(remoteUserId)
            val bundle = bundleResp.body() ?: throw e
            cryptoManager.initializeSession(remoteUserId, bundle)
            cryptoManager.encryptMessage(content, remoteUserId)
        }
    }

    /** Шифруем контент ОТДЕЛЬНО для каждого участника группы (вариант А) */
    private suspend fun buildGroupPayloads(content: MessageContent): List<GroupPayload> {
        val members = groupManager.getMembers(chatId).filter { it.userId != myId }
        return members.map { m ->
            val p = encryptWithLazySession(content, m.userId)
            GroupPayload(m.userId, p.type, p.data)
        }
    }

    // ==================== Сессия и ключи ====================

    fun initSession() {
        if (chatId.isEmpty()) return
        viewModelScope.launch {
            try {
                if (isGroup) {
                    // Группа: подтягиваем состав, участники сохраняются как контакты.
                    // Сессии строятся лениво — при первой отправке/дешифровке.
                    groupManager.getMembers(chatId)
                    _sessionReady.value = true
                    return@launch
                }
                val response = NetworkClient.apiService.getKeysBundle(chatId)
                if (response.isSuccessful && response.body() != null) {
                    messageManager.saveContact(
                        id = chatId, username = contactName,
                        displayName = contactName, isOnline = _peerOnline.value ?: false
                    )
                    cryptoManager.initializeSession(chatId, response.body()!!)
                    _sessionReady.value = true
                } else {
                    Log.e(TAG, "Key bundle empty for $chatId")
                }
            } catch (e: Exception) {
                Log.e(TAG, "initSession failed", e)
            }
        }
    }

    // ==================== Отправка ====================

    suspend fun sendText(
        text: String,
        replyToMessageId: String? = null,
        replyToSenderName: String? = null,
        replyToContent: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val content = MessageContent(
                text = text,
                replyToMessageId = replyToMessageId,
                replyToSenderName = replyToSenderName,
                replyToContent = replyToContent
            )
            val messageId: String

            if (isGroup) {
                // Шифруем для каждого участника и храним контейнер — тогда ретрай
                // сможет переотправить без повторного шифрования
                val payloads = buildGroupPayloads(content)
                val container = com.google.gson.Gson().toJson(mapOf("payloads" to payloads))
                // saveOutgoingMessage принимает EncryptedPayload: контейнер кладём в data,
                // type=-1 — маркер группового сообщения (единый формат с медиа в группах).
                messageId = messageManager.saveOutgoingMessage(
                    chatId = chatId, content = text, myId = myId,
                    encryptedPayload = EncryptedPayload(type = -1, data = container), status = MessageStatus.SENDING,
                    replyToMessageId = replyToMessageId,
                    replyToSenderName = replyToSenderName,
                    replyToContent = replyToContent
                )
                val sent = SocketManager.sendGroupMessage(chatId, payloads, messageId, "text")
                messageManager.updateStatus(chatId, messageId, if (sent) MessageStatus.SENT else MessageStatus.FAILED)
                return@withContext sent
            }

            val encrypted = cryptoManager.encryptMessage(content, chatId)

            messageId = messageManager.saveOutgoingMessage(
                chatId = chatId,
                content = text,
                myId = myId,
                encryptedPayload = encrypted,
                status = MessageStatus.SENDING,
                replyToMessageId = replyToMessageId,
                replyToSenderName = replyToSenderName,
                replyToContent = replyToContent
            )

            val sent = SocketManager.sendEncryptedMessage(chatId, encrypted, messageId, "text")
            messageManager.updateStatus(chatId, messageId, if (sent) MessageStatus.SENT else MessageStatus.FAILED)
            sent
        } catch (e: Exception) {
            Log.e(TAG, "sendText failed", e)
            false
        }
    }

    suspend fun sendEdit(messageId: String, text: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val actionContent = MessageContent(text = text, action = "edit", targetMessageId = messageId)
            val encrypted = cryptoManager.encryptMessage(actionContent, chatId)
            SocketManager.sendMessageAction(chatId, encrypted, messageId)
            messageManager.updateMessageText(messageId, text)
            true
        } catch (e: Exception) {
            Log.e(TAG, "sendEdit failed", e)
            false
        }
    }

    suspend fun deleteMessage(message: MessageEntity) = withContext(Dispatchers.IO) {
        try {
            if (message.status != MessageStatus.FAILED) {
                val actionContent = MessageContent(action = "delete", targetMessageId = message.messageId)
                val encrypted = cryptoManager.encryptMessage(actionContent, chatId)
                SocketManager.sendMessageAction(chatId, encrypted, message.messageId)
            }
            messageManager.deleteMessage(message.messageId)
        } catch (e: Exception) {
            Log.e(TAG, "deleteMessage failed", e)
        }
    }

    suspend fun retryMessage(message: MessageEntity) = withContext(Dispatchers.IO) {
        try {
            val payload = Gson().fromJson(message.encryptedPayload, EncryptedPayload::class.java)
            val sent = SocketManager.sendEncryptedMessage(chatId, payload, message.messageId, "text")
            messageManager.updateStatus(chatId, message.messageId, if (sent) MessageStatus.SENT else MessageStatus.FAILED)
        } catch (e: Exception) {
            Log.e(TAG, "retryMessage failed", e)
        }
    }

    suspend fun getMessageById(messageId: String) =
        withContext(Dispatchers.IO) { messageManager.getMessageById(messageId) }

    // ==================== Медиа ====================

    /** Отправка выбранных фото/видео одним сообщением (логика из processSelectedMedia) */
    suspend fun sendMedia(filePaths: List<String>, caption: String = ""): Boolean =
        withContext(Dispatchers.IO) {
            val messageId = UUID.randomUUID().toString()
            val attachments = mutableListOf<MediaAttachment>()
            val originalPaths = mutableListOf<String>()

            try {
                filePaths.forEach { tempPath ->
                    val fileId = UUID.randomUUID().toString()
                    val tempEncryptedFile = File(context.cacheDir, "$fileId.enc")
                    val originalFile = File(tempPath)
                    if (!originalFile.exists()) return@forEach

                    val isVideo = originalFile.name.endsWith(".mp4")
                    val isGif = originalFile.name.endsWith(".gif")
                    val extension = if (isGif) "gif" else if (isVideo) "mp4" else "jpg"
                    val localCacheFile = File(context.cacheDir, "$fileId.$extension")

                    if (isVideo) {
                        val fileUri = Uri.fromFile(originalFile)
                        VideoHelper.compressVideo(context, fileUri, localCacheFile)
                        originalFile.delete()
                    } else {
                        originalFile.renameTo(localCacheFile)
                    }
                    originalPaths.add(localCacheFile.absolutePath)

                    // Шифрование
                    val aesKey = MediaCrypto.generateAESKey()
                    MediaCrypto.encryptFile(localCacheFile, tempEncryptedFile, aesKey)
                    val aesKeyBase64 = Base64.encodeToString(MediaCrypto.keyToBytes(aesKey), Base64.NO_WRAP)

                    val localFileUri = Uri.fromFile(localCacheFile)
                    val thumbnailBase64 = if (isVideo || isGif) {
                        VideoHelper.generateVideoThumbnail(context, localFileUri)
                    } else {
                        ImageHelper.generateThumbnailBase64(context, localFileUri)
                    }

                    attachments.add(
                        MediaAttachment(
                            fileId = fileId,
                            encryptedKey = aesKeyBase64,
                            mediaType = when {
                                isGif -> "image/gif"
                                isVideo -> "video/mp4"
                                else -> "image/jpeg"
                            },
                            fileName = if (isGif) "animation.gif" else if (isVideo) "video.mp4" else "photo.jpg",
                            fileSize = tempEncryptedFile.length(),
                            thumbnailBase64 = thumbnailBase64
                        )
                    )
                }

                val messageContent = MessageContent(text = caption, attachments = attachments)

                // 🌟 Группа: шифруем контент для каждого участника, сохраняем контейнер
                //    {payloads:[...]} — MediaUploadWorker определит формат и пошлёт group_message
                val encryptedPayload: EncryptedPayload = if (isGroup) {
                    val payloads = buildGroupPayloads(messageContent)
                    EncryptedPayload(
                        type = -1, // маркер: не настоящий ciphertext
                        data = com.google.gson.Gson().toJson(mapOf("payloads" to payloads))
                    )
                } else {
                    cryptoManager.encryptMessage(messageContent, chatId)
                }

                val messageType = when {
                    attachments.any { it.mediaType == "image/gif" } -> "gif"
                    attachments.any { it.mediaType.startsWith("video") } -> "video"
                    else -> "image"
                }

                val uiAttachments = attachments.mapIndexed { index, att ->
                    AttachmentEntity(
                        messageId = messageId,
                        fileId = att.fileId,
                        encryptedKey = att.encryptedKey,
                        mediaType = att.mediaType,
                        fileName = att.fileName,
                        fileSize = att.fileSize,
                        thumbnailBase64 = att.thumbnailBase64,
                        localFilePath = originalPaths[index],
                        downloadProgress = 0,
                        uploadProgress = 0
                    )
                }

                messageManager.saveOutgoingMessage(
                    chatId = chatId,
                    content = caption,
                    myId = myId,
                    encryptedPayload = encryptedPayload,
                    status = MessageStatus.SENDING,
                    messageType = messageType,
                    attachments = uiAttachments,
                    uploadProgress = 0,
                    messageId = messageId
                )

                val inputData = Data.Builder()
                    .putString("messageId", messageId)
                    .putString("contactId", chatId)
                    .putString("typeMsg", messageType)
                    .build()

                val uploadWorkRequest = OneTimeWorkRequestBuilder<MediaUploadWorker>()
                    .setInputData(inputData)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .build()

                WorkManager.getInstance(context).enqueue(uploadWorkRequest)
                true
            } catch (e: Exception) {
                Log.e(TAG, "sendMedia failed", e)
                false
            }
        }

    /** Отправка записанного голосового сообщения */
    suspend fun sendVoiceMessage(voiceFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            val messageId = UUID.randomUUID().toString()
            val fileId = UUID.randomUUID().toString()
            val tempEncryptedFile = File(context.cacheDir, "$fileId.enc")

            val aesKey = MediaCrypto.generateAESKey()
            MediaCrypto.encryptFile(voiceFile, tempEncryptedFile, aesKey)
            val aesKeyBase64 = Base64.encodeToString(MediaCrypto.keyToBytes(aesKey), Base64.NO_WRAP)

            val attachment = MediaAttachment(
                fileId = fileId,
                encryptedKey = aesKeyBase64,
                mediaType = "audio/3gp",
                fileName = "voice.3gp",
                fileSize = tempEncryptedFile.length(),
                thumbnailBase64 = null
            )

            val messageContent = MessageContent(text = "", attachments = listOf(attachment))
            val encryptedPayload = cryptoManager.encryptMessage(messageContent, chatId)

            messageManager.saveOutgoingMessage(
                chatId = chatId,
                content = "",
                myId = myId,
                encryptedPayload = encryptedPayload,
                status = MessageStatus.SENDING,
                messageType = "audio",
                attachments = listOf(
                    AttachmentEntity(
                        messageId = messageId,
                        fileId = fileId,
                        encryptedKey = aesKeyBase64,
                        mediaType = "audio/3gp",
                        fileName = "voice.3gp",
                        fileSize = tempEncryptedFile.length(),
                        localFilePath = voiceFile.absolutePath,
                        downloadProgress = 0,
                        uploadProgress = 0
                    )
                ),
                uploadProgress = 0,
                messageId = messageId
            )

            val inputData = Data.Builder()
                .putString("messageId", messageId)
                .putString("contactId", chatId)
                .putString("typeMsg", "audio")
                .build()

            WorkManager.getInstance(context).enqueue(
                OneTimeWorkRequestBuilder<MediaUploadWorker>()
                    .setInputData(inputData)
                    .setConstraints(
                        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                    )
                    .build()
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "sendVoiceMessage failed", e)
            false
        }
    }

    // ==================== Синхронизация / статусы / тайпинг ====================

    fun syncNow() {
        if (chatId.isEmpty()) return
        viewModelScope.launch {
            MessageSyncer.syncChat(
                context = context,
                cryptoManager = cryptoManager,
                messageManager = messageManager,
                chatId = chatId,
                myId = myId,
                showNotification = false
            )
        }
    }

    fun markRead() {
        if (chatId.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val unread = messageManager.getUnreadMessages(chatId)
                for (msg in unread) {
                    messageManager.updateMessageStatus(chatId, msg.messageId, MessageStatus.READ)
                    SocketManager.sendReadReceipt(chatId, msg.messageId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "markRead failed", e)
            }
        }
    }

    fun resendPending() {
        if (chatId.isEmpty()) return
        viewModelScope.launch {
            messageManager.getPendingMessages(chatId).first().let { pending ->
                for (msg in pending) {
                    try {
                        // 🌟 Групповой контейнер {payloads:[...]} — шлём group_message
                        val groupPayloads = parseGroupPayloads(msg.encryptedPayload)
                        val sent = if (groupPayloads != null) {
                            SocketManager.sendGroupMessage(chatId, groupPayloads, msg.messageId, msg.messageType)
                        } else {
                            val payload = Gson().fromJson(msg.encryptedPayload, EncryptedPayload::class.java)
                            SocketManager.sendEncryptedMessage(chatId, payload, msg.messageId, msg.messageType)
                        }
                        messageManager.updateStatus(chatId, msg.messageId, if (sent) MessageStatus.SENT else MessageStatus.FAILED)
                    } catch (e: Exception) {
                        messageManager.updateStatus(chatId, msg.messageId, MessageStatus.FAILED)
                    }
                }
            }
        }
    }

    fun refreshOnlineStatus() {
        if (chatId.isEmpty() || isGroup) return
        viewModelScope.launch {
            try {
                val response = NetworkClient.apiService.getUserStatus(chatId)
                if (response.isSuccessful) _peerOnline.value = response.body()?.isOnline ?: false
            } catch (e: Exception) {
                Log.e(TAG, "refreshOnlineStatus failed", e)
            }
        }
    }

    fun updateContactOnline(isOnline: Boolean) {
        _peerOnline.value = isOnline
        viewModelScope.launch(Dispatchers.IO) {
            try { messageManager.updateContactOnlineStatus(chatId, isOnline) } catch (_: Exception) {}
        }
    }

    /** Привязка сокет-колбэков к состоянию этого чата (вызывать из onCreate/onResume) */
    fun bindSocket() {
        SocketManager.onUserStatusChanged = { userId, isOnline ->
            if (userId == chatId) _peerOnline.value = isOnline
        }
        SocketManager.onTypingIndicator = { userId, chatIdParam, isTyping ->
            if (userId == chatId && chatIdParam == myId) _peerTyping.value = isTyping
        }
        SocketManager.onGroupTyping = { gId, userId, isTyping ->
            if (gId == chatId) onGroupTyping(userId, isTyping)
        }
    }

    /** Тайпинг участника группы; свой таймер гаснет через 4с, если stop не пришёл */
    fun onGroupTyping(userId: String, isTyping: Boolean) {
        if (!isGroup || userId == myId) return
        groupTypingJobs.remove(userId)?.cancel()
        if (isTyping) {
            groupTypingIds.add(userId)
            groupTypingJobs[userId] = viewModelScope.launch {
                delay(4000)
                groupTypingJobs.remove(userId)
                groupTypingIds.remove(userId)
                refreshTypingNames()
            }
        } else {
            groupTypingIds.remove(userId)
        }
        refreshTypingNames()
    }

    private fun refreshTypingNames() {
        viewModelScope.launch {
            val members = groupManager.getMembers(chatId)
            val nameMap = members.associate { it.userId to (it.displayName ?: it.username ?: it.userId) }
            _groupTypingNames.value = groupTypingIds.mapNotNull { nameMap[it] ?: it }
        }
    }

    fun unbindSocket() {
        if (SocketManager.onUserStatusChanged != null) SocketManager.onUserStatusChanged = null
        if (SocketManager.onTypingIndicator != null) SocketManager.onTypingIndicator = null
        if (SocketManager.onGroupTyping != null) SocketManager.onGroupTyping = null
        typingStopJob?.cancel()
        groupTypingJobs.values.forEach { it.cancel() }
        groupTypingJobs.clear()
        groupTypingIds.clear()
        _groupTypingNames.value = emptyList()
    }

    fun onUserTyping() {
        if (!SocketManager.isConnected()) return
        if (!typingStartSent) {
            if (isGroup) SocketManager.sendGroupTyping(chatId, true)
            else SocketManager.sendTypingStart(chatId)
            typingStartSent = true
        }
        typingStopJob?.cancel()
        typingStopJob = viewModelScope.launch {
            delay(3000)
            sendTypingStop()
        }
    }

    fun sendTypingStop() {
        if (typingStartSent && SocketManager.isConnected()) {
            if (isGroup) SocketManager.sendGroupTyping(chatId, false)
            else SocketManager.sendTypingStop(chatId)
            typingStartSent = false
        }
        typingStopJob?.cancel()
        typingStopJob = null
    }

    /** Повторное скачивание вложения (ретрай по крестику в чате) */
    suspend fun downloadAttachment(attachment: ru.insolent.castoms.ig.database.AttachmentEntity): String? =
        withContext(Dispatchers.IO) {
            messageManager.downloadAndDecryptMedia(
                context = context,
                fileId = attachment.fileId,
                encryptedKeyBase64 = attachment.encryptedKey,
                mediaType = attachment.mediaType,
                fileName = attachment.fileName,
                messageId = attachment.messageId,
                expectedFileSize = attachment.fileSize
            )
        }

    /** Сброс прогресса вложений перед ретраем */
    suspend fun resetAttachmentProgress(messageId: String) = withContext(Dispatchers.IO) {
        try {
            for (att in messageManager.getAttachmentsForMessage(messageId)) {
                messageManager.updateAttachmentDownloadProgress(messageId, att.fileId, 0)
            }
        } catch (_: Exception) {}
    }

    /**
     * Парсинг группового контейнера {payloads:[...]}; null — если не групповой.
     * Понимает две формы хранения:
     *  1) {"payloads":[...]} — напрямую;
     *  2) {"type":-1,"data":"{payloads:...}"} — EncryptedPayload-обёртка, как пишет
     *     MessageManager.saveOutgoingMessage (единый формат с медиа в группах).
     */
    private fun parseGroupPayloads(raw: String?): List<GroupPayload>? {
        if (raw.isNullOrEmpty()) return null
        return try {
            val obj = com.google.gson.JsonParser.parseString(raw).asJsonObject
            val target = when {
                obj.has("payloads") -> obj
                obj.has("data") && obj.get("type")?.asInt == -1 ->
                    com.google.gson.JsonParser.parseString(obj.get("data").asString).asJsonObject
                else -> return null
            }
            if (!target.has("payloads")) return null
            target.getAsJsonArray("payloads").map {
                val e = it.asJsonObject
                GroupPayload(e.get("userId").asString, e.get("type").asInt, e.get("data").asString)
            }
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private const val TAG = "ChatViewModel"
    }
}
