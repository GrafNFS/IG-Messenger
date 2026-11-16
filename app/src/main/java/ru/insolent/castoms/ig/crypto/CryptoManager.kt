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
import java.util.Date

class CryptoManager(private val context: Context) {

    companion object {
        private const val TAG = "CryptoManager"
        private const val REFILL_THRESHOLD = 30  // ниже этого остатка на сервере — пополняем
        private const val BATCH_SIZE = 25        // сколько OTK генерим за раз
        private const val MAX_LOCAL_OTK = 300    // локальный потолок, старые подрезаем
    }

    // ✅ Приватные ключи теперь шифруются мастер-ключом Android Keystore
    private val secureStore = SecureKeyStore(context)

    /**
     * Генерация и загрузка ключей на сервер.
     * Вызывается ОДИН РАЗ при регистрации или первом входе на устройстве.
     */
    suspend fun generateAndUploadKeys(userId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val registrationId = KeyHelper.generateRegistrationId(false)

            // 1. Identity Keys
            val identityKeyPair = generateIdentityKeyPair()

            // 2. Signed PreKey
            val signedPreKeyId = 1
            val signedPreKeyPair: ECKeyPair = Curve.generateKeyPair()
            val signedPreKeySignature = identityKeyPair.privateKey.calculateSignature(
                signedPreKeyPair.publicKey.serialize()
            )

            // 3. 30 One-Time PreKeys
            val oneTimeKeys = mutableListOf<OneTimeKey>()
            val oneTimeKeyPairs = mutableListOf<Pair<Int, ECKeyPair>>()
            for (i in 1..30) {
                val keyPair: ECKeyPair = Curve.generateKeyPair()
                oneTimeKeyPairs.add(i to keyPair)
                oneTimeKeys.add(
                    OneTimeKey(
                        keyId = i,
                        publicKey = Base64.encodeToString(keyPair.publicKey.serialize(), Base64.NO_WRAP)
                    )
                )
            }

            // 4. Сохраняем ВСЕ приватные ключи ЛОКАЛЬНО (зашифрованно)
            val signedPreKeyRecord = SignedPreKeyRecord(
                signedPreKeyId,
                Date().time,
                signedPreKeyPair,
                signedPreKeySignature
            )
            saveLocalKeys(userId, identityKeyPair, signedPreKeyRecord, oneTimeKeyPairs, registrationId)

            // 5. Публичные ключи — на сервер
            val request = KeysUploadRequest(
                userId = userId,
                identityKey = Base64.encodeToString(identityKeyPair.publicKey.serialize(), Base64.NO_WRAP),
                signedPreKeyId = signedPreKeyId,
                signedPreKeyPublic = Base64.encodeToString(signedPreKeyPair.publicKey.serialize(), Base64.NO_WRAP),
                signedPreKeySig = Base64.encodeToString(signedPreKeySignature, Base64.NO_WRAP),
                oneTimeKeys = oneTimeKeys,
                registrationId = registrationId
            )

            val response = NetworkClient.apiService.uploadKeys(request)
            response.isSuccessful

        } catch (e: Exception) {
            Log.e(TAG, "generateAndUploadKeys failed", e)
            false
        }
    }

    /**
     * РОТАЦИЯ one-time prekeys.
     * Проверяет остаток на сервере (GET /api/keys/count/remaining) и пополняет
     * запас пачкой BATCH_SIZE, если остаток ниже REFILL_THRESHOLD.
     *
     * Вызывается автоматически при подключении сокета и раз в сутки воркером.
     */
    suspend fun refillOneTimePreKeysIfNeeded(userId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val countResp = NetworkClient.apiService.getRemainingPreKeys()
            if (!countResp.isSuccessful) return@withContext false
            val remaining = countResp.body()?.remaining ?: return@withContext false

            if (remaining >= REFILL_THRESHOLD) {
                Log.d(TAG, "OTK supply OK ($remaining on server)")
                return@withContext true
            }

            Log.i(TAG, "⚠️ OTK supply low ($remaining), refilling +$BATCH_SIZE...")

            val registrationId = secureStore.getInt("${userId}_registration_id", -1)
            if (registrationId == -1) {
                Log.e(TAG, "No stored registrationId — cannot refill. Run generateAndUploadKeys.")
                return@withContext false
            }

            // Генерируем новую пачку с keyId от max+1
            val startId = getNextOneTimeKeyId(userId)
            val newPublicKeys = mutableListOf<OneTimeKey>()
            val newPrivateKeys = mutableListOf<Pair<Int, ECKeyPair>>()
            for (i in 0 until BATCH_SIZE) {
                val keyId = startId + i
                val keyPair = Curve.generateKeyPair()
                newPublicKeys.add(
                    OneTimeKey(keyId, Base64.encodeToString(keyPair.publicKey.serialize(), Base64.NO_WRAP))
                )
                newPrivateKeys.add(keyId to keyPair)
            }

            // Сохраняем приватные части локально (зашифрованно), ДО загрузки на сервер
            appendOneTimeKeys(userId, newPrivateKeys)

            // Вариант A: лёгкий эндпоинт только с OTK (см. PATCHES.md, серверный сниппет)
            val otkResp = NetworkClient.apiService.uploadOneTimeKeys(OneTimeKeysUploadRequest(newPublicKeys))
            if (otkResp.isSuccessful) {
                Log.i(TAG, "✅ Uploaded ${newPublicKeys.size} OTKs (light endpoint)")
                return@withContext true
            }

            // Вариант B (фолбэк): полный uploadKeys — серверная версия мержит OTK, не затирая
            val identityPublic = secureStore.getString("${userId}_identity_public", null)
            val signedRecordB64 = secureStore.getString("${userId}_signed_prekey_record", null)
            if (identityPublic == null || signedRecordB64 == null) {
                Log.e(TAG, "Missing identity/signed prekey for fallback upload")
                return@withContext false
            }
            val signedRecord = SignedPreKeyRecord(Base64.decode(signedRecordB64, Base64.NO_WRAP))

            val request = KeysUploadRequest(
                userId = userId,
                identityKey = identityPublic,
                signedPreKeyId = signedRecord.id,
                signedPreKeyPublic = Base64.encodeToString(signedRecord.keyPair.publicKey.serialize(), Base64.NO_WRAP),
                signedPreKeySig = Base64.encodeToString(signedRecord.signature, Base64.NO_WRAP),
                oneTimeKeys = newPublicKeys,
                registrationId = registrationId
            )
            val ok = NetworkClient.apiService.uploadKeys(request).isSuccessful
            if (ok) Log.i(TAG, "✅ Uploaded ${newPublicKeys.size} OTKs (fallback full upload)")
            ok

        } catch (e: Exception) {
            Log.e(TAG, "refillOneTimePreKeysIfNeeded failed", e)
            false
        }
    }

    // ==================== Локальное хранение (зашифрованное) ====================

    private fun saveLocalKeys(
        userId: String,
        identityKeyPair: IdentityKeyPair,
        signedPreKeyRecord: SignedPreKeyRecord,
        oneTimeKeyPairs: List<Pair<Int, ECKeyPair>>,
        registrationId: Int
    ) {
        secureStore.putString("${userId}_identity_private",
            Base64.encodeToString(identityKeyPair.privateKey.serialize(), Base64.NO_WRAP))
        secureStore.putString("${userId}_identity_public",
            Base64.encodeToString(identityKeyPair.publicKey.serialize(), Base64.NO_WRAP))
        secureStore.putString("${userId}_signed_prekey_record",
            Base64.encodeToString(signedPreKeyRecord.serialize(), Base64.NO_WRAP))
        secureStore.putString("${userId}_one_time_keys", oneTimeKeysToJson(oneTimeKeyPairs))
        secureStore.putInt("${userId}_registration_id", registrationId)

        Log.d(TAG, "✅ Saved encrypted keys for $userId (${oneTimeKeyPairs.size} OTPs)")
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

    /** Добавляет новые OTK к существующим и подрезает старые сверх MAX_LOCAL_OTK */
    private fun appendOneTimeKeys(userId: String, newKeys: List<Pair<Int, ECKeyPair>>) {
        val existingJson = secureStore.getString("${userId}_one_time_keys", "[]") ?: "[]"
        val arr = try { JSONArray(existingJson) } catch (e: Exception) { JSONArray() }

        for ((keyId, keyPair) in newKeys) {
            arr.put(JSONObject()
                .put("id", keyId)
                .put("private", Base64.encodeToString(keyPair.privateKey.serialize(), Base64.NO_WRAP)))
        }

        // Подрезаем самые старые (уже выданные сервером и не нужные локально)
        val trimmed = if (arr.length() > MAX_LOCAL_OTK) {
            val keep = JSONArray()
            for (i in arr.length() - MAX_LOCAL_OTK until arr.length()) keep.put(arr.getJSONObject(i))
            Log.d(TAG, "Pruned old OTKs to $MAX_LOCAL_OTK")
            keep
        } else arr

        secureStore.putString("${userId}_one_time_keys", trimmed.toString())
        Log.d(TAG, "✅ Stored ${newKeys.size} new OTKs (local total: ${trimmed.length()})")
    }

    private fun getNextOneTimeKeyId(userId: String): Int {
        val existingJson = secureStore.getString("${userId}_one_time_keys", "[]") ?: "[]"
        return try {
            val arr = JSONArray(existingJson)
            var maxId = 0
            for (i in 0 until arr.length()) {
                maxId = maxOf(maxId, arr.getJSONObject(i).getInt("id"))
            }
            maxId + 1
        } catch (e: Exception) {
            1
        }
    }

    private fun generateIdentityKeyPair(): IdentityKeyPair {
        val keyPair = Curve.generateKeyPair()
        val identityKey = org.signal.libsignal.protocol.IdentityKey(keyPair.publicKey)
        return IdentityKeyPair(identityKey, keyPair.privateKey)
    }
}
