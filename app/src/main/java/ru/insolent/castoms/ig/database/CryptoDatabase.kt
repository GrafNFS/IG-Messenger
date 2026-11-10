package ru.insolent.castoms.ig.database

import ru.insolent.castoms.ig.database.MessageDao
import android.content.Context
import androidx.room.*
import org.signal.libsignal.protocol.*
import org.signal.libsignal.protocol.message.*
import org.signal.libsignal.protocol.state.*

// === ENTITY: Сессии ===
@Entity(tableName = "session_record", primaryKeys = ["name", "deviceId"])
data class SessionRecordEntity(
    val name: String,      // UUID собеседника
    val deviceId: Int,     // ID устройства
    val record: ByteArray  // Сериализованная сессия (Blob)
)

// === ENTITY: Одноразовые ключи (PreKeys) ===
@Entity(tableName = "prekey_record", primaryKeys = ["keyId"])
data class PreKeyRecordEntity(
    val keyId: Int,
    val record: ByteArray
)

// === ENTITY: Подписанные ключи (SignedPreKeys) ===
@Entity(tableName = "signed_prekey_record", primaryKeys = ["keyId"])
data class SignedPreKeyRecordEntity(
    val keyId: Int,
    val record: ByteArray
)

// === ENTITY: Доверенные Identity Keys ===
@Entity(tableName = "identity_record", primaryKeys = ["name", "deviceId"])
data class IdentityRecordEntity(
    val name: String,
    val deviceId: Int,
    val record: ByteArray
)

// === DAO (Интерфейсы доступа к данным) ===
@Dao
interface CryptoDao {
    // Sessions
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: SessionRecordEntity)
    @Query("SELECT record FROM session_record WHERE name = :name AND deviceId = :deviceId")
    suspend fun getSession(name: String, deviceId: Int): ByteArray?
    @Query("DELETE FROM session_record WHERE name = :name AND deviceId = :deviceId")
    suspend fun deleteSession(name: String, deviceId: Int)

    // PreKeys
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPreKey(preKey: PreKeyRecordEntity)
    @Query("SELECT record FROM prekey_record WHERE keyId = :keyId")
    suspend fun getPreKey(keyId: Int): ByteArray?
    @Query("DELETE FROM prekey_record WHERE keyId = :keyId")
    suspend fun deletePreKey(keyId: Int)
    @Query("SELECT keyId FROM prekey_record ORDER BY keyId ASC LIMIT 1")
    suspend fun getMinPreKeyId(): Int?

    // SignedPreKeys
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSignedPreKey(preKey: SignedPreKeyRecordEntity)
    @Query("SELECT record FROM signed_prekey_record WHERE keyId = :keyId")
    suspend fun getSignedPreKey(keyId: Int): ByteArray?
    @Query("DELETE FROM signed_prekey_record WHERE keyId = :keyId")
    suspend fun deleteSignedPreKey(keyId: Int)

    // Identity
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertIdentity(identity: IdentityRecordEntity)
    @Query("SELECT record FROM identity_record WHERE name = :name AND deviceId = :deviceId")
    suspend fun getIdentity(name: String, deviceId: Int): ByteArray?
    @Query("DELETE FROM identity_record WHERE name = :name AND deviceId = :deviceId")
    suspend fun deleteIdentity(name: String, deviceId: Int)
}

// === DATABASE CLASS ===
@Database(
    entities = [
        SessionRecordEntity::class,
        PreKeyRecordEntity::class,
        SignedPreKeyRecordEntity::class,
        IdentityRecordEntity::class,
        MessageEntity::class,  // ✅ Добавили MessageEntity
        ContactEntity::class,
        AttachmentEntity::class,
        UserProfileEntity::class
    ],
    version = 3  // ✅ Увеличили версию для миграции
)
abstract class CryptoDatabase : RoomDatabase() {
    abstract fun cryptoDao(): CryptoDao
    abstract fun messageDao(): MessageDao  // ✅ Добавили метод для ru.insolent.castoms.ig.database.MessageDao
    abstract fun conversationDao(): ConversationDao
    abstract fun contactDao(): ContactDao
    abstract fun userProfileDao(): UserProfileDao

    companion object {
        @Volatile private var INSTANCE: CryptoDatabase? = null

        fun getDatabase(context: Context): CryptoDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context,
                    CryptoDatabase::class.java,
                    "crypto_store"
                )
                    // ✅ Если нужно, добавь миграцию:
                    .addMigrations(MIGRATION_2_3)
                    .build().also { INSTANCE = it }
            }
        }
    }
}