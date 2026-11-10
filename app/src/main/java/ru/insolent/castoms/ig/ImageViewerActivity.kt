package ru.insolent.castoms.ig

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import ru.insolent.castoms.ig.adapters.MediaViewerAdapter
import ru.insolent.castoms.ig.database.AttachmentEntity

class ImageViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_START_POSITION = "extra_start_position"
    }

    private lateinit var mediaAdapter: MediaViewerAdapter
    private var mediaItems: List<AttachmentEntity> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_viewer)

        // 🌟 1. Восстанавливаем типизированный список вложений из JSON строки
        val attachmentsJson = intent.getStringExtra("EXTRA_ATTACHMENTS_JSON")
        if (!attachmentsJson.isNullOrEmpty()) {
            val type = object : TypeToken<List<AttachmentEntity>>() {}.type
            mediaItems = Gson().fromJson(attachmentsJson, type)
        }

        val startPosition = intent.getIntExtra(EXTRA_START_POSITION, 0)

        val viewPager = findViewById<ViewPager2>(R.id.viewPager)
        val tvPageIndicator = findViewById<TextView>(R.id.tvPageIndicator)
        val btnClose = findViewById<View>(R.id.btnClose)

        // Настраиваем новый комбинированный адаптер
        mediaAdapter = MediaViewerAdapter(mediaItems)
        viewPager.adapter = mediaAdapter

        viewPager.setCurrentItem(startPosition, false)

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                tvPageIndicator.text = "${position + 1} / ${mediaItems.size}"

                // 🌟 2. Эффект Telegram: Глушим плееры на прошлых страницах
                mediaAdapter.pauseAllVideos()

                // 🌟 3. Автоматически запускаем видео, если перелистнули на него
                if (mediaItems[position].mediaType.startsWith("video/")) {
                    viewPager.postDelayed({
                        mediaAdapter.playVideoAt(position)
                        // Скрываем кнопку плей в холдере через вызов клика или кастомный метод,
                        // но для простоты VideoView начнет тихое фоновое воспроизведение автоматически.
                    }, 100)
                }
            }
        })

        tvPageIndicator.text = "${startPosition + 1} / ${mediaItems.size}"
        btnClose.setOnClickListener { finish() }
    }

    override fun onPause() {
        super.onPause()
        // 🌟 Автоматически ставим плеер на паузу, если приложение свернули
        if (::mediaAdapter.isInitialized) {
            mediaAdapter.pauseAllVideos()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::mediaAdapter.isInitialized) {
            mediaAdapter.pauseAllVideos()
        }
    }

    override fun onBackPressed() {
        super.onBackPressed()
        finish()
    }
}
