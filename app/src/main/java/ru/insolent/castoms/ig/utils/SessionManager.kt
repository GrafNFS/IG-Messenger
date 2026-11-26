package ru.insolent.castoms.ig.utils

import android.content.Context
import android.content.SharedPreferences

/**
 * Единая точка хранения сессии.
 * ⚠️ Раньше токен писался по ключу "token" (saveSession), а читался по "jwt_token"
 * (getToken) — два разных ключа. Теперь ключ один: jwt_token.
 * При первом запуске старое значение автоматически переносится.
 */
class SessionManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "auth_prefs"
        const val KEY_TOKEN = "jwt_token"
        const val KEY_USER_ID = "user_id"
        const val KEY_DEVICE_ID = "device_id"

        // Старый ключ, по которому токен писался раньше
        private const val LEGACY_TOKEN_KEY = "token"
    }

    init {
        // Миграция: "token" -> "jwt_token"
        if (!prefs.contains(KEY_TOKEN) && prefs.contains(LEGACY_TOKEN_KEY)) {
            val legacy = prefs.getString(LEGACY_TOKEN_KEY, null)
            if (legacy != null) {
                prefs.edit()
                    .putString(KEY_TOKEN, legacy)
                    .remove(LEGACY_TOKEN_KEY)
                    .apply()
            }
        }
    }

    /** Сохранить сессию (токен + userId) — атомарно */
    fun saveSession(token: String, userId: String) {
        prefs.edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_USER_ID, userId)
            .apply()
    }

    fun isLoggedIn(): Boolean = prefs.getString(KEY_TOKEN, null) != null

    fun saveToken(token: String) {
        prefs.edit().putString(KEY_TOKEN, token).apply()
    }

    fun getToken(): String? = prefs.getString(KEY_TOKEN, null)

    fun clearToken() {
        prefs.edit().remove(KEY_TOKEN).apply()
    }

    fun getUserId(): String? = prefs.getString(KEY_USER_ID, null)

    fun saveUserId(userId: String) {
        prefs.edit().putString(KEY_USER_ID, userId).apply()
    }

    fun getDeviceId(): String {
        var deviceId = prefs.getString(KEY_DEVICE_ID, null)
        if (deviceId == null) {
            deviceId = java.util.UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply()
        }
        return deviceId
    }

    fun getCryptoDeviceId(): Int {
        var id = prefs.getInt("crypto_device_id", -1)
        if (id == -1) {
            id = java.util.Random().nextInt(16380) + 1
            prefs.edit().putInt("crypto_device_id", id).apply()
        }
        return id
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }
}
