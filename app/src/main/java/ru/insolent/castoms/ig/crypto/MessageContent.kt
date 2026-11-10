package ru.insolent.castoms.ig.crypto

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.json.JSONObject

// ✅ Класс для одного вложения (фото или видео)
data class MediaAttachment(
    val fileId: String,
    val encryptedKey: String,
    val mediaType: String,
    val fileName: String,
    val fileSize: Long,
    val thumbnailBase64: String? = null
)

// ✅ Полностью очищенный MessageContent
data class MessageContent(
    val text: String = "",
    val attachments: List<MediaAttachment>? = null, // Теперь здесь список вложений
    val replyToMessageId: String? = null,
    val replyToSenderName: String? = null,
    val replyToContent: String? = null,
    val action: String? = null,
    val targetMessageId: String? = null
) {
    fun toJson(): String = Gson().toJson(this)

    companion object {
        fun fromJson(json: String): MessageContent {
            return try {
                val obj = JSONObject(json)

                // Парсим список вложений через Gson (это надежнее, чем вручную парсить JSONArray)
                val attachmentsJson = obj.optString("attachments", "")
                val attachmentsType = object : TypeToken<List<MediaAttachment>>() {}.type
                val attachments: List<MediaAttachment>? = if (attachmentsJson.isNotEmpty()) {
                    Gson().fromJson(attachmentsJson, attachmentsType)
                } else null

                MessageContent(
                    text = obj.optString("text", ""),
                    attachments = attachments,
                    replyToMessageId = obj.optString("replyToMessageId", null),
                    replyToSenderName = obj.optString("replyToSenderName", null),
                    replyToContent = obj.optString("replyToContent", null),
                    action = obj.optString("action", null),
                    targetMessageId = obj.optString("targetMessageId", null)
                )
            } catch (e: Exception) {
                Log.e("MessageContent", "❌ Failed to parse JSON: $json", e)
                MessageContent(text = json)
            }
        }
    }
}