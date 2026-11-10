package ru.insolent.castoms.ig.adapters

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.squareup.picasso.Picasso
import ru.insolent.castoms.ig.R
import ru.insolent.castoms.ig.database.AttachmentEntity
import java.io.File

class MessageImagesAdapter(
    private val attachments: List<AttachmentEntity>,
    private val onClick: (List<AttachmentEntity>, Int) -> Unit,
    private val onRetryClick: (AttachmentEntity) -> Unit // 🌟 Добавляем колбэк для перезапуска
) : RecyclerView.Adapter<MessageImagesAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivImage: com.google.android.material.imageview.ShapeableImageView = view.findViewById(R.id.ivMessageImage)
        val progressBar: ProgressBar = view.findViewById(R.id.progressBar)
        val ivPlaceholder: ImageView = view.findViewById(R.id.ivPlaceholder)
        val ivPlayIcon: ImageView = view.findViewById(R.id.ivPlayIcon)
        val ivErrorIcon: ImageView = view.findViewById(R.id.ivErrorIcon) // 🌟 Новая иконка ошибки
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_message_image, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val attachment = attachments[position]
        val context = holder.itemView.context
        val total = attachments.size

        val layoutParams = holder.itemView.layoutParams as? GridLayoutManager.LayoutParams
            ?: GridLayoutManager.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        val isFullWidth = total == 1 || (total % 2 != 0 && position == total - 1)

        if (isFullWidth) {
            if (total == 1) {
                layoutParams.height = dpToPx(context, 200)
            } else {
                layoutParams.height = dpToPx(context, 140)
            }
        } else {
            layoutParams.height = dpToPx(context, 105)
        }

        holder.itemView.layoutParams = layoutParams
        holder.itemView.requestLayout()

        holder.ivImage.alpha = 1.0f
        holder.ivErrorIcon.visibility = View.GONE

        val progress = attachment.downloadProgress
        val isVideo = attachment.mediaType.startsWith("video") // 🌟 Выносим проверку типа медиа

        // Настраиваем иконку Play сразу для всех состояний видео
        holder.ivPlayIcon.visibility = if (isVideo && progress != -1) View.VISIBLE else View.GONE

        when {
            // ❌ 1. СОСТОЯНИЕ ОШИБКИ СКАЧИВАНИЯ
            progress == -1 -> {
                holder.progressBar.visibility = View.GONE
                holder.ivPlaceholder.visibility = View.GONE
                holder.ivPlayIcon.visibility = View.GONE
                holder.ivErrorIcon.visibility = View.VISIBLE
                holder.ivImage.alpha = 0.4f

                if (!attachment.thumbnailBase64.isNullOrEmpty()) {
                    setBase64Thumbnail(attachment.thumbnailBase64, holder.ivImage)
                } else {
                    holder.ivImage.setImageResource(R.drawable.ic_image_placeholder)
                }

                holder.itemView.setOnClickListener { onRetryClick(attachment) }
            }

            // ✅ 2. ФАЙЛ УСПЕШНО СКАЧАН И СУЩЕСТВУЕТ НА ДИСКЕ
            !attachment.localFilePath.isNullOrEmpty() && File(attachment.localFilePath).exists() -> {
                holder.progressBar.visibility = View.GONE
                holder.ivPlaceholder.visibility = View.GONE

                if (isVideo) {
                    // 🌟 ИСПРАВЛЕНИЕ: Если это видео — Picasso его не загрузит!
                    // Берем готовое превью из базы данных, чтобы оно не исчезало после скачивания.
                    if (!attachment.thumbnailBase64.isNullOrEmpty()) {
                        setBase64Thumbnail(attachment.thumbnailBase64, holder.ivImage)
                    } else {
                        holder.ivImage.setImageResource(R.drawable.ic_image_placeholder)
                    }
                } else {
                    // Если это обычное фото — без проблем грузим через Picasso оригинальный файл
                    Picasso.get().load(File(attachment.localFilePath)).fit().centerCrop().into(holder.ivImage)
                }

                // Стандартный клик: открываем просмотр
                holder.itemView.setOnClickListener { onClick(attachments, position) }
            }

            // ⏳ 3. ФАЙЛ СКАЧИВАЕТСЯ (ИЛИ ЕЩЕ НЕ СКАЧАН, НО ЕСТЬ ПРЕВЬЮ В БД)
            !attachment.thumbnailBase64.isNullOrEmpty() -> {
                holder.progressBar.visibility = View.VISIBLE
                holder.progressBar.progress = progress.coerceIn(0, 100)
                holder.ivPlaceholder.visibility = View.GONE

                setBase64Thumbnail(attachment.thumbnailBase64, holder.ivImage)
                holder.ivImage.alpha = 0.6f

                holder.itemView.setOnClickListener(null)
            }

            // 🚫 4. НЕТ НИ ПРЕВЮ, НИ ФАЙЛА (ПОЛНАЯ ЗАГЛУШКА)
            else -> {
                holder.progressBar.visibility = View.VISIBLE
                holder.progressBar.progress = progress.coerceIn(0, 100)
                holder.ivPlaceholder.visibility = View.VISIBLE
                holder.ivImage.setImageResource(R.drawable.ic_image_placeholder)

                holder.itemView.setOnClickListener(null)
            }
        }
    }

    // 🌟 Вспомогательный метод для красивой и безопасной отрисовки Base64 миниатюр
    private fun setBase64Thumbnail(base64String: String, imageView: ImageView) {
        try {
            val imageBytes = Base64.decode(base64String, Base64.NO_WRAP)
            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            if (bitmap != null) {
                imageView.setImageBitmap(bitmap)
            } else {
                imageView.setImageResource(R.drawable.ic_image_placeholder)
            }
        } catch (e: Exception) {
            imageView.setImageResource(R.drawable.ic_image_placeholder)
        }
    }

    private fun dpToPx(context: Context, dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }

    override fun getItemCount(): Int = attachments.size
}
