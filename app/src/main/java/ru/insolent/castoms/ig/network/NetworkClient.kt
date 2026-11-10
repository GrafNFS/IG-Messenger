package ru.insolent.castoms.ig.network

import android.content.Context
import android.util.Log
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import ru.insolent.castoms.ig.utils.SessionManager
import java.util.concurrent.TimeUnit

object NetworkClient {
    private const val BASE_URL = "https://messenger.insolent-castoms.ru/"

    // Храним контекст приложения для доступа к SessionManager
    private lateinit var appContext: Context

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    private val authInterceptor = Interceptor { chain ->
        // ✅ Всегда читаем токен из SessionManager (актуальные данные)
        val sessionManager = SessionManager(appContext)
        val token = sessionManager.getToken()

        //Log.d("NetworkClient", "✅ Token authorization bearer $token")

        val request = if (!token.isNullOrEmpty()) {
            chain.request().newBuilder()
                .addHeader("Authorization", "Bearer $token")
                .build()
        } else {
            chain.request()
        }

        chain.proceed(request)
    }

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor)
        .addInterceptor(authInterceptor)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    val apiService: ApiService = retrofit.create(ApiService::class.java)

    /**
     * Инициализация NetworkClient с контекстом приложения
     * Вызови один раз при старте приложения (в Application классе или MainActivity)
     */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Установить токен (вызывается после успешного логина)
     */
    fun setToken(token: String) {
        val sessionManager = SessionManager(appContext)
        sessionManager.saveToken(token)
    }

    /**
     * Получить текущий токен
     */
    fun getToken(): String? {
        return if (::appContext.isInitialized) {
            SessionManager(appContext).getToken()
        } else {
            null
        }
    }

    /**
     * Очистить токен (при выходе из аккаунта)
     */
    fun clearToken() {
        if (::appContext.isInitialized) {
            val sessionManager = SessionManager(appContext)
            sessionManager.clearToken()
        }
    }

    suspend fun sendFcmTokenToServer(userId: String, deviceId: String, token: String) {
        try {
            val request = DeviceTokenRequest(
                userId = userId,
                deviceId = deviceId,
                pushToken = token
            )
            val response = apiService.registerDevice(request)
            if (response.isSuccessful) {
                Log.d("NetworkClient", "✅ FCM token sent to server")
            } else {
                Log.e("NetworkClient", "❌ Failed to send FCM token: ${response.code()}")
            }
        } catch (e: Exception) {
            Log.e("NetworkClient", "Error sending FCM token", e)
        }
    }
}