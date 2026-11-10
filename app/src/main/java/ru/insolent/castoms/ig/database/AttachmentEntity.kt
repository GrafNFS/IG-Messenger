package ru.insolent.castoms.ig.database

import androidx.room.*
import java.util.UUID

@Entity(
    tableName = "attachments",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["messageId"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["messageId"], name = "idx_attachments_messageId")]
)
data class AttachmentEntity(
    @PrimaryKey
    @ColumnInfo(name = "fileId")
    val fileId: String, // ✅ fileId теперь является уникальным первичным ключом!

    @ColumnInfo(name = "messageId") val messageId: String,
    @ColumnInfo(name = "encryptedKey") val encryptedKey: String,
    @ColumnInfo(name = "mediaType") val mediaType: String,
    @ColumnInfo(name = "fileName") val fileName: String,
    @ColumnInfo(name = "fileSize") val fileSize: Long,
    @ColumnInfo(name = "thumbnailBase64") val thumbnailBase64: String? = null,
    @ColumnInfo(name = "localFilePath") val localFilePath: String? = null,
    @ColumnInfo(name = "downloadProgress") val downloadProgress: Int = 0,
    @ColumnInfo(name = "uploadProgress") val uploadProgress: Int = 0
)