package ru.insolent.castoms.ig.crypto

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Drop-in замена getSharedPreferences("crypto_keys") для приватных ключей.
 * API совместим с используемым в CryptoManager/MessageCryptoManager,
 * но все значения шифруются мастер-ключом Android Keystore.
 *
 * При первом запуске автоматически мигрирует лежавшие в открытом виде значения
 * (старый формат Base64) в зашифрованный.
 *
 * Int хранится как зашифрованная строка "int:<значение>" под тем же ключом.
 */
class SecureKeyStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val cipher = MasterCipher()

    companion object {
        private const val TAG = "SecureKeyStore"
        private const val PREFS_NAME = "crypto_keys"
        private const val KEY_MIGRATED = "__secure_migrated_v1__"
        private const val INT_PREFIX = "int:"
    }

    init {
        migrateLegacyIfNeeded()
    }

    // === Миграция: открытый Base64 → enc1:<...> ===
    private fun migrateLegacyIfNeeded() {
        if (prefs.getBoolean(KEY_MIGRATED, false)) return

        val editor = prefs.edit()
        var migrated = 0
        for ((key, value) in prefs.all) {
            if (key == KEY_MIGRATED) continue
            when (value) {
                is String -> {
                    if (!value.startsWith(MasterCipher.MARKER)) {
                        editor.putString(key, cipher.encryptToString(value.toByteArray()))
                        migrated++
                    }
                }
                is Int -> {
                    editor.putString(key, cipher.encryptToString("$INT_PREFIX$value".toByteArray()))
                    migrated++
                }
                is Boolean -> editor.remove(key) // служебные флаги не переносим
                else -> {}
            }
        }
        editor.putBoolean(KEY_MIGRATED, true).apply()
        if (migrated > 0) {
            Log.i(TAG, "🔐 Migrated $migrated plaintext key values to encrypted storage")
        }
    }

    // === API ===

    fun putString(key: String, value: String) {
        prefs.edit().putString(key, cipher.encryptToString(value.toByteArray())).apply()
    }

    fun getString(key: String, defValue: String?): String? {
        val raw = prefs.getString(key, null) ?: return defValue
        return tryDecrypt(raw) ?: defValue
    }

    fun putInt(key: String, value: Int) {
        prefs.edit().putString(key, cipher.encryptToString("$INT_PREFIX$value".toByteArray())).apply()
    }

    fun getInt(key: String, defValue: Int): Int {
        val raw = prefs.getString(key, null) ?: return defValue
        val plain = tryDecrypt(raw) ?: return defValue
        return plain.removePrefix(INT_PREFIX).toIntOrNull() ?: defValue
    }

    fun contains(key: String): Boolean = prefs.contains(key)

    fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    /**
     * Дешифровка с защитой от потери Keystore-ключа:
     * если дешифровать не удалось — значение удаляется (ключи будут перегенерированы).
     */
    private fun tryDecrypt(raw: String): String? {
        if (!raw.startsWith(MasterCipher.MARKER)) return raw // legacy, до миграции
        return try {
            String(cipher.decryptFromString(raw), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "⚠️ Cannot decrypt '$raw'-key, Keystore key lost. Wiping value.", e)
            null
        }
    }
}
