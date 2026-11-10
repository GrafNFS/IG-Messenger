package ru.insolent.castoms.ig.database

import androidx.room.*

@Entity(tableName = "user_profile")
data class UserProfileEntity(
    @PrimaryKey
    @ColumnInfo(name = "userId") val userId: String,

    @ColumnInfo(name = "username") val username: String,
    @ColumnInfo(name = "email") val email: String?,
    @ColumnInfo(name = "phone") val phone: String?,
    @ColumnInfo(name = "displayName") val displayName: String?,
    @ColumnInfo(name = "avatarUrl") val avatarUrl: String?,
    @ColumnInfo(name = "status") val status: String = "ACTIVE", // ACTIVE или SUSPENDED
    @ColumnInfo(name = "createdAt") val createdAt: Long? = null,
    @ColumnInfo(name = "updatedAt") val updatedAt: Long? = null,
    @ColumnInfo(name = "isOnline") val isOnline: Boolean = false,
    @ColumnInfo(name = "lastSeen") val lastSeen: Long? = null
)