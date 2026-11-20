package ru.insolent.castoms.ig.database

import androidx.room.*

@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val id: String,              // UUID пользователя ИЛИ id группы (isGroup=1)
    @ColumnInfo(name = "username") val username: String,
    @ColumnInfo(name = "displayName") val displayName: String?, // Красивое имя (может быть null)
    @ColumnInfo(name = "avatarUrl") val avatarUrl: String?,
    @ColumnInfo(name = "lastSeen") val lastSeen: Long?,
    @ColumnInfo(name = "isOnline") val isOnline: Boolean = false,
    @ColumnInfo(name = "isGroup", defaultValue = "0") val isGroup: Boolean = false
)
