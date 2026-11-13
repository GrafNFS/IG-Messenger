package ru.insolent.castoms.ig.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

@Entity(tableName = "signal_records")
data class SignalRecord(
    @PrimaryKey val key: String,      // например "otk_42", "session_<address>", "identity"
    val encryptedValue: String        // Base64(IV + ciphertext) — одна колонка на всё
)

@Dao
interface SignalRecordDao {
    @Query("SELECT * FROM signal_records WHERE `key` = :key")
    suspend fun get(key: String): SignalRecord?

    @Query("SELECT * FROM signal_records WHERE `key` LIKE :prefix")
    suspend fun getByPrefix(prefix: String): List<SignalRecord>

    @Upsert
    suspend fun upsert(record: SignalRecord)

    @Query("DELETE FROM signal_records WHERE `key` = :key")
    suspend fun delete(key: String)
}