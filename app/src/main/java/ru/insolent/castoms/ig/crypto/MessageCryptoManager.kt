package ru.insolent.castoms.ig.crypto

import android.content.Context
import android.util.Base64
import android.util.Log
import ru.insolent.castoms.ig.network.DeviceKeysBundle
import ru.insolent.castoms.ig.network.DevicesBundleResponse
import org.signal.libsignal.protocol.*
import org.signal.libsignal.protocol.ecc.Curve
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.message.*
import org.signal.libsignal.protocol.state.*
import org.signal.libsignal.protocol.util.KeyHelper
import ru.insolent.castoms.ig.database.CryptoDatabase
import ru.insolent.castoms.ig.utils.SessionManager
import java.nio.charset.StandardCharsets

/**
 * Шифрование сообщений. 🌟 МУЛЬТИ-ДЕВАЙС: сессии строятся на пару
 * (userId, deviceId); у каждого устройства свой identity-key/registrationId.
 */
class MessageCryptoManager(
    private val localUserId: String,
    private val context: Context
) {
    private val TAG = "CryptoManager"

    /** id ЭТОГО устройства (int, для SignalProtocolAddress) */
    val localDeviceId: Int = SessionManager(context).getCryptoDeviceId()

    private val db = CryptoDatabase.getDatabase(context)
    private val dao = db.cryptoDao()

    private val secureStore = SecureKeyStore(context)

    /** Ключи этого устройства хранятся с префиксом "<userId>_<deviceId>_" */
    private fun keyPrefix() = "${localUserId}_${localDeviceId}_"

    private lateinit var identityKeyPair: IdentityKeyPair
    private var registrationId: Int
    private val store: SignalProtocolStore

    init {
        // 1. Identity + RegistrationId (per-device, fallback на легаси)
        val p = keyPrefix()
        val identityPrivateB64 = secureStore.getString("${p}identity_private", null)
            ?: secureStore.getString("${localUserId}_identity_private", null)   // легаси
        val regId = secureStore.getInt("${p}registration_id", -1).let {
            if (it != -1) it else secureStore.getInt("${localUserId}_registration_id", -1)
        }

        if (identityPrivateB64 != null && regId != -1) {
            val identityPrivateBytes = Base64.decode(identityPrivateB64, Base64.NO_WRAP)
            val identityPrivateKey = Curve.decodePrivatePoint(identityPrivateBytes)
            val identityPublicB64 = secureStore.getString("${p}identity_public", null)
                ?: secureStore.getString("${localUserId}_identity_public", "")!!
            val identityPublicKey = IdentityKey(
                Curve.decodePoint(Base64.decode(identityPublicB64, Base64.NO_WRAP), 0)
            )
            identityKeyPair = IdentityKeyPair(identityPublicKey, identityPrivateKey)
            registrationId = regId
            Log.d(TAG, "✅ Loaded Identity Keys for $localUserId device $localDeviceId")
        } else {
            identityKeyPair = generateIdentityKeyPair()
            registrationId = KeyHelper.generateRegistrationId(false)
            Log.w(TAG, "⚠️ No stored keys for $localUserId — generated temporary pair")

            secureStore.putString("${p}identity_private",
                Base64.encodeToString(identityKeyPair.privateKey.serialize(), Base64.NO_WRAP))
            secureStore.putString("${p}identity_public",
                Base64.encodeToString(identityKeyPair.publicKey.serialize(), Base64.NO_WRAP))
            secureStore.putInt("${p}registration_id", registrationId)
        }

        // 2. Persistent Store
        store = PersistentSignalProtocolStore(identityKeyPair, registrationId, dao)

        // 3. SignedPreKey
        val signedB64 = secureStore.getString("${p}signed_prekey_record", null)
            ?: secureStore.getString("${localUserId}_signed_prekey_record", null)
        if (signedB64 != null) {
            try {
                val record = SignedPreKeyRecord(Base64.decode(signedB64, Base64.NO_WRAP))
                store.storeSignedPreKey(record.id, record)
                Log.d(TAG, "✅ Restored SignedPreKeyRecord ${record.id}")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to restore SignedPreKey", e)
            }
        }

        // 4. One-Time PreKeys
        val otkJson = secureStore.getString("${p}one_time_keys", null)
            ?: secureStore.getString("${localUserId}_one_time_keys", null)
        if (otkJson != null) {
            try {
                val jsonArray = org.json.JSONArray(otkJson)
                var loadedCount = 0
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val keyId = obj.getInt("id")
                    val privateKeyBytes = Base64.decode(obj.getString("private"), Base64.NO_WRAP)
                    val privateKey = Curve.decodePrivatePoint(privateKeyBytes)
                    val publicKey = privateKey.publicKey()
                    store.storePreKey(keyId, PreKeyRecord(keyId, ECKeyPair(publicKey, privateKey)))
                    loadedCount++
                }
                Log.d(TAG, "✅ Restored $loadedCount One-Time PreKeys to Room")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to restore One-Time PreKeys", e)
            }
        }
    }

    private fun generateIdentityKeyPair(): IdentityKeyPair {
        val keyPair = Curve.generateKeyPair()
        val identityKey = IdentityKey(keyPair.publicKey)
        return IdentityKeyPair(identityKey, keyPair.privateKey)
    }

    /**
     * Инициализация сессий со ВСЕМИ устройствами собеседника.
     * Bundle содержит per-device registrationId и (опционально) OTK.
     */
    fun initializeSession(remoteUserId: String, bundle: DevicesBundleResponse) {
        for (device in bundle.devices) {
            try {
                val remoteDeviceId = device.deviceId.toInt()
                val address = SignalProtocolAddress(remoteUserId, remoteDeviceId)

                val identityKey = IdentityKey(Base64.decode(device.identityKey, Base64.NO_WRAP), 0)
                val signedPreKeyPublic = Curve.decodePoint(Base64.decode(device.signedPreKeyPublic, Base64.NO_WRAP), 0)
                val signedPreKeySig = Base64.decode(device.signedPreKeySig, Base64.NO_WRAP)

                val hasOtk = device.oneTimeKeys.isNotEmpty()
                val oneTimeKeyId = if (hasOtk) device.oneTimeKeys.first().keyId else -1
                val oneTimeKeyPublic = if (hasOtk)
                    Curve.decodePoint(Base64.decode(device.oneTimeKeys.first().publicKey, Base64.NO_WRAP), 0)
                else null

                val preKeyBundle = PreKeyBundle(
                    device.registrationId,
                    remoteDeviceId,
                    oneTimeKeyId,
                    oneTimeKeyPublic,
                    device.signedPreKeyId,
                    signedPreKeyPublic,
                    signedPreKeySig,
                    identityKey
                )

                try {
                    SessionBuilder(store, address).process(preKeyBundle)
                    Log.d(TAG, "✅ Session init: $remoteUserId device $remoteDeviceId (OTK: $hasOtk)")
                } catch (e: UntrustedIdentityException) {
                    Log.w(TAG, "⚠️ Untrusted identity $remoteUserId:$remoteDeviceId — saving new identity and retrying")
                    store.saveIdentity(address, preKeyBundle.identityKey)
                    SessionBuilder(store, address).process(preKeyBundle)
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Session init failed for device ${device.deviceId}", e)
            }
        }
    }

    /** Удобная перегрузка: инициализация по одному устройству */
    fun initializeSession(remoteUserId: String, device: DeviceKeysBundle) {
        initializeSession(remoteUserId, DevicesBundleResponse(listOf(device)))
    }

    fun encryptMessage(content: MessageContent, remoteUserId: String, remoteDeviceId: Int): EncryptedPayload {
        try {
            val address = SignalProtocolAddress(remoteUserId, remoteDeviceId)
            val sessionCipher = SessionCipher(store, address)
            val ciphertext = sessionCipher.encrypt(content.toJson().toByteArray(StandardCharsets.UTF_8))
            Log.d(TAG, "✅ Encrypted for $remoteUserId:$remoteDeviceId (type=${ciphertext.type})")
            return EncryptedPayload(
                type = ciphertext.type,
                data = Base64.encodeToString(ciphertext.serialize(), Base64.NO_WRAP)
            )
        } catch (e: NoSessionException) {
            Log.e(TAG, "⚠️ NoSessionException: $remoteUserId:$remoteDeviceId", e)
            throw IllegalStateException("Session not ready for $remoteUserId device $remoteDeviceId", e)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Encryption failed", e)
            throw e
        }
    }

    fun decryptMessage(payload: EncryptedPayload, senderUserId: String, senderDeviceId: Int): MessageContent {
        Log.d(TAG, "🔓 Decrypting from $senderUserId:$senderDeviceId, type=${payload.type}")
        if (payload.type == 0) {
            throw IllegalArgumentException("Invalid ciphertext type: 0. Message was not encrypted.")
        }

        val address = SignalProtocolAddress(senderUserId, senderDeviceId)
        val sessionCipher = SessionCipher(store, address)
        val ciphertextBytes = Base64.decode(payload.data, Base64.NO_WRAP)

        try {
            val decryptedText = when (payload.type) {
                CiphertextMessage.PREKEY_TYPE -> {
                    val message = PreKeySignalMessage(ciphertextBytes)
                    String(sessionCipher.decrypt(message), StandardCharsets.UTF_8)
                }
                CiphertextMessage.WHISPER_TYPE -> {
                    val message = SignalMessage(ciphertextBytes)
                    String(sessionCipher.decrypt(message), StandardCharsets.UTF_8)
                }
                else -> throw IllegalArgumentException("Unknown ciphertext type: ${payload.type}")
            }
            return MessageContent.fromJson(decryptedText)
        } catch (e: UntrustedIdentityException) {
            Log.w(TAG, "⚠️ Untrusted identity for $senderUserId:$senderDeviceId — trusting new identity and retrying")
            if (payload.type == CiphertextMessage.PREKEY_TYPE) {
                val message = PreKeySignalMessage(ciphertextBytes)
                store.saveIdentity(address, message.identityKey)
                val result = sessionCipher.decrypt(message)
                return MessageContent.fromJson(String(result, StandardCharsets.UTF_8))
            }
            throw Exception("Decryption failed: Untrusted identity for regular message.")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Decryption failed for $senderUserId:$senderDeviceId", e)
            throw e
        }
    }

    fun saveIdentityKey(remoteUserId: String, remoteDeviceId: Int, identityKey: IdentityKey) {
        store.saveIdentity(SignalProtocolAddress(remoteUserId, remoteDeviceId), identityKey)
    }

    fun createSessionCipher(remoteUserId: String, remoteDeviceId: Int): SessionCipher {
        return SessionCipher(store, SignalProtocolAddress(remoteUserId, remoteDeviceId))
    }
}

data class EncryptedPayload(
    val type: Int = 0,
    val data: String = "",
    val senderKey: String = ""
)
