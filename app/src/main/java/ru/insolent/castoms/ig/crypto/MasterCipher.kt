package ru.insolent.castoms.ig.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Мастер-ключ AES-256 в Android Keystore.
 * Им шифруются приватные ключи Signal перед записью в SharedPreferences.
 *
 * ⚠️ Если Keystore-ключ потерян (сброс устройства, восстановление из бэкапа на другом
 * устройстве) — старые данные недешифруемы. SecureKeyStore в этом случае удаляет их,
 * а приложение перегенерирует ключи (как будто первый вход на устройстве).
 */
class MasterCipher {

    private val keyStore: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    companion object {
        private const val TAG = "MasterCipher"
        private const val KEY_ALIAS = "ig_master_key_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val IV_LENGTH = 12
        const val MARKER = "enc1:"
    }

    private fun getOrCreateKey(): SecretKey {
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // Можно добавить привязку к биометрии — тогда расшифровка только после unlock:
                // .setUserAuthenticationRequired(true)
                .build()
        )
        Log.i(TAG, "🔑 Created new master key in Android Keystore")
        return generator.generateKey()
    }

    /** Шифрует байты → "enc1:<Base64(IV + ciphertext)>" */
    fun encryptToString(plain: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plain)
        return MARKER + Base64.encodeToString(iv + ciphertext, Base64.NO_WRAP)
    }

    /** Дешифрует строку с маркером enc1:. Бросает исключение, если ключ недоступен. */
    fun decryptFromString(value: String): ByteArray {
        val bytes = Base64.decode(value.removePrefix(MARKER), Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, bytes.copyOfRange(0, IV_LENGTH)))
        return cipher.doFinal(bytes.copyOfRange(IV_LENGTH, bytes.size))
    }
}
