package ru.insolent.castoms.ig.database

import androidx.room.*

@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val id: String,              // UUID пользователя
    @ColumnInfo(name = "username") val username: String,
    @ColumnInfo(name = "displayName") val displayName: String?, // Красивое имя (может быть null)
    @ColumnInfo(name = "avatarUrl") val avatarUrl: String?,
    @ColumnInfo(name = "lastSeen") val lastSeen: Long?,
    @ColumnInfo(name = "isOnline") val isOnline: Boolean = false
)