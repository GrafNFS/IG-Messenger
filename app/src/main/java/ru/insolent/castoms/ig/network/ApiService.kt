package ru.insolent.castoms.ig.network

import ru.insolent.castoms.ig.models.*
import retrofit2.Response
import retrofit2.http.*
import ru.insolent.castoms.ig.model.AppCheckVersion

interface ApiService {

    // Аутентификация
    @POST("api/register")
    suspend fun register(@Body request: RegisterRequest): Response<AuthResponse>

    @POST("api/login")
    suspend fun login(@Body request: LoginRequest): Response<AuthResponse>

    // Криптография
    @POST("api/keys/upload")
    suspend fun uploadKeys(@Body request: KeysUploadRequest): Response<KeysUploadResponse>

    @GET("api/keys/{userId}")
    suspend fun getKeysBundle(@Path("userId") userId: String): Response<KeysBundle>

    @GET("api/users/search")
    suspend fun searchUsers(
        @Query("query") query: String,
        @Query("userId") currentUserId: String
    ): Response<List<UserSearchResult>>

    @GET("api/contacts")
    suspend fun getMyContacts(@Query("userId") userId: String): Response<List<ApiContact>>

    @POST("api/contacts")
    suspend fun addContact(@Body request: AddContactRequest): Response<AddContactResponse>

    @POST("api/friend-requests")
    suspend fun sendFriendRequest(@Body request: AddContactRequest): Response<Any>

    @GET("api/friend-requests/pending")
    suspend fun getPendingRequests(@Query("userId") userId: String): Response<List<FriendRequestDto>>

    @GET("api/frends-requests/count")
    suspend fun getPendingCountRequests(@Query("userId") userId: String): Response<Int>

    @POST("api/friend-requests/respond")
    suspend fun respondToFriendRequest(@Body request: RespondRequestDto): Response<Any>


    @GET("api/chats/{chatId}/messages")
    suspend fun getChatHistory(
        @Path("chatId") chatId: String,
        @Query("userId") userId: String // ✅ Добавили параметр
    ): Response<List<MessageDTO>>

    @GET("api/users/{userId}/status")
    suspend fun getUserStatus(@Path("userId") userId: String): Response<UserStatusDTO>

    @Multipart
    @POST("api/media/upload")
    suspend fun uploadMedia(
        @Query("fileId") fileId: String,
        @Part file: okhttp3.MultipartBody.Part
    ): Response<MediaUploadResponse>

    // В интерфейсе ApiService
    @Headers("Accept-Encoding: identity")
    @GET("api/media/{fileId}")
    suspend fun downloadMedia(@Path("fileId") fileId: String): Response<okhttp3.ResponseBody>

    @Headers("Accept-Encoding: identity")
    @GET("api/apk/{version}")
    suspend fun downloadApk(@Path("version") version: String): Response<okhttp3.ResponseBody>

    @DELETE("api/media/{fileId}")
    suspend fun deleteMedia(@Path("fileId") fileId: String): Response<okhttp3.ResponseBody>

    @GET("api/updateapp")
    suspend fun getCheckUpdate(): Response<AppCheckVersion>

    @GET("myprofile")
    suspend fun getMyProfile(): Response<UserProfileDto>

    @PUT("myprofile")
    suspend fun updateMyProfile(@Body request: UpdateProfileRequest): Response<UserProfileDto>

    @POST("api/devices/register")
    suspend fun registerDevice(@Body request: DeviceTokenRequest): Response<Any>

    @DELETE("api/devices/{deviceId}")
    suspend fun unregisterDevice(@Path("deviceId") deviceId: String): Response<Any>
}

// === МОДЕЛИ ДАННЫХ ===
data class UserDto(
    val id: String,
    val username: String,
    val displayName: String?,
    val avatarUrl: String?
)

// DTO для отправки токена
data class DeviceTokenRequest(
    val userId: String,
    val deviceId: String,
    val pushToken: String,
    val platform: String = "android"
)

data class FriendRequestDto(
    val id: String,
    val sender: UserDto,
    val createdAt: String
)

data class RespondRequestDto(
    val requestId: String,
    val status: String // "ACCEPTED" или "REJECTED"
)

data class RegisterRequest(
    val username: String,
    val email: String,
    val password: String,
    val firstName: String,
    val lastName: String
)

data class LoginRequest(
    val username: String,
    val password: String
)

data class AuthResponse(
    val message: String,
    val token: String,
    val user: UserData
)

data class UserData(
    val id: String,
    val username: String,
    val email: String
)

data class KeysUploadRequest(
    val userId: String,
    val identityKey: String,
    val signedPreKeyId: Int,
    val signedPreKeyPublic: String,
    val signedPreKeySig: String,
    val oneTimeKeys: List<OneTimeKey>,
    val registrationId: Int  // ✅ Добавь это поле!
)

data class UserSearchResult(
    val id: String,
    val username: String,
    val displayName: String?,
    val avatarUrl: String?
)

data class ApiContact(
    val id: String,
    val username: String,
    val displayName: String?,
    val avatarUrl: String?,
    val status: String?
)

data class EncryptedPayloadDTO(
    val type: Int,
    val data: String
)

data class MessageDTO(
    val id: String?,
    val senderId: String,
    val payload: EncryptedPayloadDTO,  // ✅ Теперь объект, а не String
    val timestamp: Long,
    val delivered: Boolean,
    val isIncoming: Boolean
)

data class AddContactRequest(val userId: String, val contactUserId: String)
data class AddContactResponse(val success: Boolean, val message: String)

data class OneTimeKey(
    val keyId: Int,
    val publicKey: String
)

data class KeysUploadResponse(
    val success: Boolean,
    val message: String
)

data class KeysBundle(
    val identityKey: String,
    val signedPreKeyId: Int,
    val signedPreKeyPublic: String,
    val signedPreKeySig: String,
    val oneTimeKeys: List<OneTimeKey>,
    val registrationId: Int  // ✅ Добавь это поле!
)

data class UserStatusDTO(
    val userId: String,
    val isOnline: Boolean
)

// Data class для ответа сервера
data class MediaUploadResponse(
    val fileId: String,
    val size: Long,
    val uploadedAt: Long
)