package ru.insolent.castoms.ig.crypto

import kotlinx.coroutines.runBlocking  // ✅ Добавь этот импорт
import org.signal.libsignal.protocol.*
import org.signal.libsignal.protocol.groups.state.*
import org.signal.libsignal.protocol.message.*
import org.signal.libsignal.protocol.state.*
import ru.insolent.castoms.ig.database.*
import java.util.UUID

class PersistentSignalProtocolStore(
    private val identityKeyPair: IdentityKeyPair,
    private val localRegistrationId: Int,
    private val dao: CryptoDao
) : SignalProtocolStore {

    // === IdentityKeyStore ===
    override fun getIdentityKeyPair(): IdentityKeyPair = identityKeyPair
    override fun getLocalRegistrationId(): Int = localRegistrationId

    override fun saveIdentity(address: SignalProtocolAddress, key: IdentityKey?): Boolean {
        return try {
            runBlocking {  // ✅ Обёртываем в runBlocking
                if (key == null) {
                    dao.deleteIdentity(address.name, address.deviceId)
                } else {
                    dao.insertIdentity(ru.insolent.castoms.ig.database.IdentityRecordEntity(
                        address.name, address.deviceId, key.serialize()
                    ))
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    override fun isTrustedIdentity(
        address: SignalProtocolAddress,
        key: IdentityKey,
        direction: IdentityKeyStore.Direction
    ): Boolean {
        return try {
            val storedBytes = runBlocking {  // ✅ Обёртываем в runBlocking
                dao.getIdentity(address.name, address.deviceId)
            }
            if (storedBytes == null) true
            else {
                val storedKey = IdentityKey(storedBytes)
                storedKey == key
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? {
        return try {
            val bytes = runBlocking {  // ✅ Обёртываем в runBlocking
                dao.getIdentity(address.name, address.deviceId)
            }
            if (bytes != null) IdentityKey(bytes) else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // === SessionStore ===
    override fun loadSession(address: SignalProtocolAddress): SessionRecord {
        val bytes = runBlocking {  // ✅ Обёртываем в runBlocking
            dao.getSession(address.name, address.deviceId)
        }
        return if (bytes != null) SessionRecord(bytes) else SessionRecord()
    }

    override fun loadExistingSessions(addresses: List<SignalProtocolAddress>?): List<SessionRecord> {
        return runBlocking {  // ✅ Обёртываем в runBlocking
            addresses?.mapNotNull { address ->
                dao.getSession(address.name, address.deviceId)?.let { SessionRecord(it) }
            } ?: emptyList()
        }
    }

    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) {
        runBlocking {  // ✅ Обёртываем в runBlocking
            dao.insertSession(ru.insolent.castoms.ig.database.SessionRecordEntity(
                address.name, address.deviceId, record.serialize()
            ))
        }
    }

    fun sessionExists(address: SignalProtocolAddress): Boolean {
        return runBlocking {  // ✅ Обёртываем в runBlocking
            dao.getSession(address.name, address.deviceId) != null
        }
    }

    override fun containsSession(address: SignalProtocolAddress): Boolean {
        return runBlocking {  // ✅ Обёртываем в runBlocking
            dao.getSession(address.name, address.deviceId) != null
        }
    }

    override fun deleteSession(address: SignalProtocolAddress) {
        runBlocking {  // ✅ Обёртываем в runBlocking
            dao.deleteSession(address.name, address.deviceId)
        }
    }

    override fun deleteAllSessions(name: String) {
        // Для MVP не реализуем
    }

    override fun getSubDeviceSessions(name: String): List<Int> {
        return listOf(1)
    }

    // === PreKeyStore ===
    override fun loadPreKey(keyId: Int): PreKeyRecord {
        val bytes = runBlocking {  // ✅ Обёртываем в runBlocking
            dao.getPreKey(keyId)
        } ?: throw InvalidKeyIdException("No such prekeyrecord!")
        return PreKeyRecord(bytes)
    }

    override fun storePreKey(keyId: Int, record: PreKeyRecord) {
        runBlocking {  // ✅ Обёртываем в runBlocking
            dao.insertPreKey(ru.insolent.castoms.ig.database.PreKeyRecordEntity(keyId, record.serialize()))
        }
    }

    override fun removePreKey(keyId: Int) {
        runBlocking {  // ✅ Обёртываем в runBlocking
            dao.deletePreKey(keyId)
        }
    }

    override fun containsPreKey(keyId: Int): Boolean {
        return runBlocking {  // ✅ Обёртываем в runBlocking
            dao.getPreKey(keyId) != null
        }
    }

    // === SignedPreKeyStore ===
    override fun loadSignedPreKey(keyId: Int): SignedPreKeyRecord {
        val bytes = runBlocking {  // ✅ Обёртываем в runBlocking
            dao.getSignedPreKey(keyId)
        } ?: throw InvalidKeyIdException("No such SignedPreKeyRecord!")
        return SignedPreKeyRecord(bytes)
    }

    override fun loadSignedPreKeys(): List<SignedPreKeyRecord> {
        return emptyList()
    }

    override fun storeSignedPreKey(keyId: Int, record: SignedPreKeyRecord) {
        runBlocking {  // ✅ Обёртываем в runBlocking
            dao.insertSignedPreKey(ru.insolent.castoms.ig.database.SignedPreKeyRecordEntity(
                keyId, record.serialize()
            ))
        }
    }

    override fun removeSignedPreKey(keyId: Int) {
        runBlocking {  // ✅ Обёртываем в runBlocking
            dao.deleteSignedPreKey(keyId)
        }
    }

    override fun containsSignedPreKey(keyId: Int): Boolean {
        return runBlocking {  // ✅ Обёртываем в runBlocking
            dao.getSignedPreKey(keyId) != null
        }
    }

    // === SenderKeyStore ===
    override fun storeSenderKey(address: SignalProtocolAddress, distributionId: UUID, record: SenderKeyRecord) {
        // Для MVP не реализуем
    }

    override fun loadSenderKey(address: SignalProtocolAddress, distributionId: UUID): SenderKeyRecord {
        return SenderKeyRecord(ByteArray(0))  // ✅ Передаём пустой массив
    }

    // === KyberPreKeyStore ===
    override fun loadKyberPreKey(keyId: Int): KyberPreKeyRecord {
        throw InvalidKeyIdException("Kyber keys not supported")
    }

    override fun loadKyberPreKeys(): List<KyberPreKeyRecord> {
        return emptyList()
    }

    override fun storeKyberPreKey(keyId: Int, record: KyberPreKeyRecord) {
        // Не реализуем
    }

    override fun containsKyberPreKey(keyId: Int): Boolean {
        return false
    }

    override fun markKyberPreKeyUsed(keyId: Int) {
        // Не реализуем
    }
}