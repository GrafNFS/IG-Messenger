package ru.insolent.castoms.ig.adapters

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
import com.linc.amplituda.Amplituda
import com.masoudss.lib.WaveformSeekBar
import java.util.Calendar


/**
 * ⚠️ КЛЮЧЕВЫЕ ИСПРАВЛЕНИЯ (были краши "Inconsistency detected"):
 * 1. Единый источник данных — chatItems. Старый параллельный список `messages`
 *    использовался для notify с ИНДЕКСАМИ ИЗ ДРУГОГО СПИСКА (со съехавшими
 *    плашками дат) — это и давал рассинхрон с RecyclerView.
 * 2. Все мутации + notify только на MAIN-потоке (mainGuard). Раньдаптера
 *    могли дергаться с потоков Socket.IO → падение при layout.
 * 3. submitList синхронизирует оба представления списка атомарно.
 */
class MessageAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val chatItems = mutableListOf<ChatItem>()
    // Зеркало чистых сообщений (без плашек дат) — для совместимости со старыми вызовами
    private val messages = mutableListOf<MessageWithAttachments>()

    companion object {
        private const val TYPE_MESSAGE = 0
        private const val TYPE_DATE_HEADER = 1
    }

    private val TAG = "MessageAdapter"

    // 🌟 Поля для управления общим плеером голосовых сообщений
    private var mediaPlayer: MediaPlayer? = null
    private var currentlyPlayingMessageId: String? = null
    private var currentPlayingWaveform: WaveformSeekBar? = null
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

    interface OnRetryDownloadListener {
        fun onRetryClick(attachment: AttachmentEntity)
    }

    // ============ ✅ ГЛАВНАЯ ЗАЩИТА: всё на main-потоке ============
    private inline fun mainGuard(crossinline body: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            body()
        } else {
            Handler(Looper.getMainLooper()).post { body() }
        }
    }

    override fun getItemViewType(position: Int): Int {
        return when (chatItems[position]) {
            is ChatItem.Message -> TYPE_MESSAGE
            is ChatItem.DateHeader -> TYPE_DATE_HEADER
        }
    }

    /** Легаси-метод мгновенной вставки (оптимистичный UI). Добавляет с плашкой даты при смене дня */
    fun addMessage(
        messageId: String,
        text: String,
        isMe: Boolean,
        time: String,
        status: MessageStatus,
        messageType: String = "text",
        attachments: List<AttachmentEntity>? = null
    ) {
        mainGuard {
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

            val mwa = MessageWithAttachments(newMessage, attachments ?: emptyList())
            messages.add(mwa)

            val dateStr = getFormattedDateForHeader(mwa.message.timestamp)
            val lastDateStr = (chatItems.lastOrNull() as? ChatItem.Message)
                ?.let { getFormattedDateForHeader(it.messageWithAttachments.message.timestamp) }
            if (lastDateStr != dateStr) {
                chatItems.add(ChatItem.DateHeader(dateStr))
            }
            chatItems.add(ChatItem.Message(mwa))
            notifyItemInserted(chatItems.size - 1)
        }
    }

    /** Полная перезагрузка списка из Room (вызывается из LiveData/Flow observer) */
    fun submitList(newMessages: List<MessageWithAttachments>) {
        mainGuard {
            chatItems.clear()
            messages.clear()
            messages.addAll(newMessages)

            if (newMessages.isEmpty()) {
                notifyDataSetChanged()
                return@mainGuard
            }

            var lastDateString = ""

            for (item in newMessages) {
                val currentDateString = getFormattedDateForHeader(item.message.timestamp)

                if (currentDateString != lastDateString) {
                    chatItems.add(ChatItem.DateHeader(currentDateString))
                    lastDateString = currentDateString
                }

                chatItems.add(ChatItem.Message(item))
            }
            notifyDataSetChanged()
        }
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
        // 🌟 Прогресс скачивания/загрузки аудио (безопасно: null, пока нет в layout)
        val pbAudioProgress: android.widget.ProgressBar? = view.findViewById(R.id.pbAudioProgress)
    }

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

        if (rawHolder is DateHeaderViewHolder && chatItem is ChatItem.DateHeader) {
            rawHolder.tvDateHeader.text = chatItem.dateText
            return
        }

        if (rawHolder is MessageViewHolder && chatItem is ChatItem.Message) {
            val item = chatItem.messageWithAttachments
            val message = item.message
            val attachments = item.attachments
            val context = rawHolder.itemView.context

            if (message.content.isNotEmpty()) {
                rawHolder.tvText.visibility = View.VISIBLE
                rawHolder.tvText.text = message.content

                android.text.util.Linkify.addLinks(
                    rawHolder.tvText,
                    android.text.util.Linkify.WEB_URLS
                )
                rawHolder.tvText.movementMethod = android.text.method.LinkMovementMethod.getInstance()

                rawHolder.tvText.setOnLongClickListener {
                    rawHolder.messageContainer.performLongClick()
                }

                if (!message.isIncoming) {
                    rawHolder.tvText.setLinkTextColor(android.graphics.Color.parseColor("#80DEEA"))
                } else {
                    rawHolder.tvText.setLinkTextColor(android.graphics.Color.BLUE)
                }
            } else {
                rawHolder.tvText.visibility = View.GONE
            }
            rawHolder.tvTime.text = formatTimestamp(message.timestamp)

            rawHolder.audioContainer.visibility = View.GONE
            rawHolder.linkPreviewContainer.visibility = View.GONE
            rawHolder.ivLinkPreview.visibility = View.GONE

            if (message.linkTitle != null || message.linkDescription != null) {
                rawHolder.linkPreviewContainer.visibility = View.VISIBLE

                rawHolder.tvLinkTitle.text = message.linkTitle ?: ""
                rawHolder.tvLinkDescription.text = message.linkDescription ?: ""

                rawHolder.linkPreviewContainer.setOnClickListener {
                    val url =
                        ru.insolent.castoms.ig.utils.LinkPreviewHelper.extractUrl(message.content)
                    if (url != null) {
                        try {
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

                rawHolder.linkPreviewContainer.isClickable = true
                rawHolder.linkPreviewContainer.foreground =
                    context.getDrawable(android.R.drawable.list_selector_background)

                if (!message.linkImageUrl.isNullOrEmpty()) {
                    rawHolder.ivLinkPreview.visibility = View.VISIBLE
                    Picasso.get()
                        .load(message.linkImageUrl)
                        .fit()
                        .centerCrop()
                        .into(rawHolder.ivLinkPreview)
                }
            }

            if (message.messageType == "audio") {
                rawHolder.audioContainer.visibility = View.VISIBLE
                rawHolder.tvText.visibility = View.GONE

                val audioAttachment = attachments.firstOrNull()

                // 🌟 Прогресс аудио: скачивание (входящие) / загрузка (исходящие)
                rawHolder.pbAudioProgress?.let { pb ->
                    if (audioAttachment == null) {
                        pb.visibility = View.GONE
                    } else {
                        val hasLocal = !audioAttachment.localFilePath.isNullOrEmpty() &&
                                File(audioAttachment.localFilePath).exists()
                        val dl = audioAttachment.downloadProgress
                        val ul = audioAttachment.uploadProgress
                        when {
                            hasLocal && (message.isIncoming || ul >= 100 || ul == 0 && !message.isIncoming && dl == 0) -> {
                                // Локальный файл есть: скрываем, если загрузка завершена или не начиналась
                                if (!message.isIncoming && ul in 1..99) {
                                    pb.visibility = View.VISIBLE
                                    pb.isIndeterminate = ul <= 0
                                    if (ul > 0) pb.progress = ul
                                } else {
                                    pb.visibility = View.GONE
                                }
                            }
                            !message.isIncoming && ul in 1..99 -> {
                                pb.visibility = View.VISIBLE
                                pb.isIndeterminate = false
                                pb.progress = ul
                            }
                            message.isIncoming && dl in 1..99 -> {
                                pb.visibility = View.VISIBLE
                                pb.isIndeterminate = dl <= 0
                                if (dl > 0) pb.progress = dl
                            }
                            dl == -1 -> pb.visibility = View.GONE
                            else -> pb.visibility = View.GONE
                        }
                    }
                }

                if (audioAttachment != null && !audioAttachment.localFilePath.isNullOrEmpty()) {
                    val file = File(audioAttachment.localFilePath)
                    if (file.exists()) {

                        try {
                            val amplituda = Amplituda(context)
                            val output = amplituda.processAudio(file).get()
                            val amplitudesList = output.amplitudesAsList()
                            rawHolder.audioWaveform.setSampleFrom(amplitudesList.toIntArray())
                        } catch (e: Exception) {
                            Log.e(TAG, "Ошибка чтения амплитуды звука", e)
                        }

                        if (currentlyPlayingMessageId == message.messageId) {
                            currentPlayingWaveform = rawHolder.audioWaveform
                            currentPlayingDurationText = rawHolder.tvAudioDuration
                            currentPlayButton = rawHolder.btnPlayAudio
                            rawHolder.btnPlayAudio.setImageResource(android.R.drawable.ic_media_pause)
                        } else {
                            rawHolder.btnPlayAudio.setImageResource(android.R.drawable.ic_media_play)
                            rawHolder.audioWaveform.progress = 0f
                            rawHolder.tvAudioDuration.text = "0:00"
                        }

                        rawHolder.btnPlayAudio.setOnClickListener {
                            toggleAudioPlayback(
                                messageId = message.messageId,
                                file = file,
                                btnPlay = rawHolder.btnPlayAudio,
                                waveform = rawHolder.audioWaveform,
                                tvDuration = rawHolder.tvAudioDuration
                            )
                        }
                    }
                }
            }

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

    private fun startSeekBarUpdate() {
        updateSeekBarRunnable?.let { handler.removeCallbacks(it) }
        updateSeekBarRunnable = object : Runnable {
            override fun run() {
                mediaPlayer?.let { player ->
                    if (player.isPlaying) {
                        val totalDuration = player.duration.toFloat()
                        if (totalDuration > 0) {
                            val progressPercent = (player.currentPosition.toFloat() / totalDuration) * 100
                            currentPlayingWaveform?.progress = progressPercent
                        }
                        currentPlayingDurationText?.text = formatAudioTime(player.currentPosition)
                        handler.postDelayed(this, 50)
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

    fun stopAudioPlayback() {
        updateSeekBarRunnable?.let { handler.removeCallbacks(it) }
        mediaPlayer?.let {
            if (it.isPlaying) it.stop()
            it.release()
        }
        mediaPlayer = null

        currentPlayButton?.setImageResource(android.R.drawable.ic_media_play)
        currentPlayingWaveform?.progress = 0f
        currentPlayingDurationText?.text = "0:00"

        currentlyPlayingMessageId = null
        currentPlayingWaveform = null
        currentPlayingDurationText = null
        currentPlayButton = null
    }

    private fun dpToPx(context: Context, dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }

    // ✅ ИСПРАВЛЕНО: позиция ищется в chatItems (было — в messages, индексы съезжали из-за плашек дат)
    fun updateDownloadProgressForAttachment(messageId: String, fileId: String, progress: Int) {
        mainGuard {
            val position = chatItems.indexOfFirst {
                it is ChatItem.Message && it.messageWithAttachments.message.messageId == messageId
            }
            if (position != -1) {
                val item = (chatItems[position] as ChatItem.Message).messageWithAttachments
                val updated = item.copy(
                    attachments = item.attachments.map {
                        if (it.fileId == fileId) it.copy(downloadProgress = progress) else it
                    }
                )
                chatItems[position] = ChatItem.Message(updated)
                messages.indexOfFirst { it.message.messageId == messageId }
                    .takeIf { it != -1 }
                    ?.let { messages[it] = updated }
                notifyItemChanged(position)
            }
        }
    }

    fun updateMessageStatus(messageId: String, newStatus: MessageStatus) {
        mainGuard {
            val position = chatItems.indexOfFirst {
                it is ChatItem.Message && it.messageWithAttachments.message.messageId == messageId
            }
            if (position != -1) {
                val item = (chatItems[position] as ChatItem.Message).messageWithAttachments
                val updated = item.copy(message = item.message.copy(status = newStatus))
                chatItems[position] = ChatItem.Message(updated)
                messages.indexOfFirst { it.message.messageId == messageId }
                    .takeIf { it != -1 }
                    ?.let { messages[it] = updated }
                notifyItemChanged(position)
            }
        }
    }

    private fun showPopupMenu(view: View, message: MessageEntity, attachments: List<AttachmentEntity>, listener: OnMessageActionListener) {
        val popup = PopupMenu(view.context, view)
        if (message.status != MessageStatus.FAILED) {
            popup.menu.add("Ответить")
            popup.menu.add("Копировать")

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

    // ✅ ИСПРАВЛЕНО: вставка идёт в chatItems (messages лишь зеркалится) — раньше
    // notifyItemInserted применялся к индексу из messages при dataset chatItems
    fun addMessageToPosition(position: Int, messageWithAttachments: MessageWithAttachments) {
        mainGuard {
            messages.add(messageWithAttachments)
            val safePos = position.coerceIn(0, chatItems.size)
            chatItems.add(safePos, ChatItem.Message(messageWithAttachments))
            notifyItemInserted(safePos)
        }
    }

    sealed class ChatItem {
        data class Message(val messageWithAttachments: MessageWithAttachments) : ChatItem()
        data class DateHeader(val dateText: String) : ChatItem()
    }
}
