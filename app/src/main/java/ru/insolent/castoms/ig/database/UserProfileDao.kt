package ru.insolent.castoms.ig.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface UserProfileDao {
    @Query("SELECT * FROM user_profile WHERE userId = :userId")
    fun observeProfile(userId: String): Flow<UserProfileEntity?>

    @Query("SELECT * FROM user_profile WHERE userId = :userId")
    suspend fun getProfile(userId: String): UserProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveProfile(profile: UserProfileEntity)

    // ✅ Убрали bio из запроса
    @Query("UPDATE user_profile SET displayName = :name, phone = :phone, avatarUrl = :avatarUrl, updatedAt = :timestamp WHERE userId = :userId")
    suspend fun updateProfile(userId: String, name: String?, phone: String?, avatarUrl: String?, timestamp: Long)
}