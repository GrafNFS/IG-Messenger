package ru.insolent.castoms.ig.adapters

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.squareup.picasso.Picasso
import ru.insolent.castoms.ig.R
import ru.insolent.castoms.ig.database.AttachmentEntity
import java.io.File

/**
 * 🌟 Добавлено: прогресс ЗАГРУЗКИ (upload) для исходящих вложений.
 * isOutgoing = true → показываем uploadProgress (0..99), локальное фото видно сразу.
 */
class MessageImagesAdapter(
    private val attachments: List<AttachmentEntity>,
    private val isOutgoing: Boolean = false,
    private val onClick: (List<AttachmentEntity>, Int) -> Unit,
    private val onRetryClick: (AttachmentEntity) -> Unit
) : RecyclerView.Adapter<MessageImagesAdapter.ViewHolder>() {

    class ViewHolder(view: View) : ViewHolderBase(view)

    abstract class ViewHolderBase(view: View) : RecyclerView.ViewHolder(view) {
        val ivImage: com.google.android.material.imageview.ShapeableImageView = view.findViewById(R.id.ivMessageImage)
        val progressBar: ProgressBar = view.findViewById(R.id.progressBar)
        val ivPlaceholder: ImageView = view.findViewById(R.id.ivPlaceholder)
        val ivPlayIcon: ImageView = view.findViewById(R.id.ivPlayIcon)
        val ivErrorIcon: ImageView = view.findViewById(R.id.ivErrorIcon)
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
            layoutParams.height = dpToPx(context, if (total == 1) 200 else 140)
        } else {
            layoutParams.height = dpToPx(context, 105)
        }

        holder.itemView.layoutParams = layoutParams
        holder.itemView.requestLayout()

        holder.ivImage.alpha = 1.0f
        holder.ivErrorIcon.visibility = View.GONE

        val progress = attachment.downloadProgress
        val isVideo = attachment.mediaType.startsWith("video")
        val uploadProgress = attachment.uploadProgress

        // Настраиваем иконку Play сразу для всех состояний видео
        holder.ivPlayIcon.visibility = if (isVideo && progress != -1) View.VISIBLE else View.GONE

        if (isOutgoing) {
            // ===== ИСХОДЯЩИЕ: локальный файл есть всегда, поверх — прогресс загрузки =====
            if (!attachment.thumbnailBase64.isNullOrEmpty()) {
                setBase64Thumbnail(attachment.thumbnailBase64, holder.ivImage)
            } else if (!attachment.localFilePath.isNullOrEmpty() && File(attachment.localFilePath).exists()) {
                if (isVideo) {
                    holder.ivImage.setImageResource(R.drawable.ic_image_placeholder)
                } else {
                    Picasso.get().load(File(attachment.localFilePath)).fit().centerCrop().into(holder.ivImage)
                }
            } else {
                holder.ivImage.setImageResource(R.drawable.ic_image_placeholder)
            }

            when {
                uploadProgress in 0..99 -> {
                    holder.progressBar.visibility = View.VISIBLE
                    holder.progressBar.isIndeterminate = uploadProgress <= 0
                    if (uploadProgress > 0) holder.progressBar.progress = uploadProgress
                    holder.ivImage.alpha = 0.7f
                    holder.itemView.setOnClickListener(null)
                }
                progress == -1 -> { // ошибка ОТПРАВКИ
                    holder.progressBar.visibility = View.GONE
                    holder.ivErrorIcon.visibility = View.VISIBLE
                    holder.ivImage.alpha = 0.4f
                    holder.itemView.setOnClickListener { onRetryClick(attachment) }
                }
                else -> { // загружено на сервер
                    holder.progressBar.visibility = View.GONE
                    holder.itemView.setOnClickListener { onClick(attachments, position) }
                }
            }
            return
        }

        // ===== ВХОДЯЩИЕ: прежняя логика (скачивание) =====
        when {
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

            !attachment.localFilePath.isNullOrEmpty() && File(attachment.localFilePath).exists() -> {
                holder.progressBar.visibility = View.GONE
                holder.ivPlaceholder.visibility = View.GONE

                if (isVideo) {
                    if (!attachment.thumbnailBase64.isNullOrEmpty()) {
                        setBase64Thumbnail(attachment.thumbnailBase64, holder.ivImage)
                    } else {
                        holder.ivImage.setImageResource(R.drawable.ic_image_placeholder)
                    }
                } else {
                    Picasso.get().load(File(attachment.localFilePath)).fit().centerCrop().into(holder.ivImage)
                }

                holder.itemView.setOnClickListener { onClick(attachments, position) }
            }

            !attachment.thumbnailBase64.isNullOrEmpty() -> {
                holder.progressBar.visibility = View.VISIBLE
                holder.progressBar.isIndeterminate = progress <= 0
                if (progress > 0) holder.progressBar.progress = progress.coerceIn(0, 100)
                holder.ivPlaceholder.visibility = View.GONE

                setBase64Thumbnail(attachment.thumbnailBase64, holder.ivImage)
                holder.ivImage.alpha = 0.6f

                holder.itemView.setOnClickListener(null)
            }

            else -> {
                holder.progressBar.visibility = View.VISIBLE
                holder.progressBar.isIndeterminate = progress <= 0
                if (progress > 0) holder.progressBar.progress = progress.coerceIn(0, 100)
                holder.ivPlaceholder.visibility = View.VISIBLE
                holder.ivImage.setImageResource(R.drawable.ic_image_placeholder)

                holder.itemView.setOnClickListener(null)
            }
        }
    }

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
