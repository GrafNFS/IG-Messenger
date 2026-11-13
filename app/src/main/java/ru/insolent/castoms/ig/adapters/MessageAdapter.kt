package ru.insolent.castoms.ig.adapters

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.squareup.picasso.Picasso
import ru.insolent.castoms.ig.R
import ru.insolent.castoms.ig.database.AttachmentEntity
import ru.insolent.castoms.ig.database.MessageEntity
import ru.insolent.castoms.ig.database.MessageStatus
import ru.insolent.castoms.ig.database.MessageWithAttachments
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.util.Log
import android.widget.Toast
import com.linc.amplituda.Amplituda
import com.masoudss.lib.WaveformSeekBar
import java.util.Calendar


class MessageAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val chatItems = mutableListOf<ChatItem>()
    companion object {
        private const val TYPE_MESSAGE = 0
        private const val TYPE_DATE_HEADER = 1
    }
    private val messages = mutableListOf<MessageWithAttachments>()
    private val TAG = "MessageAdapter"

    // 🌟 Поля для управления общим плеером голосовых сообщений
    private var mediaPlayer: MediaPlayer? = null
    private var currentlyPlayingMessageId: String? = null
    private var currentPlayingWaveform: WaveformSeekBar? = null // 🌟 СТАЛО
    private var currentPlayingDurationText: TextView? = null
    private var currentPlayButton: ImageView? = null
    private val handler = Handler(Looper.getMainLooper())
    private var updateSeekBarRunnable: Runnable? = null

    private var quoteClickListener: ((replyToMessageId: String) -> Unit)? = null
    private var mediaClickListener: OnMediaClickListener? = null
    private var actionListener: OnMessageActionListener? = null
    private var retryDownloadListener: OnRetryDownloadListener? = null

    fun setQuoteClickListener(quoteClickListener: (replyToMessageId: String) -> Unit) {
        this.quoteClickListener = quoteClickListener
    }

    fun setMediaClickListener(listener: OnMediaClickListener) {
        this.mediaClickListener = listener
    }

    fun setActionListener(listener: OnMessageActionListener) {
        this.actionListener = listener
    }

    // 🌟 2. Метод установки слушателя для перезапуска загрузки (вызовем в ChatActivity)
    fun setRetryDownloadListener(listener: OnRetryDownloadListener) {
        this.retryDownloadListener = listener
    }

    interface OnMessageActionListener {
        fun onReply(message: MessageEntity)
        fun onCopu(message: MessageEntity)
        fun onEdit(message: MessageEntity)
        fun onRefresh(message: MessageEntity, attachments: List<AttachmentEntity>)
        fun onDelete(message: MessageEntity)
    }

    interface OnMediaClickListener {
        fun onMediaClick(attachments: List<AttachmentEntity>, position: Int)
    }

    // 🌟 3. Интерфейс для обработки повторного скачивания медиа
    interface OnRetryDownloadListener {
        fun onRetryClick(attachment: AttachmentEntity)
    }

    // 🌟 ОПРЕДЕЛЯЕМ ВИД ЯЧЕЙКИ НА ЭКРАНЕ
    override fun getItemViewType(position: Int): Int {
        return when (chatItems[position]) {
            is ChatItem.Message -> TYPE_MESSAGE
            is ChatItem.DateHeader -> TYPE_DATE_HEADER
        }
    }

    fun addMessage(
        messageId: String,
        text: String,
        isMe: Boolean,
        time: String,
        status: MessageStatus,
        messageType: String = "text",
        attachments: List<AttachmentEntity>? = null
    ) {
        val newMessage = MessageEntity(
            chatId = "",
            messageId = messageId,
            senderId = if (isMe) "me" else "other",
            content = text,
            encryptedPayload = "",
            timestamp = System.currentTimeMillis(),
            status = status,
            isIncoming = !isMe,
            messageType = messageType,
            replyToMessageId = null,
            replyToSenderName = null,
            replyToContent = null
        )

        messages.add(MessageWithAttachments(newMessage, attachments ?: emptyList()))
        notifyItemInserted(messages.size - 1)
    }

    fun submitList(newMessages: List<MessageWithAttachments>) {
        chatItems.clear()
        if (newMessages.isEmpty()) {
            notifyDataSetChanged()
            return
        }

        var lastDateString = ""

        for (item in newMessages) {
            val currentDateString = getFormattedDateForHeader(item.message.timestamp)

            // Если день этого сообщения отличается от предыдущего — вставляем плашку даты!
            if (currentDateString != lastDateString) {
                chatItems.add(ChatItem.DateHeader(currentDateString))
                lastDateString = currentDateString
            }

            chatItems.add(ChatItem.Message(item))
        }
        notifyDataSetChanged()
    }

    fun getMessageAt(position: Int): MessageEntity? {
        if (position in chatItems.indices) {
            val item = chatItems[position]
            if (item is ChatItem.Message) return item.messageWithAttachments.message
        }
        return null
    }

    fun getPositionByMessageId(messageId: String): Int {
        return chatItems.indexOfFirst {
            it is ChatItem.Message && it.messageWithAttachments.message.messageId == messageId
        }
    }

    override fun getItemCount() = chatItems.size

    class MessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvText: TextView = view.findViewById(R.id.tvMessageText)
        val tvTime: TextView = view.findViewById(R.id.tvTime)
        val messageContainer: LinearLayout = view.findViewById(R.id.messageContainer)
        val ivStatus: ImageView = view.findViewById(R.id.ivStatus)

        val quoteContainer: LinearLayout = view.findViewById(R.id.quoteContainer)
        val tvQuoteSenderName: TextView = view.findViewById(R.id.tvQuoteSenderName)
        val tvQuoteContent: TextView = view.findViewById(R.id.tvQuoteContent)
        val rvMessageImages: RecyclerView = view.findViewById(R.id.rvMessageImages)

        val linkPreviewContainer: LinearLayout = view.findViewById(R.id.linkPreviewContainer)
        val ivLinkPreview: ImageView = view.findViewById(R.id.ivLinkPreview)
        val tvLinkTitle: TextView = view.findViewById(R.id.tvLinkTitle)
        val tvLinkDescription: TextView = view.findViewById(R.id.tvLinkDescription)
        val audioContainer: LinearLayout = view.findViewById(R.id.audioContainer)
        val btnPlayAudio: ImageView = view.findViewById(R.id.btnPlayAudio)
        val audioWaveform: WaveformSeekBar = view.findViewById(R.id.audioWaveform)
        val tvAudioDuration: TextView = view.findViewById(R.id.tvAudioDuration)
    }

    // 🌟 Холдер для плашки даты
    class DateHeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvDateHeader: TextView = view.findViewById(R.id.tvDateHeader)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        if (viewType == TYPE_DATE_HEADER) {
            val view = inflater.inflate(R.layout.item_date_header, parent, false)
            return DateHeaderViewHolder(view)
        } else {
            val view = inflater.inflate(R.layout.item_message, parent, false)
            return MessageViewHolder(view)
        }
    }

    override fun onBindViewHolder(rawHolder: RecyclerView.ViewHolder, position: Int) {
        val chatItem = chatItems[position]

        // 🌟 1. ЕСЛИ ЭТО ПЛАШКА ДАТЫ — Отрисовываем текст дня ("Сегодня", "Вчера" или дату)
        if (rawHolder is DateHeaderViewHolder && chatItem is ChatItem.DateHeader) {
            rawHolder.tvDateHeader.text = chatItem.dateText
            return
        }

        // 🌟 2. ЕСЛИ ЭТО ОБЫЧНОЕ СООБЩЕНИЕ — Выполняем всю вашу стандартную логику
        if (rawHolder is MessageViewHolder && chatItem is ChatItem.Message) {
            val item = chatItem.messageWithAttachments
            val message = item.message
            val attachments = item.attachments
            val context = rawHolder.itemView.context

            if (message.content.isNotEmpty()) {
                rawHolder.tvText.visibility = View.VISIBLE
                rawHolder.tvText.text = message.content

                // 🌟 АВТОПОДСВЕТКА И КЛИКАБЕЛЬНОСТЬ ССЫЛОК ВНУТРИ ТЕКСТА
                android.text.util.Linkify.addLinks(
                    rawHolder.tvText,
                    android.text.util.Linkify.WEB_URLS
                )
                // Заставляем TextView правильно обрабатывать переходы по ссылкам
                rawHolder.tvText.movementMethod = android.text.method.LinkMovementMethod.getInstance()

                // 🌟 ИСПРАВЛЕНИЕ: Пробрасываем долгий клик из TextView наверх, в messageContainer
                rawHolder.tvText.setOnLongClickListener {
                    rawHolder.messageContainer.performLongClick()
                }

                // Настраиваем цвет подсвеченной ссылки, чтобы она не сливалась с фоном пузыря
                if (!message.isIncoming) {
                    // Для ваших отправленных сообщений (на синем фоне) делаем ссылку, например, желтой или светло-голубой
                    rawHolder.tvText.setLinkTextColor(android.graphics.Color.parseColor("#80DEEA"))
                } else {
                    // Для входящих сообщений (на светлом фоне) делаем ссылку стандартной синей
                    rawHolder.tvText.setLinkTextColor(android.graphics.Color.BLUE)
                }
            } else {
                rawHolder.tvText.visibility = View.GONE
            }
            rawHolder.tvTime.text = formatTimestamp(message.timestamp)

            // 🌟 Сброс состояния аудио-контейнера перед проверкой
            rawHolder.audioContainer.visibility = View.GONE
            // 🌟 Сбрасываем видимость контейнера превью ссылки для переиспользуемых ViewHolder
            rawHolder.linkPreviewContainer.visibility = View.GONE
            rawHolder.ivLinkPreview.visibility = View.GONE

            // Проверяем, есть ли сохраненное превью ссылки в этом сообщении
            if (message.linkTitle != null || message.linkDescription != null) {
                rawHolder.linkPreviewContainer.visibility = View.VISIBLE

                // (Ваш код настройки цветов и текста...)
                rawHolder.tvLinkTitle.text = message.linkTitle ?: ""
                rawHolder.tvLinkDescription.text = message.linkDescription ?: ""

                // 🌟 ДОБАВЛЯЕМ КЛИК ПО КАРТОЧКЕ ПРЕВЬЮ
                rawHolder.linkPreviewContainer.setOnClickListener {
                    // Вытаскиваем ссылку из текста сообщения
                    val url =
                        ru.insolent.castoms.ig.utils.LinkPreviewHelper.extractUrl(message.content)
                    if (url != null) {
                        try {
                            // Создаем системный Intent для открытия ссылки в браузере
                            val intent = android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(url)
                            )
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            android.widget.Toast.makeText(
                                context,
                                "Не удалось открыть ссылку",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }

                // Делаем контейнер визуально кликабельным (добавляем эффект волны при тапе)
                rawHolder.linkPreviewContainer.isClickable = true
                rawHolder.linkPreviewContainer.foreground =
                    context.getDrawable(android.R.drawable.list_selector_background)

                // Если у ссылки есть картинка — грузим её через Picasso
                if (!message.linkImageUrl.isNullOrEmpty()) {
                    rawHolder.ivLinkPreview.visibility = View.VISIBLE
                    Picasso.get()
                        .load(message.linkImageUrl)
                        .fit()
                        .centerCrop()
                        .into(rawHolder.ivLinkPreview)
                }
            }

            // Проверка: является ли сообщение голосовым
            if (message.messageType == "audio") {
                rawHolder.audioContainer.visibility = View.VISIBLE
                rawHolder.tvText.visibility = View.GONE

                val audioAttachment = attachments.firstOrNull()

                if (audioAttachment != null && !audioAttachment.localFilePath.isNullOrEmpty()) {
                    val file = File(audioAttachment.localFilePath)
                    if (file.exists()) {

                        // 🌟 ГЕНЕРАЦИЯ ВОЛНЫ: Используем Amplituda для считывания высот звука из локального файла
                        try {
                            val amplituda = Amplituda(context)
                            // Считываем аудиофайл
                            val output = amplituda.processAudio(file).get()

                            // Получаем массив чисел (высот) звука
                            val amplitudesList = output.amplitudesAsList()

                            // Передаем их в нашу кастомную вьюшку волны
                            rawHolder.audioWaveform.setSampleFrom(amplitudesList.toIntArray())
                        } catch (e: Exception) {
                            Log.e(TAG, "Ошибка чтения амплитуды звука", e)
                        }

                        // Восстанавливаем состояние, если это сообщение играет прямо сейчас
                        if (currentlyPlayingMessageId == message.messageId) {
                            currentPlayingWaveform = rawHolder.audioWaveform
                            currentPlayingDurationText = rawHolder.tvAudioDuration
                            currentPlayButton = rawHolder.btnPlayAudio
                            rawHolder.btnPlayAudio.setImageResource(android.R.drawable.ic_media_pause)
                        } else {
                            rawHolder.btnPlayAudio.setImageResource(android.R.drawable.ic_media_play)
                            rawHolder.audioWaveform.progress = 0f // Сбрасываем закрашивание полосок
                            rawHolder.tvAudioDuration.text = "0:00"
                        }

                        rawHolder.btnPlayAudio.setOnClickListener {
                            toggleAudioPlayback(
                                messageId = message.messageId,
                                file = file,
                                btnPlay = rawHolder.btnPlayAudio,
                                waveform = rawHolder.audioWaveform, // Передаем волну
                                tvDuration = rawHolder.tvAudioDuration
                            )
                        }
                    }
                }
            }

            // ✅ Логика отображения картинок/видео
            if (attachments.isNotEmpty() && message.messageType != "audio") {
                rawHolder.rvMessageImages.visibility = View.VISIBLE

                val rvLayoutParams = rawHolder.rvMessageImages.layoutParams
                rvLayoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                rawHolder.rvMessageImages.layoutParams = rvLayoutParams

                val layoutManager = GridLayoutManager(context, 2)

                layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int): Int {
                        val total = attachments.size
                        return when {
                            total == 1 -> 2
                            total % 2 != 0 && position == total - 1 -> 2
                            else -> 1
                        }
                    }
                }

                rawHolder.rvMessageImages.layoutManager = layoutManager
                rawHolder.rvMessageImages.adapter = MessageImagesAdapter(
                    attachments = attachments,
                    onClick = { clickedAttachments, pos ->
                        mediaClickListener?.onMediaClick(clickedAttachments, pos)
                    },
                    onRetryClick = { failedAttachment ->
                        retryDownloadListener?.onRetryClick(failedAttachment)
                    }
                )

                rawHolder.rvMessageImages.requestLayout()
                rawHolder.tvText.setPadding(
                    dpToPx(context, 16),
                    dpToPx(context, 2),
                    dpToPx(context, 16),
                    dpToPx(context, 4)
                )
            } else {
                rawHolder.rvMessageImages.visibility = View.GONE
                if (message.messageType != "audio") {
                    rawHolder.tvText.setPadding(
                        dpToPx(context, 16),
                        dpToPx(context, 10),
                        dpToPx(context, 16),
                        dpToPx(context, 4)
                    )
                }
            }

            // ✅ Обработка цитаты
            if (message.replyToMessageId != null && message.replyToContent != null) {
                rawHolder.quoteContainer.visibility = View.VISIBLE
                rawHolder.tvQuoteSenderName.text = message.replyToSenderName ?: "Неизвестно"
                rawHolder.tvQuoteContent.text = message.replyToContent

                rawHolder.quoteContainer.setOnClickListener {
                    quoteClickListener?.invoke(message.replyToMessageId)
                }
                rawHolder.quoteContainer.isClickable = true
                rawHolder.quoteContainer.foreground =
                    context.getDrawable(android.R.drawable.list_selector_background)

                if (message.isIncoming) {
                    rawHolder.quoteContainer.setBackgroundResource(R.drawable.bg_message_received_in)
                } else {
                    rawHolder.quoteContainer.setBackgroundResource(R.drawable.bg_message_received_sent)
                }
            } else {
                rawHolder.quoteContainer.visibility = View.GONE
            }

            // ✅ Настройка ограничений (справа/слева)
            val params = rawHolder.messageContainer.layoutParams as ConstraintLayout.LayoutParams

            if (!message.isIncoming) {
                params.startToStart = ConstraintLayout.LayoutParams.UNSET
                params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID

                rawHolder.ivStatus.visibility = View.VISIBLE
                when (message.status) {
                    MessageStatus.SENDING -> rawHolder.ivStatus.setImageResource(R.drawable.ic_status_sending)
                    MessageStatus.SENT -> rawHolder.ivStatus.setImageResource(R.drawable.ic_status_sent)
                    MessageStatus.DELIVERED -> rawHolder.ivStatus.setImageResource(R.drawable.ic_status_delivered)
                    MessageStatus.READ -> rawHolder.ivStatus.setImageResource(R.drawable.ic_status_read)
                    MessageStatus.FAILED -> rawHolder.ivStatus.setImageResource(R.drawable.ic_status_failed)
                }

                rawHolder.messageContainer.setBackgroundResource(R.drawable.bg_message_sent)
                rawHolder.tvText.setTextColor(ContextCompat.getColor(context, R.color.white))
                // Красим элементы аудио под стиль отправленного сообщения
                rawHolder.btnPlayAudio.backgroundTintList =
                    ContextCompat.getColorStateList(context, R.color.white)
                rawHolder.btnPlayAudio.imageTintList =
                    ContextCompat.getColorStateList(context, R.color.black)
            } else {
                params.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                params.endToEnd = ConstraintLayout.LayoutParams.UNSET

                rawHolder.ivStatus.visibility = View.GONE
                rawHolder.messageContainer.setBackgroundResource(R.drawable.bg_message_incoming)
                rawHolder.tvText.setTextColor(ContextCompat.getColor(context, R.color.black))
                // Красим элементы аудио под стиль полученного сообщения
                rawHolder.btnPlayAudio.backgroundTintList =
                    ContextCompat.getColorStateList(context, R.color.black)
                rawHolder.btnPlayAudio.imageTintList =
                    ContextCompat.getColorStateList(context, R.color.white)
            }

            rawHolder.messageContainer.layoutParams = params
            (rawHolder.tvTime.layoutParams as LinearLayout.LayoutParams).gravity =
                android.view.Gravity.END

            rawHolder.messageContainer.setOnClickListener {
                actionListener?.let { listener ->
                    showPopupMenu(
                        it,
                        message,
                        attachments,
                        listener
                    )
                }
            }
        }
    }

    // 🌟 КРАСИВОЕ ФОРМАТИРОВАНИЕ ДАТЫ ("Сегодня", "Вчера" или "24 сентября")
    private fun getFormattedDateForHeader(timestamp: Long): String {
        val messageCalendar = Calendar.getInstance().apply { timeInMillis = timestamp }
        val nowCalendar = Calendar.getInstance()
        return when {
            isSameDay(messageCalendar, nowCalendar) -> "Сегодня"
            isYesterday(messageCalendar, nowCalendar) -> "Вчера"
            else -> SimpleDateFormat("d MMMM", Locale("ru")).format(Date(timestamp))
        }
    }

    private fun isSameDay(cal1: Calendar, cal2: Calendar): Boolean {
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
                cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
    }
    private fun isYesterday(messageCal: Calendar, nowCal: Calendar): Boolean {
        val yesterdayCal = Calendar.getInstance().apply {
            timeInMillis = nowCal.timeInMillis
            add(Calendar.DAY_OF_YEAR, -1)
        }
        return isSameDay(messageCal, yesterdayCal)
    }

    // 🌟 Логика переключения воспроизведения аудио-файла (Переписана под Waveform)
    private fun toggleAudioPlayback(messageId: String, file: File, btnPlay: ImageView, waveform: com.masoudss.lib.WaveformSeekBar, tvDuration: TextView) {
        if (currentlyPlayingMessageId == messageId) {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.pause()
                btnPlay.setImageResource(android.R.drawable.ic_media_play)
            } else {
                mediaPlayer?.start()
                btnPlay.setImageResource(android.R.drawable.ic_media_pause)
                startSeekBarUpdate()
            }
        } else {
            stopAudioPlayback()

            currentlyPlayingMessageId = messageId
            currentPlayingWaveform = waveform
            currentPlayingDurationText = tvDuration
            currentPlayButton = btnPlay

            mediaPlayer = MediaPlayer().apply {
                try {
                    setDataSource(file.absolutePath)
                    prepare()
                    // У WaveformSeekBar прогресс задается в процентах/долях,
                    // но для простоты мы будем использовать миллисекунды, выставив max плеера
                    start()

                    tvDuration.text = formatAudioTime(duration)
                    btnPlay.setImageResource(android.R.drawable.ic_media_pause)
                    startSeekBarUpdate()
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Ошибка запуска MediaPlayer", e)
                    stopAudioPlayback()
                }
            }

            mediaPlayer?.setOnCompletionListener {
                stopAudioPlayback()
            }
        }
    }

    // 🌟 Корутина/Runnable плавного закрашивания полосок звуковой волны
    private fun startSeekBarUpdate() {
        updateSeekBarRunnable?.let { handler.removeCallbacks(it) }
        updateSeekBarRunnable = object : Runnable {
            override fun run() {
                mediaPlayer?.let { player ->
                    if (player.isPlaying) {
                        val totalDuration = player.duration.toFloat()
                        if (totalDuration > 0) {
                            // 🌟 Вычисляем процент прослушивания (от 0 до 100) и передаем в WaveformSeekBar
                            val progressPercent = (player.currentPosition.toFloat() / totalDuration) * 100
                            currentPlayingWaveform?.progress = progressPercent
                        }
                        currentPlayingDurationText?.text = formatAudioTime(player.currentPosition)
                        handler.postDelayed(this, 50) // 50 мс для ультра-плавного закрашивания полосок
                    }
                }
            }
        }
        handler.post(updateSeekBarRunnable!!)
    }

    private fun formatAudioTime(ms: Int): String {
        val seconds = (ms / 1000) % 60
        val minutes = (ms / (1000 * 60)) % 60
        return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }

    // 🌟 Полный сброс состояния волнового плеера
    fun stopAudioPlayback() {
        updateSeekBarRunnable?.let { handler.removeCallbacks(it) }
        mediaPlayer?.let {
            if (it.isPlaying) it.stop()
            it.release()
        }
        mediaPlayer = null

        currentPlayButton?.setImageResource(android.R.drawable.ic_media_play)
        currentPlayingWaveform?.progress = 0f // Очищаем закрашивание
        currentPlayingDurationText?.text = "0:00"

        currentlyPlayingMessageId = null
        currentPlayingWaveform = null
        currentPlayingDurationText = null
        currentPlayButton = null
    }

    private fun dpToPx(context: Context, dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }

    fun updateDownloadProgressForAttachment(messageId: String, fileId: String, progress: Int) {
        val index = messages.indexOfFirst { it.message.messageId == messageId }
        if (index != -1) {
            val item = messages[index]
            val updatedAttachments = item.attachments.map {
                if (it.fileId == fileId) it.copy(downloadProgress = progress) else it
            }
            messages[index] = item.copy(attachments = updatedAttachments)
            notifyItemChanged(index)
        }
    }

    fun updateMessageStatus(messageId: String, newStatus: MessageStatus) {
        val position = chatItems.indexOfFirst {
            it is ChatItem.Message && it.messageWithAttachments.message.messageId == messageId
        }
        if (position != -1) {
            val item = (chatItems[position] as ChatItem.Message).messageWithAttachments
            chatItems[position] = ChatItem.Message(
                item.copy(message = item.message.copy(status = newStatus))
            )
            notifyItemChanged(position)
        }
    }

    private fun showPopupMenu(view: View, message: MessageEntity, attachments: List<AttachmentEntity>, listener: OnMessageActionListener) {
        val popup = PopupMenu(view.context, view)
        if (message.status != MessageStatus.FAILED) {
            popup.menu.add("Ответить")
            popup.menu.add("Копировать")

            // Редактировать и удалять только СВОИ сообщения (!isIncoming)
            if (!message.isIncoming) {
                popup.menu.add("Редактировать")
                popup.menu.add("Удалить")
            }
        }
        else {
            popup.menu.add("Повторить")
            popup.menu.add("Удалить")
        }

        popup.setOnMenuItemClickListener { item ->
            when (item.title) {
                "Ответить" -> listener.onReply(message)
                "Копировать" -> listener.onCopu(message)
                "Редактировать" -> listener.onEdit(message)
                "Повторить" -> listener.onRefresh(message, attachments)
                "Удалить" -> listener.onDelete(message)
            }
            true
        }
        popup.show()
    }

    private fun formatTimestamp(timestamp: Long): String {
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
    }

    fun addMessageToPosition(position: Int, messageWithAttachments: MessageWithAttachments) {
        if (position >= 0 && position <= messages.size) {
            messages.add(position, messageWithAttachments)
            notifyItemInserted(position)
        } else {
            // Если позиция невалидна, просто добавляем в конец
            messages.add(messageWithAttachments)
            notifyItemInserted(messages.size - 1)
        }
    }

    // 🌟 Обертка для элементов списка, чтобы объединить сообщения и даты
    sealed class ChatItem {
        data class Message(val messageWithAttachments: MessageWithAttachments) : ChatItem()
        data class DateHeader(val dateText: String) : ChatItem()
    }
}