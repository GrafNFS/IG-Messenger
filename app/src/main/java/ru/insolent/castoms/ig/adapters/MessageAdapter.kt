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


class MessageAdapter : RecyclerView.Adapter<MessageAdapter.MessageViewHolder>() {

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
        messages.clear()
        messages.addAll(newMessages)
        notifyDataSetChanged()
    }

    fun getMessageAt(position: Int): MessageEntity? {
        return if (position in messages.indices) messages[position].message else null
    }

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

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val item = messages[position]
        val message = item.message
        val attachments = item.attachments
        val context = holder.itemView.context

        if (message.content.isNotEmpty()) {
            holder.tvText.visibility = View.VISIBLE
            holder.tvText.text = message.content

            // 🌟 АВТОПОДСВЕТКА И КЛИКАБЕЛЬНОСТЬ ССЫЛОК ВНУТРИ ТЕКСТА
            android.text.util.Linkify.addLinks(holder.tvText, android.text.util.Linkify.WEB_URLS)
            // Заставляем TextView правильно обрабатывать переходы по ссылкам
            holder.tvText.movementMethod = android.text.method.LinkMovementMethod.getInstance()

            // 🌟 ИСПРАВЛЕНИЕ: Пробрасываем долгий клик из TextView наверх, в messageContainer
            holder.tvText.setOnLongClickListener {
                holder.messageContainer.performLongClick()
            }

            // Настраиваем цвет подсвеченной ссылки, чтобы она не сливалась с фоном пузыря
            if (!message.isIncoming) {
                // Для ваших отправленных сообщений (на синем фоне) делаем ссылку, например, желтой или светло-голубой
                holder.tvText.setLinkTextColor(android.graphics.Color.parseColor("#80DEEA"))
            } else {
                // Для входящих сообщений (на светлом фоне) делаем ссылку стандартной синей
                holder.tvText.setLinkTextColor(android.graphics.Color.BLUE)
            }
        } else {
            holder.tvText.visibility = View.GONE
        }
        holder.tvTime.text = formatTimestamp(message.timestamp)

        // 🌟 Сброс состояния аудио-контейнера перед проверкой
        holder.audioContainer.visibility = View.GONE
        // 🌟 Сбрасываем видимость контейнера превью ссылки для переиспользуемых ViewHolder
        holder.linkPreviewContainer.visibility = View.GONE
        holder.ivLinkPreview.visibility = View.GONE

        // Проверяем, есть ли сохраненное превью ссылки в этом сообщении
        if (message.linkTitle != null || message.linkDescription != null) {
            holder.linkPreviewContainer.visibility = View.VISIBLE

            // (Ваш код настройки цветов и текста...)
            holder.tvLinkTitle.text = message.linkTitle ?: ""
            holder.tvLinkDescription.text = message.linkDescription ?: ""

            // 🌟 ДОБАВЛЯЕМ КЛИК ПО КАРТОЧКЕ ПРЕВЬЮ
            holder.linkPreviewContainer.setOnClickListener {
                // Вытаскиваем ссылку из текста сообщения
                val url = ru.insolent.castoms.ig.utils.LinkPreviewHelper.extractUrl(message.content)
                if (url != null) {
                    try {
                        // Создаем системный Intent для открытия ссылки в браузере
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        android.widget.Toast.makeText(context, "Не удалось открыть ссылку", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }

            // Делаем контейнер визуально кликабельным (добавляем эффект волны при тапе)
            holder.linkPreviewContainer.isClickable = true
            holder.linkPreviewContainer.foreground = context.getDrawable(android.R.drawable.list_selector_background)

            // Если у ссылки есть картинка — грузим её через Picasso
            if (!message.linkImageUrl.isNullOrEmpty()) {
                holder.ivLinkPreview.visibility = View.VISIBLE
                Picasso.get()
                    .load(message.linkImageUrl)
                    .fit()
                    .centerCrop()
                    .into(holder.ivLinkPreview)
            }
        }

        // Проверка: является ли сообщение голосовым
        if (message.messageType == "audio") {
            holder.audioContainer.visibility = View.VISIBLE
            holder.tvText.visibility = View.GONE

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
                        holder.audioWaveform.setSampleFrom(amplitudesList.toIntArray())
                    } catch (e: Exception) {
                        Log.e(TAG, "Ошибка чтения амплитуды звука", e)
                    }

                    // Восстанавливаем состояние, если это сообщение играет прямо сейчас
                    if (currentlyPlayingMessageId == message.messageId) {
                        currentPlayingWaveform = holder.audioWaveform
                        currentPlayingDurationText = holder.tvAudioDuration
                        currentPlayButton = holder.btnPlayAudio
                        holder.btnPlayAudio.setImageResource(android.R.drawable.ic_media_pause)
                    } else {
                        holder.btnPlayAudio.setImageResource(android.R.drawable.ic_media_play)
                        holder.audioWaveform.progress = 0f // Сбрасываем закрашивание полосок
                        holder.tvAudioDuration.text = "0:00"
                    }

                    holder.btnPlayAudio.setOnClickListener {
                        toggleAudioPlayback(
                            messageId = message.messageId,
                            file = file,
                            btnPlay = holder.btnPlayAudio,
                            waveform = holder.audioWaveform, // Передаем волну
                            tvDuration = holder.tvAudioDuration
                        )
                    }
                }
            }
        }

        // ✅ Логика отображения картинок/видео
        if (attachments.isNotEmpty() && message.messageType != "audio") {
            holder.rvMessageImages.visibility = View.VISIBLE

            val rvLayoutParams = holder.rvMessageImages.layoutParams
            rvLayoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            holder.rvMessageImages.layoutParams = rvLayoutParams

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

            holder.rvMessageImages.layoutManager = layoutManager

            // 🌟 4. Передаем в конструктор MessageImagesAdapter три лямбды вместо двух
            holder.rvMessageImages.adapter = MessageImagesAdapter(
                attachments = attachments,
                onClick = { clickedAttachments, pos ->
                    mediaClickListener?.onMediaClick(clickedAttachments, pos)
                },
                onRetryClick = { failedAttachment ->
                    // Перенаправляем событие в ChatActivity
                    retryDownloadListener?.onRetryClick(failedAttachment)
                }
            )

            holder.rvMessageImages.layoutManager = layoutManager
            holder.rvMessageImages.adapter = MessageImagesAdapter(
                attachments = attachments,
                onClick = { clickedAttachments, pos ->
                    mediaClickListener?.onMediaClick(clickedAttachments, pos)
                },
                onRetryClick = { failedAttachment ->
                    retryDownloadListener?.onRetryClick(failedAttachment)
                }
            )

            holder.rvMessageImages.requestLayout()
            holder.tvText.setPadding(dpToPx(context, 16), dpToPx(context, 2), dpToPx(context, 16), dpToPx(context, 4))
        } else {
            holder.rvMessageImages.visibility = View.GONE
            if (message.messageType != "audio") {
                holder.tvText.setPadding(dpToPx(context, 16), dpToPx(context, 10), dpToPx(context, 16), dpToPx(context, 4))
            }
        }

        // ✅ Обработка цитаты
        if (message.replyToMessageId != null && message.replyToContent != null) {
            holder.quoteContainer.visibility = View.VISIBLE
            holder.tvQuoteSenderName.text = message.replyToSenderName ?: "Неизвестно"
            holder.tvQuoteContent.text = message.replyToContent

            holder.quoteContainer.setOnClickListener {
                quoteClickListener?.invoke(message.replyToMessageId)
            }
            holder.quoteContainer.isClickable = true
            holder.quoteContainer.foreground =
                context.getDrawable(android.R.drawable.list_selector_background)

            if (message.isIncoming) {
                holder.quoteContainer.setBackgroundResource(R.drawable.bg_message_received_in)
            } else {
                holder.quoteContainer.setBackgroundResource(R.drawable.bg_message_received_sent)
            }
        } else {
            holder.quoteContainer.visibility = View.GONE
        }

        // ✅ Настройка ограничений (справа/слева)
        val params = holder.messageContainer.layoutParams as ConstraintLayout.LayoutParams

        if (!message.isIncoming) {
            params.startToStart = ConstraintLayout.LayoutParams.UNSET
            params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID

            holder.ivStatus.visibility = View.VISIBLE
            when (message.status) {
                MessageStatus.SENDING -> holder.ivStatus.setImageResource(R.drawable.ic_status_sending)
                MessageStatus.SENT -> holder.ivStatus.setImageResource(R.drawable.ic_status_sent)
                MessageStatus.DELIVERED -> holder.ivStatus.setImageResource(R.drawable.ic_status_delivered)
                MessageStatus.READ -> holder.ivStatus.setImageResource(R.drawable.ic_status_read)
                MessageStatus.FAILED -> holder.ivStatus.setImageResource(R.drawable.ic_status_failed)
            }

            holder.messageContainer.setBackgroundResource(R.drawable.bg_message_sent)
            holder.tvText.setTextColor(ContextCompat.getColor(context, R.color.white))
            // Красим элементы аудио под стиль отправленного сообщения
            holder.btnPlayAudio.backgroundTintList = ContextCompat.getColorStateList(context, R.color.white)
            holder.btnPlayAudio.imageTintList = ContextCompat.getColorStateList(context, R.color.black)
        } else {
            params.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            params.endToEnd = ConstraintLayout.LayoutParams.UNSET

            holder.ivStatus.visibility = View.GONE
            holder.messageContainer.setBackgroundResource(R.drawable.bg_message_incoming)
            holder.tvText.setTextColor(ContextCompat.getColor(context, R.color.black))
            // Красим элементы аудио под стиль полученного сообщения
            holder.btnPlayAudio.backgroundTintList = ContextCompat.getColorStateList(context, R.color.black)
            holder.btnPlayAudio.imageTintList = ContextCompat.getColorStateList(context, R.color.white)
        }

        holder.messageContainer.layoutParams = params
        (holder.tvTime.layoutParams as LinearLayout.LayoutParams).gravity = android.view.Gravity.END

        holder.messageContainer.setOnClickListener {
            actionListener?.let { listener -> showPopupMenu(it, message, attachments, listener) }
        }
    }

    // 🌟 Логика переключения воспроизведения аудио-файла
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
        val position = messages.indexOfFirst { it.message.messageId == messageId }
        if (position != -1) {
            val item = messages[position]
            messages[position] = item.copy(message = item.message.copy(status = newStatus))
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

    override fun getItemCount() = messages.size
    private fun formatTimestamp(timestamp: Long): String {
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
    }

    fun getPositionByMessageId(messageId: String): Int {
        return messages.indexOfFirst { it.message.messageId == messageId }
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
}