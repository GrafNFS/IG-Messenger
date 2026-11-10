package ru.insolent.castoms.ig.adapters

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import ru.insolent.castoms.ig.R
import ru.insolent.castoms.ig.database.Conversation
import ru.insolent.castoms.ig.databinding.ItemChatBinding
import ru.insolent.castoms.ig.models.Contact
import ru.insolent.castoms.ig.network.NetworkClient
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class ChatAdapter(
    private val onItemClick: (Conversation) -> Unit
) : RecyclerView.Adapter<ChatAdapter.ViewHolder>() {

    private val conversations = mutableListOf<Conversation>()

    inner class ViewHolder(private val binding: ItemChatBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(conversation: Conversation) {
            var lastMessage = ""
            when (conversation.messageType) {
                "audio" -> lastMessage = "🎙️ Голосовое сообщение"
                "video" -> lastMessage = "🎥 Видео"
                "image" -> {
                    lastMessage = "📷 Фото"
                }
                "text" -> {
                    lastMessage = conversation.lastMessage ?: "Нет сообщений"
                }
            }
            binding.tvContactName.text = conversation.contactName
            binding.tvLastMessage.text = lastMessage
            binding.tvUnreadBadge.text = conversation.unreadCount.toString()
            binding.tvContactName.text = conversation.contactName.takeIf { it.isNotBlank() } ?: "Unknown"

            // 2. Статус (пока заглушка, позже подключим к сокету)
            Log.d("ConversationAdapter", "🟢 Binding: ${conversation.contactName}, isOnline = ${conversation.isOnline}")

            if (conversation.isOnline) {
                binding.imgStatus.visibility = View.VISIBLE
            } else {
                binding.imgStatus.visibility = View.GONE
            }

            // 4. Умное время
            conversation.lastMessageTime?.let { binding.tvTime.text = formatSmartTime(it) }

            // 5. Бейдж непрочитанных
            if (conversation.unreadCount > 0) {
                binding.tvUnreadBadge.text = if (conversation.unreadCount > 99) "99+" else conversation.unreadCount.toString()
                binding.tvUnreadBadge.visibility = View.VISIBLE
            } else {
                binding.tvUnreadBadge.visibility = View.GONE
            }

            // 6. Аватарка-заглушка с первой буквой
            val initial = conversation.contactName.firstOrNull()?.uppercase() ?: "?"

            // 2. Устанавливаем букву в TextView-аватарку
            binding.tvAvatar.text = initial
            //setAvatarInitial(binding.ivAvatar, conversation.contactName)

            itemView.setOnClickListener { onItemClick(conversation) }
        }

        private fun formatSmartTime(timestamp: Long): String {
            val now = System.currentTimeMillis()
            val diff = now - timestamp
            val dayStart = getStartOfDay(now)
            val messageDayStart = getStartOfDay(timestamp)

            return when {
                diff < 60000 -> "Сейчас"
                messageDayStart == dayStart -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
                messageDayStart == dayStart - 86400000 -> "Вчера"
                else -> SimpleDateFormat("dd MMM", Locale.getDefault()).format(Date(timestamp))
            }
        }

        private fun getStartOfDay(timestamp: Long): Long {
            val cal = Calendar.getInstance()
            cal.timeInMillis = timestamp
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }

        private fun setAvatarInitial(iv: ImageView, name: String) {
            val initial = name.firstOrNull()?.uppercase() ?: "?"
            val drawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#6200EE"))
                setStroke(4, Color.WHITE)
            }
            iv.background = drawable
            // В реальном приложении здесь будет загрузка URL через Glide/Coil
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        Log.d("ConversationAdapter", "️ Creating ViewHolder")
        val binding = ItemChatBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        Log.d("ConversationAdapter", "✅ View inflated, size: ")
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        Log.d("ConversationAdapter", "📎 Binding position $position")
        Log.d("ConversationAdapter", "   Data: ${conversations[position]}")
        holder.bind(conversations[position])
    }

    override fun getItemCount() = conversations.size


    fun submitList(newConversations: List<Conversation>) {
        Log.d("ConversationAdapter", " Submitting ${newConversations.size} items")
        conversations.clear()
        conversations.addAll(newConversations)
        notifyDataSetChanged()
        Log.d("ConversationAdapter", "✅ Notified, itemCount = ${itemCount}")
    }
}