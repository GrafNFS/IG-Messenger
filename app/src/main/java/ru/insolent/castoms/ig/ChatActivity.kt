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
import androidx.activity.viewModels
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
import ru.insolent.castoms.ig.chat.ChatViewModel
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
    private val viewModel: ChatViewModel by viewModels()
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

        myId = viewModel.myId
        viewModel.setup(contactId, contactName ?: contactId)
        viewModel.bindSocket()
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
                    viewModel.resetAttachmentProgress(attachment.messageId)

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

        //observeMessages()

        checkContactOnlineStatus()

        binding.btnSend.isEnabled = false
        binding.etMessageInput.isEnabled = false
        binding.tvStatus.text = "Ожидание подключения..."
        binding.tvStatus.setTextColor(getColor(R.color.text_secondary_light))

        binding.btnSend.setOnClickListener {
            if (isAudioRecording) return@setOnClickListener

            if (editingMessageId !== null) {
                val messageText = binding.etMessageInput.text.toString().trim()
                if (messageText.isNotEmpty()) {
                    lifecycleScope.launch {
                        if (viewModel.sendEdit(editingMessageId, messageText)) {
                            binding.etMessageInput.setText("")
                            cancelEditing()
                        }
                    }
                }
            } else if (selectedMedia.isNotEmpty()) {
                lifecycleScope.launch {
                    if (viewModel.sendMedia(ArrayList(selectedMedia), binding.etMessageInput.text.toString().trim())) {
                        selectedMedia.clear()
                        updateMediaPreview()
                        binding.etMessageInput.text.clear()
                        hideQuotePanel()
                    }
                }
            } else {
                val messageText = binding.etMessageInput.text.toString().trim()
                if (messageText.isNotEmpty()) {
                    lifecycleScope.launch {
                        if (viewModel.sendText(messageText, replyToMessageId, replyToSenderName, replyToContent)) {
                            binding.etMessageInput.setText("")
                            hideQuotePanel()
                        }
                    }
                }
            }
        }

        lifecycleScope.launch {
            viewModel.initSession()
            delay(300)
            viewModel.syncNow()
            viewModel.markRead()
        }
        observeViewModel()

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

        if (shouldSend) {
            val file = audioRecordFile
            lifecycleScope.launch {
                if (file != null && file.exists() && file.length() > 0) {
                    viewModel.sendVoiceMessage(file)
                } else {
                    file?.delete()
                }
            }
        } else {
            audioRecordFile?.delete()
        }
        isRecordLocked = false
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

    private fun checkContactOnlineStatus() { viewModel.refreshOnlineStatus() }

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
        lifecycleScope.launch { viewModel.updateContactOnline( isOnline) }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.messages.collect { messages ->
                val layoutManager = binding.rvMessages.layoutManager as LinearLayoutManager
                val oldCount = messageAdapter.itemCount
                val wasNearBottom = layoutManager.findLastVisibleItemPosition() >= oldCount - 3
                messageAdapter.submitList(messages)
                binding.rvMessages.post {
                    val newCount = messageAdapter.itemCount
                    if (newCount > 0 && (wasNearBottom || oldCount == 0)) {
                        binding.rvMessages.scrollToPosition(newCount - 1)
                    }
                }
            }
        }
        lifecycleScope.launch {
            viewModel.sessionReady.collect { ready ->
                binding.etMessageInput.isEnabled = ready
                binding.btnSend.isEnabled = ready
            }
        }
        lifecycleScope.launch {
            viewModel.peerOnline.collect { updateOnlineStatus(it ?: false) }
        }
        lifecycleScope.launch {
            viewModel.peerTyping.collect { updateTypingIndicator(it) }
        }
    }

    private fun handleUserTyping() { viewModel.onUserTyping() }

    private fun sendTypingStopEvent() { viewModel.sendTypingStop() }

    private fun sendPendingMessages() { viewModel.resendPending() }

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
            viewModel.resetAttachmentProgress(message.messageId)
            viewModel.retryMessage(message)
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
        lifecycleScope.launch { viewModel.deleteMessage(message) }
    }

    private fun scrollToMessage(messageId: String) {
        lifecycleScope.launch {
            val position = messageAdapter.getPositionByMessageId(messageId)
            if (position != -1) {
                binding.rvMessages.scrollToPosition(position)
                highlightMessage(position)
            } else {
                val messageFromDb = viewModel.getMessageById(messageId)
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
        viewModel.unbindSocket()
        typingStopJob?.cancel()
        sendTypingStopEvent()
        currentOpenChatId = null

        // 🌟 Полностью уничтожаем аниматор при закрытии экрана чата
        typingAnimator?.cancel()
        typingAnimator = null
    }

    override fun onStop() {
        super.onStop()
        currentOpenChatId = null
    }
}