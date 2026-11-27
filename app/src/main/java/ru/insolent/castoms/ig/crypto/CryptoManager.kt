package ru.insolent.castoms.ig.crypto

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.ecc.Curve
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.util.KeyHelper
import ru.insolent.castoms.ig.network.*
import ru.insolent.castoms.ig.utils.SessionManager
import java.util.Date

/**
 * Управление ключами Signal Protocol устройства.
 * 🌟 МУЛЬТИ-ДЕВАЙС: у каждого устройства свой identity-key и registrationId.
 * Ключи хранятся с префиксом "<userId>_<cryptoDeviceId>_"; легаси-ключи
 * (без префикса) автоматически переносятся на это устройство при первом запуске.
 */
class CryptoManager(private val context: Context) {

    companion object {
        private const val TAG = "CryptoManager"
        private const val REFILL_THRESHOLD = 30
        private const val BATCH_SIZE = 25
        private const val MAX_LOCAL_OTK = 300
    }

    private val sessionManager = SessionManager(context)

    /** Числовой id устройства для Signal Protocol (int) */
    val deviceId: Int = sessionManager.getCryptoDeviceId()

    private val secureStore = SecureKeyStore(context)

    /** Префикс ключей хранилища для пары (user, device) */
    private fun prefix(userId: String) = "${userId}_${deviceId}_"

    init {
        migrateLegacyKeysIfNeeded()
    }

    /** Перенос легаси-ключей (формат до мульти-девайса) на это устройство */
    private fun migrateLegacyKeysIfNeeded() {
        // Нужен userId для префикса — берём из сессии; если нет, миграция
        // произойдёт при первом generateAndUploadKeys
        val userId = sessionManager.getUserId() ?: return
        val newPrefix = prefix(userId)

        val legacyIdentity = secureStore.getString("${userId}_identity_private", null)
        val newIdentity = secureStore.getString("${newPrefix}identity_private", null)
        if (legacyIdentity == null || newIdentity != null) return

        secureStore.getString("${userId}_identity_public", null)?.let {
            secureStore.putString("${newPrefix}identity_public", it)
        }
        secureStore.getString("${userId}_signed_prekey_record", null)?.let {
            secureStore.putString("${newPrefix}signed_prekey_record", it)
        }
        secureStore.getString("${userId}_one_time_keys", null)?.let {
            secureStore.putString("${newPrefix}one_time_keys", it)
        }
        secureStore.putInt("${newPrefix}registration_id",
            secureStore.getInt("${userId}_registration_id", -1))
        secureStore.putString("${newPrefix}identity_private", legacyIdentity)
        Log.i(TAG, "🔁 Migrated legacy keys to device $deviceId for $userId")
    }

    /** Генерация и загрузка ключей этого устройства */
    suspend fun generateAndUploadKeys(userId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val registrationId = KeyHelper.generateRegistrationId(false)
            val identityKeyPair = generateIdentityKeyPair()
            val signedPreKeyId = 1
            val signedPreKeyPair: ECKeyPair = Curve.generateKeyPair()
            val signedPreKeySignature = identityKeyPair.privateKey.calculateSignature(
                signedPreKeyPair.publicKey.serialize()
            )
            val oneTimeKeys = mutableListOf<OneTimeKey>()
            val oneTimeKeyPairs = mutableListOf<Pair<Int, ECKeyPair>>()
            for (i in 1..30) {
                val keyPair: ECKeyPair = Curve.generateKeyPair()
                oneTimeKeyPairs.add(i to keyPair)
                oneTimeKeys.add(
                    OneTimeKey(i, Base64.encodeToString(keyPair.publicKey.serialize(), Base64.NO_WRAP))
                )
            }

            val signedPreKeyRecord = SignedPreKeyRecord(signedPreKeyId, Date().time, signedPreKeyPair, signedPreKeySignature)
            saveLocalKeys(userId, identityKeyPair, signedPreKeyRecord, oneTimeKeyPairs, registrationId)

            val request = KeysUploadRequest(
                deviceId = deviceId.toString(),
                userId = userId,
                identityKey = Base64.encodeToString(identityKeyPair.publicKey.serialize(), Base64.NO_WRAP),
                signedPreKeyId = signedPreKeyId,
                signedPreKeyPublic = Base64.encodeToString(signedPreKeyPair.publicKey.serialize(), Base64.NO_WRAP),
                signedPreKeySig = Base64.encodeToString(signedPreKeySignature, Base64.NO_WRAP),
                oneTimeKeys = oneTimeKeys,
                registrationId = registrationId
            )

            val response = NetworkClient.apiService.uploadKeys(request)
            val ok = response.isSuccessful
            if (ok) {
                context.getSharedPreferences("crypto_prefs", Context.MODE_PRIVATE)
                    .edit().putString("uploaded_device_id", deviceId.toString()).apply()
            }
            ok
        } catch (e: Exception) {
            Log.e(TAG, "generateAndUploadKeys failed", e)
            false
        }
    }

    /** Ротация one-time prekeys ЭТОГО устройства */
    suspend fun refillOneTimePreKeysIfNeeded(userId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val resp = NetworkClient.apiService.getRemainingPreKeys(deviceId.toString())
            if (!resp.isSuccessful) return@withContext false
            val remaining = resp.body()?.remaining ?: return@withContext false

            if (remaining >= REFILL_THRESHOLD) {
                Log.d(TAG, "OTK supply OK ($remaining on server, device $deviceId)")
                return@withContext true
            }

            Log.i(TAG, "⚠️ OTK supply low ($remaining), refilling +$BATCH_SIZE...")
            val registrationId = secureStore.getInt("${prefix(userId)}registration_id", -1)
            if (registrationId == -1) return@withContext false

            val startId = getNextOneTimeKeyId(userId)
            val newPublicKeys = mutableListOf<OneTimeKey>()
            val newPrivateKeys = mutableListOf<Pair<Int, ECKeyPair>>()
            for (i in 0 until BATCH_SIZE) {
                val keyId = startId + i
                val keyPair = Curve.generateKeyPair()
                newPublicKeys.add(OneTimeKey(keyId, Base64.encodeToString(keyPair.publicKey.serialize(), Base64.NO_WRAP)))
                newPrivateKeys.add(keyId to keyPair)
            }
            appendOneTimeKeys(userId, newPrivateKeys)

            val otkResp = NetworkClient.apiService.uploadOneTimeKeys(OneTimeKeysUploadRequest(deviceId.toString(), newPublicKeys))
            if (otkResp.isSuccessful) {
                Log.i(TAG, "✅ Uploaded ${newPublicKeys.size} OTKs (light, device $deviceId)")
                return@withContext true
            }

            // Фолбэк: полный uploadKeys (сервер мержит OTK)
            val identityPublic = secureStore.getString("${prefix(userId)}identity_public", null)
            val signedRecordB64 = secureStore.getString("${prefix(userId)}signed_prekey_record", null)
            if (identityPublic == null || signedRecordB64 == null) return@withContext false
            val signedRecord = SignedPreKeyRecord(Base64.decode(signedRecordB64, Base64.NO_WRAP))

            val request = KeysUploadRequest(
                deviceId = deviceId.toString(),
                userId = userId,
                identityKey = identityPublic,
                signedPreKeyId = signedRecord.id,
                signedPreKeyPublic = Base64.encodeToString(signedRecord.keyPair.publicKey.serialize(), Base64.NO_WRAP),
                signedPreKeySig = Base64.encodeToString(signedRecord.signature, Base64.NO_WRAP),
                oneTimeKeys = newPublicKeys,
                registrationId = registrationId
            )
            NetworkClient.apiService.uploadKeys(request).isSuccessful
        } catch (e: Exception) {
            Log.e(TAG, "refillOneTimePreKeysIfNeeded failed", e)
            false
        }
    }

    // ==================== Локальное хранение (зашифрованное, per-device) ====================

    private fun saveLocalKeys(
        userId: String,
        identityKeyPair: IdentityKeyPair,
        signedPreKeyRecord: SignedPreKeyRecord,
        oneTimeKeyPairs: List<Pair<Int, ECKeyPair>>,
        registrationId: Int
    ) {
        val p = prefix(userId)
        secureStore.putString("${p}identity_private", Base64.encodeToString(identityKeyPair.privateKey.serialize(), Base64.NO_WRAP))
        secureStore.putString("${p}identity_public", Base64.encodeToString(identityKeyPair.publicKey.serialize(), Base64.NO_WRAP))
        secureStore.putString("${p}signed_prekey_record", Base64.encodeToString(signedPreKeyRecord.serialize(), Base64.NO_WRAP))
        secureStore.putString("${p}one_time_keys", oneTimeKeysToJson(oneTimeKeyPairs))
        secureStore.putInt("${p}registration_id", registrationId)
        Log.d(TAG, "✅ Saved encrypted keys for $userId device $deviceId")
    }

    private fun oneTimeKeysToJson(keys: List<Pair<Int, ECKeyPair>>): String {
        val arr = JSONArray()
        for ((keyId, keyPair) in keys) {
            arr.put(JSONObject()
                .put("id", keyId)
                .put("private", Base64.encodeToString(keyPair.privateKey.serialize(), Base64.NO_WRAP)))
        }
        return arr.toString()
    }

    private fun appendOneTimeKeys(userId: String, newKeys: List<Pair<Int, ECKeyPair>>) {
        val p = prefix(userId)
        val existingJson = secureStore.getString("${p}one_time_keys", "[]") ?: "[]"
        val arr = try { JSONArray(existingJson) } catch (e: Exception) { JSONArray() }

        for ((keyId, keyPair) in newKeys) {
            arr.put(JSONObject()
                .put("id", keyId)
                .put("private", Base64.encodeToString(keyPair.privateKey.serialize(), Base64.NO_WRAP)))
        }

        val trimmed = if (arr.length() > MAX_LOCAL_OTK) {
            val keep = JSONArray()
            for (i in arr.length() - MAX_LOCAL_OTK until arr.length()) keep.put(arr.getJSONObject(i))
            keep
        } else arr

        secureStore.putString("${p}one_time_keys", trimmed.toString())
    }

    private fun getNextOneTimeKeyId(userId: String): Int {
        val existingJson = secureStore.getString("${prefix(userId)}one_time_keys", "[]") ?: "[]"
        return try {
            val arr = JSONArray(existingJson)
            var maxId = 0
            for (i in 0 until arr.length()) {
                maxId = maxOf(maxId, arr.getJSONObject(i).getInt("id"))
            }
            maxId + 1
        } catch (e: Exception) { 1 }
    }

    private fun generateIdentityKeyPair(): IdentityKeyPair {
        val keyPair = Curve.generateKeyPair()
        val identityKey = org.signal.libsignal.protocol.IdentityKey(keyPair.publicKey)
        return IdentityKeyPair(identityKey, keyPair.privateKey)
    }

    /**
     * Гарантирует, что ключи ЭТОГО устройства залиты на сервер в новом формате.
     * Вызывать при старте приложения после логина.
     */
    suspend fun ensureKeysUploaded(userId: String): Boolean = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("crypto_prefs", Context.MODE_PRIVATE)
        val uploadedFor = prefs.getString("uploaded_device_id", null)
        val p = prefix(userId)
        val haveKeys = secureStore.getString("${p}identity_public", null) != null

        if (uploadedFor == deviceId.toString() && haveKeys) return@withContext true

        if (haveKeys) {
            // Перезаливаем существующие ключи под id этого устройства
            val identityPublic = secureStore.getString("${p}identity_public", null)!!
            val signedB64 = secureStore.getString("${p}signed_prekey_record", null)
            val registrationId = secureStore.getInt("${p}registration_id", -1)
            if (signedB64 == null || registrationId == -1) return@withContext generateAndUploadKeys(userId)

            val signed = SignedPreKeyRecord(Base64.decode(signedB64, Base64.NO_WRAP))
            // Публичные части OTK восстанавливаем из хранимых приватных
            val otkPublics = mutableListOf<OneTimeKey>()
            val otkJson = secureStore.getString("${p}one_time_keys", "[]") ?: "[]"
            try {
                val arr = JSONArray(otkJson)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val priv = Curve.decodePrivatePoint(Base64.decode(obj.getString("private"), Base64.NO_WRAP))
                    otkPublics.add(OneTimeKey(obj.getInt("id"),
                        Base64.encodeToString(priv.publicKey().serialize(), Base64.NO_WRAP)))
                }
            } catch (_: Exception) {}

            val request = KeysUploadRequest(
                deviceId = deviceId.toString(),
                userId = userId,
                identityKey = identityPublic,
                signedPreKeyId = signed.id,
                signedPreKeyPublic = Base64.encodeToString(signed.keyPair.publicKey.serialize(), Base64.NO_WRAP),
                signedPreKeySig = Base64.encodeToString(signed.signature, Base64.NO_WRAP),
                oneTimeKeys = otkPublics,
                registrationId = registrationId
            )
            val ok = NetworkClient.apiService.uploadKeys(request).isSuccessful
            if (ok) prefs.edit().putString("uploaded_device_id", deviceId.toString()).apply()
            ok
        } else {
            generateAndUploadKeys(userId)
        }
    }
}
