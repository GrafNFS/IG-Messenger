package ru.insolent.castoms.ig

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.insolent.castoms.ig.databinding.ActivityChatBinding
import ru.insolent.castoms.ig.adapters.*
import ru.insolent.castoms.ig.crypto.*
import ru.insolent.castoms.ig.database.*
import ru.insolent.castoms.ig.database.AttachmentEntity
import ru.insolent.castoms.ig.network.*
import ru.insolent.castoms.ig.service.MediaUploadWorker
import ru.insolent.castoms.ig.utils.*
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class ChatActivity : AppCompatActivity(), MessageAdapter.OnMediaClickListener {

    private lateinit var binding: ActivityChatBinding
    private var contactId: String = ""
    private var contactName: String = ""
    private lateinit var cryptoManager: MessageCryptoManager
    private lateinit var messageManager: MessageManager
    private lateinit var messageAdapter: MessageAdapter
    companion object {
        var currentOpenChatId: String? = null
    }
    private var myId: String = ""
    private val TAG = "ChatActivity"
    private var isContactOnline = false
    private var isContactTyping = false
    private val downloadingFiles = mutableSetOf<String>()
    private val selectedMedia = mutableListOf<String>()
    private val MAX_IMAGES = 10
    private lateinit var mediaPreviewAdapter: MediaPreviewAdapter
    // 🌟 Переменные для записи голосовых сообщений
    private var mediaRecorder: MediaRecorder? = null
    private var audioRecordFile: File? = null
    private var isAudioRecording = false
    private var isRecordLocked = false
    private var startRecordY = 0f
    private var isSwipeActionHandled = false

    // 🌟 Переменные для таймера и свайпа отмены
    private var recordStartTime = 0L
    private var startRecordX = 0f
    private var recordSeconds = 0
    private val recordTimerHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var recordTimerRunnable: Runnable? = null
    // 🌟 Меняем контракт на PickMultipleVisualMedia
    private val mediaPickerLauncher = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_IMAGES)
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            lifecycleScope.launch(Dispatchers.IO) {
                val availableSlots = MAX_IMAGES - selectedMedia.size
                val toAddUris = uris.take(availableSlots)

                val copiedPaths = mutableListOf<String>()

                toAddUris.forEach { uri ->
                    try {
                        val fileId = UUID.randomUUID().toString()
                        val mimeType = contentResolver.getType(uri)
                        val isVideo = mimeType?.startsWith("video") == true
                        val isGrid = mimeType == "image/gif"
                        val extension = if (isGrid) "gif" else if (isVideo) "mp4" else "jpg"

                        // Создаем файл в нашем кэше, к которому доступ никогда не пропадет
                        val localCacheFile = File(cacheDir, "pre_$fileId.$extension")

                        // Мгновенно копируем поток байт, пока сессия выбора открыта
                        contentResolver.openInputStream(uri)?.use { input ->
                            FileOutputStream(localCacheFile).use { output ->
                                input.copyTo(output)
                            }
                        }

                        if (localCacheFile.exists() && localCacheFile.length() > 0) {
                            copiedPaths.add(localCacheFile.absolutePath)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Ошибка быстрого копирования файла из галереи", e)
                    }
                }

                withContext(Dispatchers.Main) {
                    if (copiedPaths.isNotEmpty()) {
                        selectedMedia.addAll(copiedPaths)
                        updateMediaPreview() // Перерисует горизонтальную полосу превью
                    }
                }
            }
        }
    }

    private val cameraPhotoLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success: Boolean ->
        // 🌟 ИСПРАВЛЕНИЕ: Если фото сделано, добавляем в выбранные путь к локальному файлу кэша
        if (success && cameraPhotoUri != null) {
            val photoFile = File(cacheDir, cameraPhotoUri!!.lastPathSegment ?: "")

            // Нам нужен именно абсолютный путь файла на диске для Picasso превью
            val actualFile = File(cacheDir, photoFile.name)
            if (actualFile.exists()) {
                selectedMedia.add(actualFile.absolutePath)
                updateMediaPreview() // Мгновенно обновит горизонтальную ленту превью
            }
        }
    }

    private val cameraVideoLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // 🌟 ИСПРАВЛЕНИЕ: Если видео снято, берем файл, путь к которому мы сгенерировали при старте
        if (result.resultCode == Activity.RESULT_OK && cameraVideoUri != null) {
            val videoFile = File(cacheDir, cameraVideoUri!!.lastPathSegment ?: "")
            val actualFile = File(cacheDir, videoFile.name)

            if (actualFile.exists()) {
                selectedMedia.add(actualFile.absolutePath)
                updateMediaPreview() // Мгновенно обновит горизонтальную ленту превью
            }
        }
    }

    private var cameraPhotoUri: Uri? = null
    private var cameraVideoUri: Uri? = null

    private var typingStartSent = false
    private var typingAnimator: android.animation.ValueAnimator? = null
    private var typingStopJob: Job? = null
    private var replyToMessageId: String? = null
    private var replyToSenderName: String? = null
    private var replyToContent: String? = null
    private var editingMessageId: String? = null
    private var originalEditText: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        contactId = intent.getStringExtra("EXTRA_CONTACT_ID") ?: ""
        contactName = intent.getStringExtra("EXTRA_CONTACT_NAME") ?: "Unknown"
        val avatarResId = intent.getIntExtra("EXTRA_CONTACT_AVATAR", R.drawable.ic_default_avatar)

        binding.tvContactName.text = contactName
        binding.ivContactAvatar.setImageResource(avatarResId)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val sessionManager = SessionManager(this)
        myId = sessionManager.getUserId() ?: ""
        cryptoManager = MessageCryptoManager(myId, this@ChatActivity)
        messageManager = MessageManager(this)
        messageAdapter = MessageAdapter()

        messageAdapter.setQuoteClickListener { replyToMessageId ->
            scrollToMessage(replyToMessageId)
        }
        messageAdapter.setMediaClickListener(this)

        messageAdapter.setActionListener(object : MessageAdapter.OnMessageActionListener {
            override fun onReply(message: MessageEntity) { showQuotePanel(message) }
            override fun onCopu(message: MessageEntity) { copyTextToClipboard(this@ChatActivity, message) }
            override fun onEdit(message: MessageEntity) { startEditing(message) }
            override fun onRefresh(message: MessageEntity, attachments: List<AttachmentEntity>) { startRefresh(message, attachments) }
            override fun onDelete(message: MessageEntity) { confirmDelete(message) }
        })

        // 🌟 Добавляем обработку повторного скачивания медиафайлов
        messageAdapter.setRetryDownloadListener(object : MessageAdapter.OnRetryDownloadListener {
            override fun onRetryClick(attachment: AttachmentEntity) {
                lifecycleScope.launch(Dispatchers.IO) {
                    // 1. Возвращаем прогресс в БД на 0%, чтобы скрылся крестик/ошибка и пополз лоадер
                    messageManager.updateAttachmentDownloadProgress(
                        messageId = attachment.messageId,
                        fileId = attachment.fileId,
                        progress = 0
                    )

                    // 2. Обновляем UI в адаптере, чтобы анимация сброса применилась мгновенно
                    lifecycleScope.launch(Dispatchers.Main) {
                        messageAdapter.updateDownloadProgressForAttachment(
                            messageId = attachment.messageId,
                            fileId = attachment.fileId,
                            progress = 0
                        )
                    }

                    // 3. Запускаем метод скачивания по новой в фоновом режиме
                    messageManager.downloadAndDecryptMedia(
                        context = this@ChatActivity,
                        fileId = attachment.fileId,
                        encryptedKeyBase64 = attachment.encryptedKey,
                        mediaType = attachment.mediaType,
                        fileName = attachment.fileName,
                        messageId = attachment.messageId,
                        expectedFileSize = attachment.fileSize
                    )
                }
            }
        })

        binding.rvMessages.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.rvMessages.adapter = messageAdapter

        val swipeCallback = SwipeCallback { position ->
            val message = messageAdapter.getMessageAt(position)
            if (message != null) {
                showQuotePanel(message)
                binding.rvMessages.post { messageAdapter.notifyItemChanged(position) }
            }
        }
        ItemTouchHelper(swipeCallback).attachToRecyclerView(binding.rvMessages)

        binding.btnCloseQuote.setOnClickListener { hideQuotePanel() }

        // По умолчанию ставим микрофон при старте
        binding.btnSend.setImageResource(R.drawable.ic_mic)

        binding.etMessageInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (s.isNullOrEmpty()) {
                    sendTypingStopEvent()
                    // 🌟 ИСПРАВЛЕНИЕ: Если текст стерли, и нет картинок/редактирования — возвращаем микрофон
                    if (selectedMedia.isEmpty() && editingMessageId == null) {
                        binding.btnSend.setImageResource(R.drawable.ic_mic)
                    }
                } else {
                    handleUserTyping()
                    // 🌟 ИСПРАВЛЕНИЕ: Если пользователь ввёл хотя бы один символ — СРАЗУ ставим стрелку отправки
                    if (editingMessageId == null) {
                        binding.btnSend.setImageResource(R.drawable.ic_send)
                    }
                }
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        binding.btnAttach.setOnClickListener { view ->
            val popup = PopupMenu(this, view)
            popup.menu.add("📷 Фото/Видео из галереи")  // ✅ Объединили
            popup.menu.add("📸 Снять фото")
            popup.menu.add("🎬 Снять видео (до 1 мин)")

            popup.setOnMenuItemClickListener { item ->
                when (item.title) {
                    "📷 Фото/Видео из галереи" -> openMediaPicker()
                    "📸 Снять фото" -> openCameraPhoto()
                    "🎬 Снять видео (до 1 мин)" -> openCameraVideo()
                }
                true
            }
            popup.show()
        }

        // 1. Обработка для записи голосовых сообщений
        @SuppressLint("ClickableViewAccessibility")
        binding.btnSend.setOnTouchListener { v, event ->
            val textNotEmpty = binding.etMessageInput.text.toString().trim().isNotEmpty()

            // 1. Обычный режим отправки текста/медиа
            if (textNotEmpty || selectedMedia.isNotEmpty() || editingMessageId != null) {
                return@setOnTouchListener false // Пусть работает обычный OnClickListener
            }

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // ✅ Если запись УЖЕ зафиксирована, то ACTION_DOWN — это новый тап по стрелочке!
                    if (isRecordLocked) {
                        Log.d(TAG, "🎯 Тап по зафиксированной кнопке: отправляем голосовое!")
                        stopAndSendAudioResponse(true)
                        return@setOnTouchListener true
                    }

                    // Проверка прав на микрофон
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 400)
                        return@setOnTouchListener true
                    }

                    startRecordY = event.rawY
                    startRecordX = event.rawX
                    isSwipeActionHandled = false // ✅ Сбрасываем флаг для нового нажатия
                    startAudioRecording()
                    return@setOnTouchListener true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (isAudioRecording && !isRecordLocked) {
                        // Логика А: Свайп влево — отмена и удаление файла
                        val diffX = startRecordX - event.rawX
                        if (diffX > dpToPx(this, 70)) {
                            Toast.makeText(this, "🗑️ Запись отменена", Toast.LENGTH_SHORT).show()
                            stopAndSendAudioResponse(false)
                            return@setOnTouchListener true
                        }

                        // Логика Б: Свайп вверх — фиксация замка
                        val diffY = startRecordY - event.rawY
                        if (diffY > dpToPx(this, 80)) {
                            isRecordLocked = true
                            isSwipeActionHandled = true // ✅ Запоминаем, что в этом жесте был свайп!
                            binding.btnSend.setImageResource(R.drawable.ic_send) // Меняем микрофон на стрелку

                            // Опционально: можно добавить вибрацию для тактильного отклика
                            // val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                            // vibrator.vibrate(50)

                            return@setOnTouchListener true
                        }
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isAudioRecording) {
                        if (isRecordLocked && isSwipeActionHandled) {
                            // ✅ Пользователь свайпнул вверх и отпустил палец. ИГНОРИРУЕМ ОТПУСКАНИЕ!
                            // Диктофон продолжит писать звук в фоне.
                            Log.d(TAG, "🔒 Свайп завершен, палец отпущен. Запись продолжается в фоне.")
                        } else if (isRecordLocked) {
                            // Кнопка уже была заблокирована ранее, и это новый тап (ACTION_DOWN -> ACTION_UP без движения)
                            Log.d(TAG, "🎯 Короткий тап по стрелочке: отправляем голосовое!")
                            stopAndSendAudioResponse(true)
                        } else {
                            // Обычная короткая отправка (палец не ушел вверх или влево)
                            stopAndSendAudioResponse(true)
                        }
                    }
                    return@setOnTouchListener true
                }
            }
            true
        }

        observeMessages()

        SocketManager.onUserStatusChanged = { userId, isOnline ->
            if (userId == contactId) {
                runOnUiThread { updateOnlineStatus(isOnline) }
            }
        }

        SocketManager.onTypingIndicator = { userId, chatId, isTyping ->
            if (userId == contactId && chatId == myId) {
                runOnUiThread { updateTypingIndicator(isTyping) }
            }
        }

        checkContactOnlineStatus()

        binding.btnSend.isEnabled = false
        binding.etMessageInput.isEnabled = false
        binding.tvStatus.text = "Ожидание подключения..."
        binding.tvStatus.setTextColor(getColor(R.color.text_secondary_light))

        binding.btnSend.setOnClickListener {
            val text = binding.etMessageInput.text.toString().trim()
            lifecycleScope.launch {
                try {
                    if (editingMessageId != null) {
                        val actionContent = MessageContent(
                            text = text,
                            action = "edit",
                            targetMessageId = editingMessageId
                        )
                        val encrypted = cryptoManager.encryptMessage(actionContent, contactId)
                        SocketManager.sendMessageAction(contactId, encrypted, editingMessageId)
                        messageManager.updateMessageText(editingMessageId!!, text)
                        cancelEditing()
                    }
                    // ✅ НОВОЕ: Если есть выбранные медиа, отправляем их
                    else if (selectedMedia.isNotEmpty()) {
                        val mediaToSend = selectedMedia.toList()
                        val caption = text // Текст из поля ввода

                        selectedMedia.clear()
                        updateMediaPreview()
                        binding.etMessageInput.text.clear()
                        hideQuotePanel()

                        // Отправляем ВСЕ медиа как ОДНО сообщение
                        processSelectedMedia(mediaToSend, text)
                    }
                    // ✅ СТАРОЕ: Если только текст
                    else if (text.isNotEmpty() && !selectedMedia.isNotEmpty()) {
                        val messageContent = MessageContent(
                            text = text,
                            replyToMessageId = replyToMessageId,
                            replyToSenderName = replyToSenderName,
                            replyToContent = replyToContent
                        )
                        val encrypted = cryptoManager.encryptMessage(messageContent, contactId)

                        val messageId = messageManager.saveOutgoingMessage(
                            chatId = contactId,
                            content = text,
                            myId = myId,
                            encryptedPayload = encrypted,
                            status = MessageStatus.SENDING,
                            replyToMessageId = replyToMessageId,
                            replyToSenderName = replyToSenderName,
                            replyToContent = replyToContent
                        )

                        messageAdapter.addMessage(
                            messageId = messageId,
                            text = text,
                            isMe = true,
                            time = getCurrentTime(),
                            status = MessageStatus.SENDING,
                            messageType = "text"
                        )
                        binding.etMessageInput.text.clear()
                        binding.rvMessages.scrollToPosition(messageAdapter.itemCount - 1)

                        val sent = SocketManager.sendEncryptedMessage(contactId, encrypted, messageId)

                        if (sent) {
                            messageManager.updateStatus(contactId, messageId, MessageStatus.SENT)
                            messageAdapter.updateMessageStatus(messageId, MessageStatus.SENT)
                        } else {
                            messageManager.updateStatus(contactId, messageId, MessageStatus.FAILED)
                            Toast.makeText(this@ChatActivity, "Failed to send", Toast.LENGTH_SHORT).show()
                        }
                        hideQuotePanel()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Encrypt/send error", e)
                    Toast.makeText(this@ChatActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        lifecycleScope.launch {
            loadContactKeysAndInitSession()
            delay(300)
            syncMessages()
            sendReadReceiptsForUnreadMessages()
        }

        // Инициализация адаптера превью
        mediaPreviewAdapter = MediaPreviewAdapter { position ->
            removeMedia(position)
        }
        binding.rvImagePreview.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvImagePreview.adapter = mediaPreviewAdapter

        // Обработчик кнопки добавления
        binding.btnAddImage.setOnClickListener {
            openMediaPicker()
        }
    }

    // 🌟 Запуск записи аудио + старт таймера
    private fun startAudioRecording() {
        try {
            Log.d(TAG, "🎙️ Попытка запустить запись аудио...")
            val fileId = UUID.randomUUID().toString()
            audioRecordFile = File(cacheDir, "voice_$fileId.3gp")
            audioRecordFile?.parentFile?.let { if (!it.exists()) it.mkdirs() }

            mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                setOutputFile(audioRecordFile?.absolutePath)
                prepare()
                start()
            }

            // 🌟 Фиксируем системное время старта для точного расчета миллисекунд
            recordStartTime = System.currentTimeMillis()

            isAudioRecording = true
            isRecordLocked = false

            // Запускаем новый быстрый таймер
            startRecordTimer()

        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка диктофона", e)
            isAudioRecording = false
        }
    }

    // 🌟 Высокоточный таймер с поддержкой Минут, Секунд и Миллисекунд
    private fun startRecordTimer() {
        recordTimerRunnable?.let { recordTimerHandler.removeCallbacks(it) }
        recordTimerRunnable = object : Runnable {
            override fun run() {
                if (isAudioRecording) {
                    // Вычисляем, сколько всего миллисекунд прошло со старта записи
                    val elapsedTime = System.currentTimeMillis() - recordStartTime

                    val minutes = (elapsedTime / 1000) / 60
                    val seconds = (elapsedTime / 1000) % 60
                    // Получаем сотые доли секунды (две цифры миллисекунд, как на секундомере)
                    val millis = (elapsedTime % 1000) / 10

                    // Форматируем строку в вид "0:01:25" (минуты:секунды:миллисекунды)
                    val timeString = String.format(Locale.getDefault(), "%02d:%02d,%02d", minutes, seconds, millis)

                    // Выводим обновленный текст в Hint в зависимости от состояния замка
                    if (isRecordLocked) {
                        binding.etMessageInput.setHint("🔴 $timeString | Нажмите для отправки")
                    } else {
                        binding.etMessageInput.setHint("🔴 $timeString | ◀ Сдвиг влево для отмены")
                    }

                    // 🌟 Повторяем шаг каждые 10 миллисекунд для плавного бега цифр
                    recordTimerHandler.postDelayed(this, 10)
                }
            }
        }
        recordTimerHandler.post(recordTimerRunnable!!)
    }

    // 🌟 Остановка диктофона + очистка таймера
    private fun stopAndSendAudioResponse(shouldSend: Boolean) {
        recordTimerRunnable?.let { recordTimerHandler.removeCallbacks(it) }
        recordTimerRunnable = null

        // 🌟 ВЫЧИСЛЯЕМ ФАКТИЧЕСКУЮ ДЛИТЕЛЬНОСТЬ ЗАПИСИ
        val recordDuration = System.currentTimeMillis() - recordStartTime
        var isRecordValid = true

        try {
            mediaRecorder?.apply {
                // Если запись длилась меньше 700 мс — это слишком быстрое нажатие, останавливать нельзя
                if (recordDuration < 700) {
                    isRecordValid = false
                    Log.w(TAG, "⚠️ Запись слишком короткая ($recordDuration мс), отменяем.")
                } else {
                    stop()
                }
                release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка при остановке MediaRecorder (слишком короткая запись)", e)
            isRecordValid = false // Если метод stop() упал, значит файл гарантированно битый
        }

        mediaRecorder = null
        isAudioRecording = false

        runOnUiThread {
            binding.etMessageInput.setHint(R.string.type_message)
            binding.etMessageInput.text.clear()
            binding.btnSend.setImageResource(R.drawable.ic_mic)

            // 🌟 Подсказка пользователю, если он просто быстро кликнул на микрофон
            if (!isRecordValid && !isRecordLocked && shouldSend) {
                Toast.makeText(this, "Удерживайте для записи", Toast.LENGTH_SHORT).show()
            }
        }

        val file = audioRecordFile
        // 🌟 ОТПРАВЛЯЕМ ФАЙЛ ТОЛЬКО ЕСЛИ ОН ВАЛИДНЫЙ (длился дольше 700мс)
        if (shouldSend && isRecordValid && file != null && file.exists() && file.length() > 0) {
            lifecycleScope.launch(Dispatchers.IO) {
                processAudioAttachment(file)
            }
        } else {
            Log.w(TAG, "🗑️ Файл записи удален (короткий клик, отмена свайпом или ошибка кодека)")
            if (file?.exists() == true) file.delete()
        }
        isRecordLocked = false
    }

    // 🌟 3. Шифрование и отправка в WorkManager
    private suspend fun processAudioAttachment(audioFile: File) = withContext(Dispatchers.IO) {
        val messageId = UUID.randomUUID().toString()
        val fileId = UUID.randomUUID().toString()
        val encryptedFile = File(cacheDir, "$fileId.enc")
        val localCacheFile = File(cacheDir, "$fileId.3gp")

        try {
            Log.d(TAG, "🔒 Начинаем шифрование аудиосообщения...")
            audioFile.renameTo(localCacheFile)

            // AES Шифрование
            val aesKey = MediaCrypto.generateAESKey()
            MediaCrypto.encryptFile(localCacheFile, encryptedFile, aesKey)
            Log.d(TAG, "✅ Файл зашифрован. Размер .enc: ${encryptedFile.length()} байт")

            val fileSize = encryptedFile.length()
            val aesKeyBase64 = Base64.encodeToString(MediaCrypto.keyToBytes(aesKey), Base64.NO_WRAP)

            val attachments = listOf(
                MediaAttachment(
                    fileId = fileId,
                    encryptedKey = aesKeyBase64,
                    mediaType = "audio/3gpp",
                    fileName = "voice.3gp",
                    fileSize = fileSize,
                    thumbnailBase64 = ""
                )
            )

            val messageContent = MessageContent(text = "", attachments = attachments)
            val encryptedPayload = cryptoManager.encryptMessage(messageContent, contactId)

            val uiAttachments = listOf(
                AttachmentEntity(
                    messageId = messageId,
                    fileId = fileId,
                    encryptedKey = aesKeyBase64,
                    mediaType = "audio/3gpp", // 🌟 3. ИСПРАВЛЕНИЕ
                    fileName = "voice.3gp",    // 🌟 4. ИСПРАВЛЕНИЕ
                    fileSize = fileSize,
                    thumbnailBase64 = "",
                    localFilePath = localCacheFile.absolutePath,
                    downloadProgress = 0,
                    uploadProgress = 0
                )
            )

            // Сохраняем в БД
            messageManager.saveOutgoingMessage(
                chatId = contactId, content = "", myId = myId, encryptedPayload = encryptedPayload, status = MessageStatus.SENDING,
                messageType = "audio", attachments = uiAttachments, uploadProgress = 0, messageId = messageId
            )
            Log.d(TAG, "📝 Аудиосообщение успешно записано в локальную БД Room под ID: $messageId")

            // Запуск WorkManager
            val inputData = Data.Builder().putString("messageId", messageId).putString("contactId", contactId).build()
            val uploadWorkRequest = OneTimeWorkRequestBuilder<MediaUploadWorker>()
                .setInputData(inputData)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()

            WorkManager.getInstance(this@ChatActivity).enqueue(uploadWorkRequest)

            withContext(Dispatchers.Main) {
                binding.rvMessages.scrollToPosition(messageAdapter.itemCount - 1)
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Критическая ошибка при подготовке аудиосообщения к отправке", e)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 200 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            openMediaPicker()
        }
    }

    private fun updateMediaPreview() {
        // Показываем или скрываем контейнер
        binding.mediaPreviewContainer.visibility = if (selectedMedia.isEmpty()) View.GONE else View.VISIBLE
        binding.mediaCountText.text = "${selectedMedia.size}/$MAX_IMAGES"
        binding.btnAddImage.isEnabled = selectedMedia.size < MAX_IMAGES
        // Обновляем список в адаптере
        mediaPreviewAdapter.submitList(selectedMedia.toList())
        if (selectedMedia.isNotEmpty()) {
            binding.btnSend.setImageResource(R.drawable.ic_send) // Переключаем на стрелку, если добавлены фото
        }
        else if (binding.etMessageInput.text.isEmpty()) {
                binding.btnSend.setImageResource(R.drawable.ic_mic)
            }
    }

    private fun removeMedia(position: Int) {
        if (position in selectedMedia.indices) {
            selectedMedia.removeAt(position)
            updateMediaPreview()
        }
    }

    private fun openMediaPicker() {
        // 🌟 Запускаем со специальным типом, который объединяет фото и видео
        mediaPickerLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
        )
    }

    private fun openCameraPhoto() {
        val photoFile = createImageFile()
        cameraPhotoUri = FileProvider.getUriForFile(this, "${applicationContext.packageName}.fileprovider", photoFile)
        cameraPhotoUri?.let { uri -> cameraPhotoLauncher.launch(uri) }
    }

    private fun openCameraVideo() {
        val videoFile = createVideoFile()
        cameraVideoUri = FileProvider.getUriForFile(this, "${applicationContext.packageName}.fileprovider", videoFile)
        cameraVideoUri?.let { uri ->
            val intent = Intent(MediaStore.ACTION_VIDEO_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, uri)
                putExtra(MediaStore.EXTRA_DURATION_LIMIT, 60)
                putExtra(MediaStore.EXTRA_VIDEO_QUALITY, 1)
            }
            cameraVideoLauncher.launch(intent)
        }
    }

    private fun createImageFile(): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        // 🌟 СТАЛО: Создаем временный файл прямо во внутреннем кэше приложения
        return File.createTempFile("JPEG_${timeStamp}_", ".jpg", cacheDir)
    }

    private fun createVideoFile(): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        // 🌟 СТАЛО: Создаем временный файл видео прямо во внутреннем кэше приложения
        return File.createTempFile("MP4_${timeStamp}_", ".mp4", cacheDir)
    }

    private suspend fun sendReadReceiptsForUnreadMessages() {
        val unreadMessages = messageManager.getUnreadMessages(contactId)
        if (unreadMessages.isNotEmpty()) {
            for (msg in unreadMessages) {
                messageManager.updateMessageStatus(contactId, msg.messageId, MessageStatus.READ)
                SocketManager.sendReadReceipt(contactId, msg.messageId)
            }
        }
    }

    private fun checkContactOnlineStatus() {
        lifecycleScope.launch {
            try {
                val response = NetworkClient.apiService.getUserStatus(contactId)
                if (response.isSuccessful) updateOnlineStatus(response.body()?.isOnline ?: false)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check user status", e)
            }
        }
    }

    private fun updateTypingIndicator(isTyping: Boolean) {
        isContactTyping = isTyping

        // 🌟 1. Если собеседник ПРЕКРАТИЛ печатать, глушим аниматор и возвращаем обычный статус
        if (!isTyping) {
            typingAnimator?.cancel()
            typingAnimator = null

            binding.tvStatus.text = if (isContactOnline) getText(R.string.online) else getText(R.string.offline)
            binding.tvStatus.setTextColor(if (isContactOnline) getColor(R.color.online) else getColor(R.color.text_secondary_light))
            return
        }

        // 🌟 2. Если аниматор УЖЕ запущен, повторно его создавать не нужно
        if (typingAnimator != null) return

        binding.tvStatus.setTextColor(getColor(R.color.online)) // Красим статус в зеленый цвет печати

        // 🌟 3. Создаем бесконечный аниматор, который считает циклы от 0 до 3 по кругу
        typingAnimator = android.animation.ValueAnimator.ofInt(0, 4).apply {
            duration = 1200 // Скорость полного цикла бега точек (1.2 секунды)
            repeatCount = android.animation.ValueAnimator.INFINITE // Крутим бесконечно

            addUpdateListener { animator ->
                val count = animator.animatedValue as Int

                // Генерируем точки в зависимости от текущего шага анимации (Telegram-style)
                val dots = when (count) {
                    1 -> "."
                    2 -> ".."
                    3 -> "..."
                    else -> "" // Шаг 0 — пустые точки для эффекта волны
                }

                // Выводим текст в шапку чата
                binding.tvStatus.text = "печатает$dots"
            }
            start()
        }
    }


    private fun showQuotePanel(message: MessageEntity) {
        replyToMessageId = message.messageId
        replyToSenderName = if (!message.isIncoming) contactName else "Вы"
        replyToContent = message.content
        binding.quotePanel.visibility = View.VISIBLE
        binding.tvQuoteSender.text = replyToSenderName
        binding.tvQuoteText.text = replyToContent
    }

    private fun copyTextToClipboard(context: Context, message: MessageEntity) {
        val label = "Copied Text"
        val clipboard = context.getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, message.content)
        clipboard.setPrimaryClip(clip)

        // Необязательное уведомление для пользователя
        Toast.makeText(context, "Текст скопирован", Toast.LENGTH_SHORT).show()
    }

    private fun hideQuotePanel() {
        replyToMessageId = null
        replyToSenderName = null
        replyToContent = null
        binding.quotePanel.visibility = View.GONE
        binding.tvQuoteSender.text = ""
        binding.tvQuoteText.text = ""
    }

    private fun updateOnlineStatus(isOnline: Boolean) {
        isContactOnline = isOnline
        binding.tvStatus.text = if (isOnline) getText(R.string.online) else getText(R.string.offline)
        binding.tvStatus.setTextColor(if (isOnline) getColor(R.color.online) else getColor(R.color.text_secondary_light))
        lifecycleScope.launch { messageManager.updateContactOnlineStatus(contactId, isOnline) }
    }

    private fun observeMessages() {
        lifecycleScope.launch {
            // ✅ Используем новый метод, который возвращает сообщения вместе с вложениями
            messageManager.observeChatHistoryWithAttachments(contactId).collect { messagesWithAttachments ->
                val lastMessage = messagesWithAttachments.lastOrNull()?.message
                if (lastMessage != null && lastMessage.isIncoming && System.currentTimeMillis() - lastMessage.timestamp < 5000) {
                    if (isContactTyping) updateTypingIndicator(false)
                }

                val newUnreadMessages = messagesWithAttachments.filter { it.message.isIncoming && it.message.status != MessageStatus.READ }
                if (newUnreadMessages.isNotEmpty()) {
                    for (item in newUnreadMessages) {
                        messageManager.updateMessageStatus(contactId, item.message.messageId, MessageStatus.READ)
                        SocketManager.sendReadReceipt(contactId, item.message.messageId)
                    }
                }

                messageAdapter.submitList(messagesWithAttachments)
                if (messagesWithAttachments.isNotEmpty()) binding.rvMessages.scrollToPosition(messagesWithAttachments.size - 1)
            }
        }
    }

    private fun handleUserTyping() {
        if (!SocketManager.isConnected()) return
        if (!typingStartSent) {
            SocketManager.sendTypingStart(contactId)
            typingStartSent = true
        }
        typingStopJob?.cancel()
        typingStopJob = lifecycleScope.launch {
            delay(3000)
            sendTypingStopEvent()
        }
    }

    private fun sendTypingStopEvent() {
        if (typingStartSent && SocketManager.isConnected()) {
            SocketManager.sendTypingStop(contactId)
            typingStartSent = false
        }
        typingStopJob?.cancel()
        typingStopJob = null
        // 🌟 Полностью уничтожаем аниматор при закрытии экрана чата
        typingAnimator?.cancel()
        typingAnimator = null
    }

    private fun loadContactKeysAndInitSession() {
        lifecycleScope.launch {
            try {
                val response = NetworkClient.apiService.getKeysBundle(contactId)
                if (response.isSuccessful && response.body() != null) {
                    messageManager.saveContact(id = contactId, username = contactName, displayName = contactName, isOnline = isContactOnline)
                    cryptoManager.initializeSession(contactId, response.body()!!)
                    runOnUiThread {
                        binding.btnSend.isEnabled = true
                        binding.etMessageInput.isEnabled = true
                        binding.tvStatus.text = if (isContactOnline) getText(R.string.online) else getText(R.string.offline)
                        binding.tvStatus.setTextColor(if (isContactOnline) getColor(R.color.online) else getColor(R.color.text_secondary_light))
                    }
                } else {
                    runOnUiThread {
                        binding.etMessageInput.hint = "Failed to load keys"
                        Toast.makeText(this@ChatActivity, "Key bundle empty", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.etMessageInput.hint = "Network error"
                    Toast.makeText(this@ChatActivity, "Network error", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun sendPendingMessages() {
        lifecycleScope.launch {
            messageManager.getPendingMessages(contactId).collect { pending ->
                for (msg in pending) {
                    try {
                        val encryptedPayload = Gson().fromJson(msg.encryptedPayload, EncryptedPayload::class.java)
                        val sent = SocketManager.sendEncryptedMessage(contactId, encryptedPayload, msg.messageId)
                        messageManager.updateStatus(contactId, msg.messageId, if (sent) MessageStatus.SENT else MessageStatus.FAILED)
                    } catch (e: Exception) {
                        messageManager.updateStatus(contactId, msg.messageId, MessageStatus.FAILED)
                    }
                }
            }
        }
    }

    private fun syncMessages() {
        lifecycleScope.launch {
            try {
                val response = NetworkClient.apiService.getChatHistory(contactId, myId)
                if (response.isSuccessful && response.body() != null) {
                    val dtos = response.body()!!

                    val messagesToSave = mutableListOf<MessageEntity>()
                    val attachmentsToSave = mutableListOf<AttachmentEntity>()

                    for (dto in dtos) {
                        val messageId = dto.id ?: continue
                        val senderId = dto.senderId.takeIf { it.isNotBlank() } ?: continue
                        if (!dto.isIncoming) continue

                        val payloadJson = Gson().toJson(dto.payload)
                        val messageContent = try {
                            val payload = Gson().fromJson(payloadJson, EncryptedPayload::class.java)
                            cryptoManager.decryptMessage(payload, senderId)
                        } catch (e: Exception) {
                            Log.w(TAG, "Could not decrypt outgoing $messageId", e)
                            null
                        }

                        val messageType = when {
                            messageContent?.attachments?.any { it.mediaType.startsWith("audio") } == true -> "audio"
                            messageContent?.attachments?.any { it.mediaType.startsWith("video") } == true -> "video"
                            !messageContent?.attachments.isNullOrEmpty() -> "image"
                            else -> "text"
                        }

                        messagesToSave.add(
                            MessageEntity(
                                chatId = contactId,
                                messageId = messageId,
                                senderId = senderId,
                                content = messageContent?.text ?: "",
                                encryptedPayload = payloadJson,
                                timestamp = dto.timestamp,
                                status = MessageStatus.DELIVERED,
                                isIncoming = true,
                                replyToMessageId = messageContent?.replyToMessageId,
                                replyToSenderName = messageContent?.replyToSenderName,
                                replyToContent = messageContent?.replyToContent,
                                messageType = messageType
                            )
                        )

                        messageContent?.attachments?.forEach { att ->
                            attachmentsToSave.add(
                                AttachmentEntity(
                                    messageId = messageId,
                                    fileId = att.fileId,
                                    encryptedKey = att.encryptedKey,
                                    mediaType = att.mediaType,
                                    fileName = att.fileName,
                                    fileSize = att.fileSize,
                                    localFilePath = null,
                                    downloadProgress = 0,
                                    uploadProgress = 0
                                )
                            )
                        }
                    }

                    if (messagesToSave.isNotEmpty()) {
                        messageManager.saveMessagesBatch(messagesToSave)
                    }
                    if (attachmentsToSave.isNotEmpty()) {
                        messageManager.saveAttachmentsBatch(attachmentsToSave)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Sync failed", e)
            }
        }
    }

    override fun onMediaClick(attachments: List<AttachmentEntity>, startPosition: Int) {
        // Оставляем только те файлы, которые уже физически скачаны на диск
        val availableAttachments = attachments.filter { !it.localFilePath.isNullOrEmpty() && File(it.localFilePath).exists() }

        if (availableAttachments.isEmpty()) {
            Toast.makeText(this, "Медиафайлы еще не скачаны", Toast.LENGTH_SHORT).show()
            return
        }

        // Находим новую позицию кликнутого элемента в отфильтрованном списке
        val clickedFileId = attachments[startPosition].fileId
        val realStartPosition = availableAttachments.indexOfFirst { it.fileId == clickedFileId }.coerceAtLeast(0)

        // Сериализуем список вложений в JSON-строку, чтобы передать сложные объекты
        val attachmentsJson = com.google.gson.Gson().toJson(availableAttachments)

        val intent = Intent(this, ImageViewerActivity::class.java).apply {
            putExtra("EXTRA_ATTACHMENTS_JSON", attachmentsJson)
            putExtra(ImageViewerActivity.EXTRA_START_POSITION, realStartPosition)
        }
        startActivity(intent)
    }

    private fun startEditing(message: MessageEntity) {
        editingMessageId = message.messageId
        originalEditText = message.content
        binding.etMessageInput.setText(message.content)
        binding.etMessageInput.setSelection(message.content.length)
        binding.btnSend.setImageResource(android.R.drawable.ic_menu_edit)
    }

    private fun startRefresh(message: MessageEntity, attachments: List<AttachmentEntity>) {
        lifecycleScope.launch {
            try {
                if (!attachments.isNotEmpty()) {
                    val payloadObject =
                        Gson().fromJson(message.encryptedPayload, EncryptedPayload::class.java)
                    val sent =
                        SocketManager.sendEncryptedMessage(
                            contactId,
                            payloadObject,
                            message.messageId
                        )

                    if (sent) {
                        messageManager.updateStatus(
                            contactId,
                            message.messageId,
                            MessageStatus.SENT
                        )
                        messageAdapter.updateMessageStatus(message.messageId, MessageStatus.SENT)
                    } else {
                        messageManager.updateStatus(
                            contactId,
                            message.messageId,
                            MessageStatus.FAILED
                        )
                        Toast.makeText(this@ChatActivity, "Failed to send", Toast.LENGTH_SHORT)
                            .show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Encrypt/send error", e)
                Toast.makeText(this@ChatActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun cancelEditing() {
        editingMessageId = null
        binding.etMessageInput.text.clear()
        binding.btnSend.setImageResource(R.drawable.ic_send)
    }

    private fun confirmDelete(message: MessageEntity) {
        AlertDialog.Builder(this)
            .setTitle("Удалить сообщение?")
            .setMessage("Это действие нельзя отменить.")
            .setPositiveButton("Удалить") { _, _ -> deleteMessage(message) }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun deleteMessage(message: MessageEntity) {
        lifecycleScope.launch {
            val actionContent = MessageContent(action = "delete", targetMessageId = message.messageId)
            val encrypted = cryptoManager.encryptMessage(actionContent, contactId)
            if (message.status != MessageStatus.FAILED) {
                SocketManager.sendMessageAction(contactId, encrypted, message.messageId)
            }
            messageManager.deleteMessage(message.messageId)
        }
    }

    private fun scrollToMessage(messageId: String) {
        lifecycleScope.launch {
            val position = messageAdapter.getPositionByMessageId(messageId)
            if (position != -1) {
                binding.rvMessages.scrollToPosition(position)
                highlightMessage(position)
            } else {
                val messageFromDb = messageManager.getMessageById(messageId)
                if (messageFromDb != null) {
                    // Для простоты добавляем как MessageWithAttachments с пустым списком вложений
                    messageAdapter.addMessageToPosition(position, MessageWithAttachments(messageFromDb, emptyList()))
                    binding.rvMessages.scrollToPosition(position)
                    highlightMessage(position)
                } else {
                    Toast.makeText(this@ChatActivity, "Сообщение не найдено", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun highlightMessage(position: Int) {
        val viewHolder = binding.rvMessages.findViewHolderForAdapterPosition(position)
        val messageContainer = viewHolder?.itemView?.findViewById<LinearLayout>(R.id.messageContainer)
        messageContainer?.apply {
            val originalBackground = background
            setBackgroundColor(getColor(R.color.highlight_color))
            translationZ = 8f
            postDelayed({
                background = originalBackground
                translationZ = 0f
            }, 2000)
        }
    }

    private fun dpToPx(context: Context, dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }

    private fun getCurrentTime(): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

    private suspend fun processSelectedMedia(
        filePaths: List<String>, // 🌟 Принимает пути к файлам кэша
        caption: String = ""
    ) = withContext(Dispatchers.IO) {
        val messageId = UUID.randomUUID().toString()
        val attachments = mutableListOf<MediaAttachment>()
        val originalPaths = mutableListOf<String>()
        val encryptedPaths = mutableListOf<String>()

        try {
            filePaths.forEach { tempPath ->
                val fileId = UUID.randomUUID().toString()
                val tempEncryptedFile = File(cacheDir, "$fileId.enc")

                val originalFile = File(tempPath)
                if (!originalFile.exists()) return@forEach

                // Определяем типы по расширению нашего временного файла
                val isVideo = originalFile.name.endsWith(".mp4")
                val isGif = originalFile.name.endsWith(".gif")
                val extension = if (isGif) "gif" else if (isVideo) "mp4" else "jpg"

                val localCacheFile = File(cacheDir, "$fileId.$extension")

                // Если это видео — сжимаем его. Если фото — просто переименовываем/перемещаем из "pre_" в постоянное имя
                if (isVideo) {
                    // Для сжатия видео вашему VideoHelper может потребоваться Uri. Сделаем его из нашего файла кэша:
                    val fileUri = Uri.fromFile(originalFile)
                    VideoHelper.compressVideo(this@ChatActivity, fileUri, localCacheFile)
                    originalFile.delete() // Удаляем пре-кэш файл
                } else {
                    // Перемещаем файл в постоянный кэш сообщения
                    originalFile.renameTo(localCacheFile)
                }

                originalPaths.add(localCacheFile.absolutePath)

                // Шифрование (остается вашим без изменений)
                val aesKey = MediaCrypto.generateAESKey()
                MediaCrypto.encryptFile(localCacheFile, tempEncryptedFile, aesKey)
                encryptedPaths.add(tempEncryptedFile.absolutePath)

                val fileSize = tempEncryptedFile.length()
                val aesKeyBase64 = Base64.encodeToString(MediaCrypto.keyToBytes(aesKey), Base64.NO_WRAP)

                // Генерируем превью из нашего стабильного локального файла
                val localFileUri = Uri.fromFile(localCacheFile)
                val thumbnailBase64 = if (isVideo || isGif) {
                    VideoHelper.generateVideoThumbnail(this@ChatActivity, localFileUri)
                } else {
                    ImageHelper.generateThumbnailBase64(this@ChatActivity, localFileUri)
                }

                val mediaType = when {
                    isGif -> "image/gif"
                    isVideo -> "video/mp4"
                    else -> "image/jpeg"
                }

                attachments.add(
                    MediaAttachment(
                        fileId = fileId,
                        encryptedKey = aesKeyBase64,
                        mediaType = mediaType,
                        fileName = if (isGif) "animation.gif" else if (isVideo) "video.mp4" else "photo.jpg",
                        fileSize = fileSize,
                        thumbnailBase64 = thumbnailBase64
                    )
                )
            }

            // Подготовка данных для БД
            val messageContent = MessageContent(text = caption, attachments = attachments)
            val encryptedPayload = cryptoManager.encryptMessage(messageContent, contactId)

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

            // Мгновенно сохраняем в БД как отправляющееся (появится в UI)
            messageManager.saveOutgoingMessage(
                chatId = contactId,
                content = caption,
                myId = myId,
                encryptedPayload = encryptedPayload,
                status = MessageStatus.SENDING,
                messageType = messageType,
                attachments = uiAttachments,
                uploadProgress = 0,
                messageId = messageId
            )

            // 🌟 ЗАПУСК WORKMANAGER ДЛЯ СЕТЕВОЙ ЗАГРУЗКИ
            val inputData = Data.Builder()
                .putString("messageId", messageId)
                .putString("contactId", contactId)
                .build()

            val uploadWorkRequest = OneTimeWorkRequestBuilder<MediaUploadWorker>()
                .setInputData(inputData)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED) // Запуск только при наличии сети [1]
                        .build()
                )
                .build()

            WorkManager.getInstance(this@ChatActivity).enqueue(uploadWorkRequest)

            withContext(Dispatchers.Main) {
                binding.rvMessages.scrollToPosition(messageAdapter.itemCount - 1)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to process media", e)
            withContext(Dispatchers.Main) {
                Toast.makeText(this@ChatActivity, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        currentOpenChatId = null

        // 🌟 Глушим анимацию печати при сворачивании экрана
        typingAnimator?.cancel()
        typingAnimator = null
    }

    override fun onResume() {
        super.onResume()
        currentOpenChatId = contactId
        if (SocketManager.isConnected()) sendPendingMessages()
    }

    override fun onDestroy() {
        super.onDestroy()
        typingStopJob?.cancel()
        sendTypingStopEvent()
        currentOpenChatId = null

        // 🌟 Полностью уничтожаем аниматор при закрытии экрана чата
        typingAnimator?.cancel()
        typingAnimator = null
    }
}