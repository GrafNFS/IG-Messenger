package ru.insolent.castoms.ig.adapters

import android.media.MediaMetadataRetriever
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.squareup.picasso.Picasso
import ru.insolent.castoms.ig.R
import java.io.File

class MediaPreviewAdapter(
    private val onRemoveClick: (Int) -> Unit
) : RecyclerView.Adapter<MediaPreviewAdapter.PreviewViewHolder>() {

    private val mediaItems = mutableListOf<String>()

    fun submitList(newMedia: List<String>) {
        mediaItems.clear()
        mediaItems.addAll(newMedia)
        notifyDataSetChanged()
    }

    class PreviewViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivMedia: ImageView = view.findViewById(R.id.ivPreviewMedia)
        val btnRemove: ImageView = view.findViewById(R.id.btnRemoveMedia)
        val ivPlayIcon: ImageView = view.findViewById(R.id.ivPlayIcon)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PreviewViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_media_preview, parent, false)
        return PreviewViewHolder(view)
    }

    override fun onBindViewHolder(holder: PreviewViewHolder, position: Int) {
        val filePath = mediaItems[position] // 🌟 Теперь это строковый путь к файлу кэша
        val file = File(filePath)

        if (!file.exists()) {
            holder.ivMedia.setImageResource(R.drawable.ic_image_placeholder)
            holder.ivPlayIcon.visibility = View.GONE
            return
        }

        // 🌟 Определяем тип медиа напрямую по расширению нашего временного файла
        val isVideo = file.name.endsWith(".mp4")
        val isGif = file.name.endsWith(".gif")

        // Показываем/скрываем иконку Play для видео или GIF
        holder.ivPlayIcon.visibility = if (isVideo || isGif) View.VISIBLE else View.GONE

        // Загружаем превью
        if (isVideo) {
            // Для локального видео берём кадр через абсолютный путь
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(file.absolutePath) // 🌟 Передаем прямой путь к файлу
                val bitmap = retriever.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                retriever.release()

                if (bitmap != null) {
                    holder.ivMedia.setImageBitmap(bitmap)
                } else {
                    holder.ivMedia.setImageResource(R.drawable.ic_image_placeholder)
                }
            } catch (e: Exception) {
                holder.ivMedia.setImageResource(R.drawable.ic_image_placeholder)
            }
        } else {
            // Для фото используем Picasso, передавая объект File вместо Uri
            Picasso.get()
                .load(file) // 🌟 Передаем локальный File, права Android на него не нужны
                .fit()
                .centerCrop()
                .into(holder.ivMedia)
        }

        // Обработка клика по крестику
        holder.btnRemove.setOnClickListener {
            onRemoveClick(position)
        }
    }

    override fun getItemCount() = mediaItems.size
}
