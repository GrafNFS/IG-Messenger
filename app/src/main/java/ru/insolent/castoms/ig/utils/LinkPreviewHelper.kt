package ru.insolent.castoms.ig.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.util.regex.Pattern

object LinkPreviewHelper {

    // Регулярное выражение для поиска http/https ссылок в тексте
    private val URL_PATTERN = Pattern.compile(
        "https?://[-a-zA-Z0-9+&@#/%?=~_|!:,.;]*[-a-zA-Z0-9+&@#/%=~_|]"
    )

    data class PreviewResult(
        val title: String?,
        val description: String?,
        val imageUrl: String?
    )

    // Функция поиска первой ссылки в тексте сообщения
    fun extractUrl(text: String): String? {
        val matcher = URL_PATTERN.matcher(text)
        return if (matcher.find()) matcher.group() else null
    }

    // Асинхронный парсинг HTML страницы (Open Graph теги)
    suspend fun fetchPreview(url: String): PreviewResult? = withContext(Dispatchers.IO) {
        try {
            // Подключаемся к сайту с таймаутом в 3 секунды и прикидываемся браузером
            val document = Jsoup.connect(url)
                .timeout(3000)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .get()

            // Ищем стандартные Open Graph теги (они есть у 95% сайтов: YouTube, VK, Habr и т.д.)
            var title = document.select("meta[property=og:title]").attr("content")
            var description = document.select("meta[property=og:description]").attr("content")
            var imageUrl = document.select("meta[property=og:image]").attr("content")

            // Если сайт старый и og-тегов нет, берем стандартные теги страницы
            if (title.isEmpty()) title = document.title()
            if (description.isEmpty()) description = document.select("meta[name=description]").attr("content")

            if (title.isEmpty() && description.isEmpty() && imageUrl.isEmpty()) {
                return@withContext null
            }

            PreviewResult(
                title = title.takeIf { it.isNotBlank() },
                description = description.takeIf { it.isNotBlank() },
                imageUrl = imageUrl.takeIf { it.isNotBlank() }
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
