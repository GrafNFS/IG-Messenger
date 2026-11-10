package ru.insolent.castoms.ig.crypto

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.ecc.Curve
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.util.KeyHelper
import ru.insolent.castoms.ig.network.*
import java.util.Date

class CryptoManager(private val context: Context) {

    /**
     * Генерация и загрузка криптографических ключей на сервер
     * Вызывается ОДИН РАЗ при регистрации или первом входе
     */
    private val localRegistrationId: Int = KeyHelper.generateRegistrationId(false)

    suspend fun generateAndUploadKeys(userId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            // 1. Генерация Identity Keys
            val identityKeyPair = generateIdentityKeyPair()

            // 2. Генерация Signed PreKey
            val signedPreKeyId = 1
            val signedPreKeyPair: ECKeyPair = Curve.generateKeyPair()
            val signedPreKeySignature = identityKeyPair.privateKey.calculateSignature(
                signedPreKeyPair.publicKey.serialize()
            )

            // 3. Генерация 30 One-Time PreKeys
            val oneTimeKeys = mutableListOf<OneTimeKeyData>()
            val oneTimeKeyPairs = mutableListOf<ECKeyPair>() // ✅ Сохраняем приватные части

            for (i in 1..30) {
                val keyPair: ECKeyPair = Curve.generateKeyPair()
                oneTimeKeyPairs.add(keyPair) // ✅ Запоминаем для сохранения
                oneTimeKeys.add(
                    OneTimeKeyData(
                        keyId = i,
                        publicKey = Base64.encodeToString(keyPair.publicKey.serialize(), Base64.NO_WRAP)
                    )
                )
            }

            // ✅ 4. СОХРАНЯЕМ ВСЕ КЛЮЧИ ЛОКАЛЬНО
            val signedPreKeyRecord = SignedPreKeyRecord(
                signedPreKeyId,
                Date().time,
                signedPreKeyPair,
                signedPreKeySignature
            )

            saveLocalKeys(
                userId,
                identityKeyPair,
                signedPreKeyRecord,
                oneTimeKeyPairs, // ✅ Передаём One-Time PreKeys
                localRegistrationId
            )

            // 5. Отправляем публичные ключи на сервер
            val request = KeysUploadRequest(
                userId = userId,
                identityKey = Base64.encodeToString(identityKeyPair.publicKey.serialize(), Base64.NO_WRAP),
                signedPreKeyId = signedPreKeyId,
                signedPreKeyPublic = Base64.encodeToString(signedPreKeyPair.publicKey.serialize(), Base64.NO_WRAP),
                signedPreKeySig = Base64.encodeToString(signedPreKeySignature, Base64.NO_WRAP),
                oneTimeKeys = oneTimeKeys.map { OneTimeKey(it.keyId, it.publicKey) },
                registrationId = localRegistrationId
            )

            val response = NetworkClient.apiService.uploadKeys(request)
            response.isSuccessful

        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Сохраняет приватные ключи в SharedPreferences
     * Вызывается ОДИН РАЗ при регистрации
     */
    private fun saveLocalKeys(
        userId: String,
        identityKeyPair: IdentityKeyPair,
        signedPreKeyRecord: SignedPreKeyRecord,
        oneTimeKeyPairs: List<ECKeyPair>, // ✅ Новый параметр
        registrationId: Int
    ) {
        val prefs = context.getSharedPreferences("crypto_keys", Context.MODE_PRIVATE)
        val editor = prefs.edit()

        // Identity Keys
        editor.putString("${userId}_identity_private",
            Base64.encodeToString(identityKeyPair.privateKey.serialize(), Base64.NO_WRAP))
        editor.putString("${userId}_identity_public",
            Base64.encodeToString(identityKeyPair.publicKey.serialize(), Base64.NO_WRAP))

        // Signed PreKey
        editor.putString("${userId}_signed_prekey_record",
            Base64.encodeToString(signedPreKeyRecord.serialize(), Base64.NO_WRAP))

        // ✅ One-Time PreKeys (сериализуем как JSON массив)
        val otkpJson = oneTimeKeyPairs.mapIndexed { index, keyPair ->
            """{"id":${index + 1},"private":"${Base64.encodeToString(keyPair.privateKey.serialize(), Base64.NO_WRAP)}"}"""
        }.joinToString(",", "[", "]")

        editor.putString("${userId}_one_time_keys", otkpJson)
        editor.putInt("${userId}_registration_id", registrationId)

        editor.apply()
        Log.d("CryptoManager", "✅ Saved keys for $userId (30 OTPs)")
    }

    /**
     * Вспомогательный метод для генерации IdentityKeyPair
     */
    private fun generateIdentityKeyPair(): IdentityKeyPair {
        val keyPair = Curve.generateKeyPair()
        val identityKey = org.signal.libsignal.protocol.IdentityKey(keyPair.publicKey)
        return IdentityKeyPair(identityKey, keyPair.privateKey)
    }
}

// Вспомогательные data-классы
data class OneTimeKeyData(val keyId: Int, val publicKey: String)