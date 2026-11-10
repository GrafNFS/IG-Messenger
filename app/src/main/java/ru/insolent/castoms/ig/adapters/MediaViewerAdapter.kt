package ru.insolent.castoms.ig.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.VideoView
import androidx.recyclerview.widget.RecyclerView
import com.github.chrisbanes.photoview.PhotoView
import com.squareup.picasso.Picasso
import ru.insolent.castoms.ig.R
import ru.insolent.castoms.ig.database.AttachmentEntity
import java.io.File

class MediaViewerAdapter(
    private val items: List<AttachmentEntity>
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_IMAGE = 0
        private const val TYPE_VIDEO = 1
    }

    // Храним ссылки на активные плееры, чтобы управлять ими при перелистывании страниц
    private val activeVideoViews = HashMap<Int, VideoView>()
    private val activePlayButtons = HashMap<Int, ImageView>()

    override fun getItemViewType(position: Int): Int {
        val mediaType = items[position].mediaType
        // Проверяем, начинается ли тип с "video", чтобы разделить ячейки
        return if (mediaType.startsWith("video")) TYPE_VIDEO else TYPE_IMAGE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_VIDEO) {
            val view = inflater.inflate(R.layout.item_viewer_video, parent, false)
            VideoViewHolder(view)
        } else {
            // 🌟 Используем вашу оригинальную разметку для картинок с PhotoView внутри
            val view = inflater.inflate(R.layout.item_image_viewer_page, parent, false)
            ImageViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        val file = File(item.localFilePath ?: "")

        // 🖼️ Отрисовка ФОТО (Ваша старая логика с сохранением зума)
        if (holder is ImageViewHolder) {
            if (file.exists()) {
                holder.ivPlaceholder.visibility = View.GONE
                holder.photoView.visibility = View.VISIBLE

                Picasso.get()
                    .load(file)
                    .fit()
                    .centerInside()
                    .into(holder.photoView)
            } else {
                holder.photoView.visibility = View.GONE
                holder.ivPlaceholder.visibility = View.VISIBLE
            }
        }

        // 🎥 Отрисовка ВИДЕО (Плеер VideoView)
        else if (holder is VideoViewHolder) {
            activeVideoViews[position] = holder.videoView
            activePlayButtons[position] = holder.btnPlayPause

            if (file.exists()) {
                holder.videoView.setVideoPath(file.absolutePath)
                holder.btnPlayPause.visibility = View.VISIBLE

                holder.videoView.setOnPreparedListener { mediaPlayer ->
                    mediaPlayer.isLooping = true // Зацикливаем видео

                    // Умное выравнивание видео под размеры экрана (без искажения пропорций)
                    val videoRatio = mediaPlayer.videoWidth.toFloat() / mediaPlayer.videoHeight
                    val screenRatio = holder.videoView.width.toFloat() / holder.videoView.height
                    val scaleX = videoRatio / screenRatio
                    if (scaleX >= 1f) {
                        holder.videoView.scaleX = scaleX
                    } else {
                        holder.videoView.scaleY = 1f / scaleX
                    }
                }

                // Единая логика кликов для Старта / Паузы по экрану
                val togglePlayPause = View.OnClickListener {
                    if (holder.videoView.isPlaying) {
                        holder.videoView.pause()
                        holder.btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
                        holder.btnPlayPause.visibility = View.VISIBLE
                    } else {
                        holder.videoView.start()
                        holder.btnPlayPause.visibility = View.GONE
                    }
                }

                holder.itemView.setOnClickListener(togglePlayPause)
                holder.btnPlayPause.setOnClickListener(togglePlayPause)
            } else {
                holder.btnPlayPause.visibility = View.GONE
            }
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        val position = holder.adapterPosition
        if (holder is VideoViewHolder) {
            holder.videoView.stopPlayback()
            activeVideoViews.remove(position)
            activePlayButtons.remove(position)
        }
    }

    // Метод автоматического запуска видео при перелистывании страницы в ViewPager2
    fun playVideoAt(position: Int) {
        activeVideoViews[position]?.start()
        activePlayButtons[position]?.visibility = View.GONE // Прячем кнопку Play при старте
    }

    // Метод остановки всех видео (вызывается при перелистывании на фото или сворачивании экрана)
    fun pauseAllVideos() {
        activeVideoViews.forEach { (position, videoView) ->
            if (videoView.isPlaying) {
                videoView.pause()
                activePlayButtons[position]?.setImageResource(android.R.drawable.ic_media_play)
                activePlayButtons[position]?.visibility = View.VISIBLE
            }
        }
    }

    override fun getItemCount(): Int = items.size

    // Холдер для фото (ваши старые поля)
    class ImageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val photoView: PhotoView = view.findViewById(R.id.photoView)
        val ivPlaceholder: ImageView = view.findViewById(R.id.ivPlaceholder)
    }

    // Холдер для видео
    class VideoViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val videoView: VideoView = view.findViewById(R.id.videoView)
        val btnPlayPause: ImageView = view.findViewById(R.id.btnPlayPause)
        val progressBar: ProgressBar = view.findViewById(R.id.videoProgressBar)
    }
}
