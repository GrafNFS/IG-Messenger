package ru.insolent.castoms.ig.network

import com.google.gson.JsonElement
import retrofit2.Response
import retrofit2.http.*
import ru.insolent.castoms.ig.model.AppCheckVersion

interface ApiService {

    // === Аутентификация ===
    @POST("api/register")
    suspend fun register(@Body request: RegisterRequest): Response<AuthResponse>

    @POST("api/login")
    suspend fun login(@Body request: LoginRequest): Response<AuthResponse>

    // === Профиль ===
    @GET("api/myprofile")
    suspend fun getMyProfile(): Response<ProfileResponse>

    @PUT("api/myprofile")
    suspend fun updateMyProfile(@Body request: UpdateProfileRequest): Response<ProfileResponse>

    // === Контакты и заявки ===
    @GET("api/users/search")
    suspend fun searchUsers(
        @Query("query") query: String,
        @Query("userId") currentUserId: String = ""
    ): Response<List<UserSearchResult>>

    @GET("api/contacts")
    suspend fun getMyContacts(@Query("userId") userId: String = ""): Response<List<ApiContact>>

    @POST("api/contacts")
    suspend fun addContact(@Body request: AddContactRequest): Response<AddContactResponse>

    @POST("api/friend-requests")
    suspend fun sendFriendRequest(@Body request: AddContactRequest): Response<Any>

    @GET("api/friend-requests/pending")
    suspend fun getPendingRequests(@Query("userId") userId: String): Response<List<FriendRequestDto>>

    @GET("api/friends-requests/count")
    suspend fun getPendingCountRequests(@Query("userId") userId: String): Response<Int>

    @POST("api/friend-requests/respond")
    suspend fun respondToFriendRequest(@Body request: RespondRequestDto): Response<Any>

    // === Ключи (мульти-девайс) ===
    @POST("api/keys/upload")
    suspend fun uploadKeys(@Body request: KeysUploadRequest): Response<KeysUploadResponse>

    /** Бандлы ВСЕХ устройств пользователя */
    @GET("api/keys/{userId}")
    suspend fun getKeysBundle(@Path("userId") userId: String): Response<DevicesBundleResponse>

    @GET("api/keys/{userId}/identity")
    suspend fun getIdentityKey(@Path("userId") userId: String): Response<IdentityKeyResponse>

    @GET("api/keys/count/remaining")
    suspend fun getRemainingPreKeys(@Query("deviceId") deviceId: String? = null): Response<RemainingKeysResponse>

    @POST("api/keys/one-time")
    suspend fun uploadOneTimeKeys(@Body request: OneTimeKeysUploadRequest): Response<KeysUploadResponse>

    // === Чаты и история ===
    /** Мои чаты: собеседники + группы (для глобального догона) */
    @GET("api/chats")
    suspend fun getMyChats(): Response<List<String>>

    @GET("api/chats/{chatId}/messages")
    suspend fun getChatHistory(
        @Path("chatId") chatId: String,
        @Query("userId") userId: String,
        @Query("since") since: Long? = null
    ): Response<List<MessageDTO>>

    @GET("api/users/{userId}/status")
    suspend fun getUserStatus(@Path("userId") userId: String): Response<UserStatusDTO>

    // === Группы ===
    @POST("api/groups")
    suspend fun createGroup(@Body request: CreateGroupRequest): Response<GroupResponse>

    @GET("api/groups")
    suspend fun getMyGroups(): Response<List<GroupResponse>>

    @GET("api/groups/{groupId}/members")
    suspend fun getGroupMembers(@Path("groupId") groupId: String): Response<List<GroupMemberDto>>

    @POST("api/groups/{groupId}/members")
    suspend fun addGroupMember(@Path("groupId") groupId: String, @Body body: Map<String, String>): Response<Any>

    @DELETE("api/groups/{groupId}/members/{userId}")
    suspend fun removeGroupMember(
        @Path("groupId") groupId: String,
        @Path("userId") userId: String
    ): Response<Any>

    // === Устройства (пуши) ===
    @POST("api/devices/register")
    suspend fun registerDevice(@Body request: DeviceTokenRequest): Response<Any>

    @DELETE("api/devices/{deviceId}")
    suspend fun unregisterDevice(@Path("deviceId") deviceId: String): Response<Any>

    // === Медиа ===
    @Multipart
    @POST("api/media/upload")
    suspend fun uploadMedia(
        @Query("fileId") fileId: String,
        @Part file: okhttp3.MultipartBody.Part,
        @Query("size") size: Long
    ): Response<MediaUploadResponse>

    @Headers("Accept-Encoding: identity")
    @GET("api/media/{fileId}")
    suspend fun downloadMedia(
        @Path("fileId") fileId: String,
        @Header("Range") range: String? = null
    ): Response<okhttp3.ResponseBody>

    @Headers("Accept-Encoding: identity")
    @GET("api/apk/{version}")
    suspend fun downloadApk(
        @Path("version") version: String,
        @Header("Range") range: String? = null
    ): Response<okhttp3.ResponseBody>

    @DELETE("api/media/{fileId}")
    suspend fun deleteMedia(@Path("fileId") fileId: String): Response<Any>

    // === Обновление приложения ===
    @GET("api/updateapp")
    suspend fun getCheckUpdate(): Response<AppCheckVersion>
}

// ==================== МОДЕЛИ ДАННЫХ ====================

data class UserDto(
    val id: String,
    val username: String,
    val displayName: String?,
    val avatarUrl: String?
)

data class ProfileResponse(
    val id: String,
    val username: String,
    val email: String?,
    val phone: String?,
    val displayName: String?,
    val avatarUrl: String?,
    val status: String?
)

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

data class RespondRequestDto(val requestId: String, val status: String)

data class RegisterRequest(
    val username: String,
    val email: String,
    val password: String,
    val firstName: String,
    val lastName: String
)

data class LoginRequest(val username: String, val password: String)

data class AuthResponse(
    val message: String,
    val token: String,
    val user: UserData
)

data class UserData(val id: String, val username: String, val email: String)

// === Ключи: мульти-девайс ===
data class OneTimeKey(val keyId: Int, val publicKey: String)

data class KeysUploadRequest(
    val deviceId: String,               // ← мульти-девайс
    val userId: String,
    val identityKey: String,
    val signedPreKeyId: Int,
    val signedPreKeyPublic: String,
    val signedPreKeySig: String,
    val oneTimeKeys: List<OneTimeKey>,
    val registrationId: Int
)

data class KeysUploadResponse(
    val success: Boolean,
    val message: String? = null,
    val remaining: Int? = null
)

data class RemainingKeysResponse(val remaining: Int, val deviceId: String? = null)

data class OneTimeKeysUploadRequest(
    val deviceId: String,               // ← мульти-девайс
    val oneTimeKeys: List<OneTimeKey>
)

data class IdentityKeyResponse(val identityKey: String? = null)

/** Бандл одного устройства */
data class DeviceKeysBundle(
    val deviceId: String,
    val identityKey: String,
    val signedPreKeyId: Int,
    val signedPreKeyPublic: String,
    val signedPreKeySig: String,
    val registrationId: Int,
    val oneTimeKeys: List<OneTimeKey> = emptyList()
)

/** Ответ GET /api/keys/{userId}: бандлы всех устройств */
data class DevicesBundleResponse(val devices: List<DeviceKeysBundle>)

/** Payload для конкретного устройства (личка) */
data class DevicePayload(val deviceId: Int, val type: Int, val data: String)

/** Payload для (участник × устройство) (группа) */
data class GroupDevicePayload(val userId: String, val deviceId: Int, val type: Int, val data: String)

// === Группы ===
data class CreateGroupRequest(val name: String, val memberIds: List<String>)

data class GroupResponse(
    val id: String,
    val name: String,
    val avatarUrl: String? = null,
    val createdById: String? = null,
    val myRole: String? = null,
    val members: List<GroupMemberDto> = emptyList()
)

data class GroupMemberDto(
    val userId: String,
    val role: String? = null,
    val username: String? = null,
    val displayName: String? = null,
    val avatarUrl: String? = null
)

// === Прочее ===
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

data class UserStatusDTO(val userId: String, val isOnline: Boolean)

/** payload — JsonElement: сервер шлёт как {type,data}, так и контейнеры {payloads:[...]} */
data class MessageDTO(
    val id: String?,
    val senderId: String,
    val payload: JsonElement,
    val timestamp: Long,
    val delivered: Boolean
)

data class AddContactRequest(val userId: String, val contactUserId: String)
data class AddContactResponse(val success: Boolean, val message: String? = null)

data class MediaUploadResponse(
    val fileId: String,
    val size: Long,
    val uploadedAt: Long
)
