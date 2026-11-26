package ru.insolent.castoms.ig.crypto

import android.content.Context
import android.util.Base64
import android.util.Log
import org.signal.libsignal.protocol.*
import org.signal.libsignal.protocol.ecc.Curve
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.message.*
import org.signal.libsignal.protocol.state.*
import org.signal.libsignal.protocol.util.KeyHelper
import ru.insolent.castoms.ig.database.CryptoDatabase
import ru.insolent.castoms.ig.network.DeviceKeysBundle
import ru.insolent.castoms.ig.network.DevicesBundleResponse
import java.nio.charset.StandardCharsets

class MessageCryptoManager(
    private val localUserId: String,
    private val context: Context
) {
    private val localDeviceId = 1
    private val TAG = "CryptoManager"

    private val db = CryptoDatabase.getDatabase(context)
    private val dao = db.cryptoDao()

    // ✅ Приватные ключи теперь через зашифрованное хранилище (Keystore)
    private val secureStore = SecureKeyStore(context)

    private lateinit var identityKeyPair: IdentityKeyPair
    private var registrationId: Int
    private val store: SignalProtocolStore

    init {
        // 1. Загружаем IdentityKey и RegistrationId (зашифрованное хранилище)
        val identityPrivateB64 = secureStore.getString("${localUserId}_identity_private", null)
        val regId = secureStore.getInt("${localUserId}_registration_id", -1)

        if (identityPrivateB64 != null && regId != -1) {
            val identityPrivateBytes = Base64.decode(identityPrivateB64, Base64.NO_WRAP)
            val identityPrivateKey = Curve.decodePrivatePoint(identityPrivateBytes)
            val identityPublicB64 = secureStore.getString("${localUserId}_identity_public", "")!!
            val identityPublicKey = IdentityKey(
                Curve.decodePoint(Base64.decode(identityPublicB64, Base64.NO_WRAP), 0)
            )
            identityKeyPair = IdentityKeyPair(identityPublicKey, identityPrivateKey)
            registrationId = regId
            Log.d(TAG, "✅ Loaded saved Identity Keys for $localUserId")
        } else {
            // Fallback: ключей нет (первый запуск / Keystore утерян) — генерируем.
            // ВАЖНО: это состояние «нет ключей», LoginActivity запустит полную
            // генерацию + загрузку через generateAndUploadKeys().
            identityKeyPair = generateIdentityKeyPair()
            registrationId = KeyHelper.generateRegistrationId(false)
            Log.w(TAG, "⚠️ No stored keys for $localUserId — generated temporary pair")

            secureStore.putString("${localUserId}_identity_private",
                Base64.encodeToString(identityKeyPair.privateKey.serialize(), Base64.NO_WRAP))
            secureStore.putString("${localUserId}_identity_public",
                Base64.encodeToString(identityKeyPair.publicKey.serialize(), Base64.NO_WRAP))
            secureStore.putInt("${localUserId}_registration_id", registrationId)
        }

        // 2. Persistent Store
        store = PersistentSignalProtocolStore(identityKeyPair, registrationId, dao)

        // === SignedPreKeyRecord ===
        val signedPreKeyRecordB64 = secureStore.getString("${localUserId}_signed_prekey_record", null)
        if (signedPreKeyRecordB64 != null) {
            try {
                val record = SignedPreKeyRecord(Base64.decode(signedPreKeyRecordB64, Base64.NO_WRAP))
                store.storeSignedPreKey(record.id, record)
                Log.d(TAG, "✅ Restored SignedPreKeyRecord ${record.id}")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to restore SignedPreKey", e)
            }
        }

        // === One-Time PreKeys ===
        val otkpJson = secureStore.getString("${localUserId}_one_time_keys", null)
        if (otkpJson != null) {
            try {
                val jsonArray = org.json.JSONArray(otkpJson)
                var loadedCount = 0

                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val keyId = obj.getInt("id")
                    val privateKeyB64 = obj.getString("private")

                    val privateKeyBytes = Base64.decode(privateKeyB64, Base64.NO_WRAP)
                    val privateKey = Curve.decodePrivatePoint(privateKeyBytes)
                    val publicKey = privateKey.publicKey()

                    val keyPair = ECKeyPair(publicKey, privateKey)
                    val preKeyRecord = PreKeyRecord(keyId, keyPair)

                    store.storePreKey(keyId, preKeyRecord)
                    loadedCount++
                }

                Log.d(TAG, "✅ Restored $loadedCount One-Time PreKeys to Room")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to restore One-Time PreKeys", e)
            }
        } else {
            Log.w(TAG, "⚠️ No One-Time PreKeys found for $localUserId")
        }
    }

    private fun generateIdentityKeyPair(): IdentityKeyPair {
        val keyPair = Curve.generateKeyPair()
        val identityKey = IdentityKey(keyPair.publicKey)
        return IdentityKeyPair(identityKey, keyPair.privateKey)
    }

    fun initializeSession(remoteUserId: String, bundle: DevicesBundleResponse) {
        try {
            Log.d(TAG, "🔄 Initializing session for $remoteUserId...")

            val address = SignalProtocolAddress(remoteUserId, localDeviceId)

            val identityKey = IdentityKey(Base64.decode(bundle.identityKey, Base64.NO_WRAP), 0)
            val signedPreKeyPublic = Curve.decodePoint(Base64.decode(bundle.signedPreKeyPublic, Base64.NO_WRAP), 0)
            val signedPreKeySig = Base64.decode(bundle.signedPreKeySig, Base64.NO_WRAP)

            // ✅ Сервер отдаёт 0 или 1 one-time prekey (одноразовые).
            // Бандл без OTK — валидный: X3DH используется только signed prekey.
            val hasOneTimeKey = bundle.oneTimeKeys.isNotEmpty()
            val oneTimeKeyId = if (hasOneTimeKey) bundle.oneTimeKeys.first().keyId else -1
            val oneTimeKeyPublic = if (hasOneTimeKey)
                Curve.decodePoint(Base64.decode(bundle.oneTimeKeys.first().publicKey, Base64.NO_WRAP), 0)
            else null

            val preKeyBundle = PreKeyBundle(
                bundle.registrationId,
                localDeviceId,
                oneTimeKeyId,
                oneTimeKeyPublic,   // null = серверу нечего отдать, сессия строится без OTK
                bundle.signedPreKeyId,
                signedPreKeyPublic,
                signedPreKeySig,
                identityKey
            )

            try {
                val sessionBuilder = SessionBuilder(store, address)
                sessionBuilder.process(preKeyBundle)
                Log.d(TAG, "✅ Session initialized for $remoteUserId (OTK used: $hasOneTimeKey)")

            } catch (e: UntrustedIdentityException) {
                Log.w(TAG, "⚠️ Untrusted identity for $remoteUserId. Updating identity key and retrying...")
                try {
                    store.saveIdentity(address, preKeyBundle.identityKey)
                    Log.d(TAG, "✅ New identity key saved. Retrying session initialization...")
                    SessionBuilder(store, address).process(preKeyBundle)
                } catch (retryException: Exception) {
                    Log.e(TAG, "❌ Session init failed even after identity update", retryException)
                    throw retryException
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to initialize session", e)
            throw e
        }
    }

    fun encryptMessage(content: MessageContent, remoteUserId: String, remoteDeviceId: Int): EncryptedPayload {
        try {
            Log.d(TAG, "🔒 Encrypting message for $remoteUserId")
            val address = SignalProtocolAddress(remoteUserId, localDeviceId)
            val sessionCipher = SessionCipher(store, address)

            val jsonContent = content.toJson()

            val ciphertext = sessionCipher.encrypt(jsonContent.toByteArray(StandardCharsets.UTF_8))

            Log.d(TAG, "✅ Encryption successful")
            return EncryptedPayload(
                type = ciphertext.type,
                data = Base64.encodeToString(ciphertext.serialize(), Base64.NO_WRAP)
            )
        } catch (e: NoSessionException) {
            Log.e(TAG, "⚠️ NoSessionException: Session not found for $remoteUserId", e)
            throw IllegalStateException("Session not ready. Wait for keys to load.", e)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Encryption failed", e)
            throw e
        }
    }

    fun decryptMessage(payload: EncryptedPayload, senderUserId: String): MessageContent {
        Log.d(TAG, "🔓 Decrypting from $senderUserId, type=${payload.type}")

        if (payload.type == 0) {
            Log.e(TAG, "❌ Received payload with type=0! Message was not properly encrypted.")
            throw IllegalArgumentException("Invalid ciphertext type: 0. Message was not encrypted.")
        }

        val address = SignalProtocolAddress(senderUserId, localDeviceId)
        val sessionCipher = SessionCipher(store, address)
        val ciphertextBytes = Base64.decode(payload.data, Base64.NO_WRAP)

        try {
            val decryptedText = when (payload.type) {
                CiphertextMessage.PREKEY_TYPE -> {
                    Log.d(TAG, "  → PreKeySignalMessage")
                    val message = PreKeySignalMessage(ciphertextBytes)
                    val result = sessionCipher.decrypt(message)
                    String(result, StandardCharsets.UTF_8)
                }
                CiphertextMessage.WHISPER_TYPE -> {
                    Log.d(TAG, "  → SignalMessage")
                    val message = SignalMessage(ciphertextBytes)
                    val result = sessionCipher.decrypt(message)
                    String(result, StandardCharsets.UTF_8)
                }
                else -> throw IllegalArgumentException("Unknown ciphertext type: ${payload.type}")
            }
            return MessageContent.fromJson(decryptedText)
        } catch (e: UntrustedIdentityException) {
            Log.w(TAG, "⚠️ Untrusted identity for $senderUserId. Auto-trusting new identity key and retrying...")
            try {
                if (payload.type == CiphertextMessage.PREKEY_TYPE) {
                    val message = PreKeySignalMessage(ciphertextBytes)
                    val newIdentityKey = message.identityKey

                    saveIdentityKey(senderUserId, newIdentityKey)
                    Log.d(TAG, "✅ New identity key saved for $senderUserId. Retrying decrypt...")

                    val result = sessionCipher.decrypt(message)
                    val decryptedText = String(result, StandardCharsets.UTF_8)
                    return MessageContent.fromJson(decryptedText)
                } else {
                    Log.e(TAG, "❌ Regular message with untrusted identity. Re-init required.")
                    throw Exception("Decryption failed: Untrusted identity for regular message.")
                }
            } catch (retryException: Exception) {
                Log.e(TAG, "❌ Failed to decrypt even after trusting new identity", retryException)
                throw Exception("Decryption failed: Untrusted identity could not be resolved.")
            }
        } catch (e: org.signal.libsignal.protocol.DuplicateMessageException) {
            // Нормальный случай при догоне истории: сообщение уже было обработано
            // сессией ранее (счётчик ratchet продвинут). Это не ошибка — не пишем ERROR.
            Log.d(TAG, "↩️ Duplicate (old counter) from $senderUserId — already processed, skip")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "❌ Decryption failed for $senderUserId", e)
            throw e
        }
    }

    fun saveIdentityKey(remoteUserId: String, identityKey: org.signal.libsignal.protocol.IdentityKey) {
        val address = SignalProtocolAddress(remoteUserId, localDeviceId)
        store.saveIdentity(address, identityKey)
        Log.d(TAG, "🔐 Trusted identity saved for $remoteUserId")
    }

    fun createSessionCipher(remoteUserId: String): SessionCipher {
        val address = SignalProtocolAddress(remoteUserId, localDeviceId)
        return SessionCipher(store, address)
    }
}

data class EncryptedPayload(
    val type: Int = 0,
    val data: String = "",
    val senderKey: String = ""
)
