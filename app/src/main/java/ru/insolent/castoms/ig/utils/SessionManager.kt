package ru.insolent.castoms.ig.utils

import android.content.Context

class SessionManager(context: Context) {
    private val prefs = context.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)

    companion object {
        const val KEY_TOKEN = "token"
        const val KEY_USER_ID = "user_id"
    }

    // Сохранить сессию
    fun saveSession(token: String, userId: String) {
        prefs.edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_USER_ID, userId)
            .apply()
    }

    // Проверить, залогинен ли пользователь
    fun isLoggedIn(): Boolean {
        return prefs.getString(KEY_TOKEN, null) != null
    }

    // Получить токен
    fun saveToken(token: String) {
        prefs.edit().putString("jwt_token", token).apply()
    }

    fun getToken(): String? {
        return prefs.getString("jwt_token", null)
    }

    fun clearToken() {
        prefs.edit().remove("jwt_token").apply()
    }

    fun getUserId(): String? {
        return prefs.getString("user_id", null)
    }

    fun getDeviceId(): String {
        var deviceId = prefs.getString("device_id", null)
        if (deviceId == null) {
            deviceId = java.util.UUID.randomUUID().toString()
            prefs.edit().putString("device_id", deviceId).apply()
        }
        return deviceId
    }

    fun saveUserId(userId: String) {
        prefs.edit().putString("user_id", userId).apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }
}