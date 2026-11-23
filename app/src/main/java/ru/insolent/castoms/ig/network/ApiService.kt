package ru.insolent.castoms.ig.network

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName
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
    suspend fun getMyContacts(): Response<List<ApiContact>>

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


    @GET("api/chats/{chatId}/messages")
    suspend fun getChatHistory(
        @Path("chatId") chatId: String,
        @Query("userId") userId: String,
        @Query("since") since: Long? = null   // ← НОВОЕ: timestamp в мс
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

    @GET("api/myprofile")
    suspend fun getMyProfile(): Response<UserProfileDto>

    @PUT("api/myprofile")
    suspend fun updateMyProfile(@Body request: UpdateProfileRequest): Response<UserProfileDto>

    @POST("api/devices/register")
    suspend fun registerDevice(@Body request: DeviceTokenRequest): Response<Any>

    @DELETE("api/devices/{deviceId}")
    suspend fun unregisterDevice(@Path("deviceId") deviceId: String): Response<Any>

    @GET("api/keys/count/remaining")
    suspend fun getRemainingPreKeys(): Response<RemainingKeysResponse>

    @POST("api/keys/one-time")
    suspend fun uploadOneTimeKeys(@Body request: OneTimeKeysUploadRequest): Response<Any>

    @GET("api/chats")
    suspend fun getMyChats(): Response<List<String>>

    /** Группы создание информация  **/
    @POST("api/groups")
    suspend fun createGroup(@Body request: CreateGroupRequest): Response<GroupResponse>

    @GET("api/groups")
    suspend fun getMyGroups(): Response<List<GroupResponse>>

    @GET("api/groups/{groupId}/members")
    suspend fun getGroupMembers(@Path("groupId") groupId: String): Response<List<GroupMemberDto>>

    @POST("api/groups/{groupId}/members")
    suspend fun addGroupMember(
        @Path("groupId") groupId: String,
        @Body body: Map<String, String>
    ): Response<Any>

    @DELETE("api/groups/{groupId}/members/{userId}")
    suspend fun removeGroupMember(
        @Path("groupId") groupId: String,
        @Path("userId") userId: String
    ): Response<Any>
    /** ------ **/
}

// === МОДЕЛИ ДАННЫХ ===
@Keep
data class UserDto(
    @SerializedName("id")
    val id: String,
    @SerializedName("username")
    val username: String,
    @SerializedName("displayName")
    val displayName: String?,
    @SerializedName("avatarUrl")
    val avatarUrl: String?
)

// DTO для отправки токена
data class DeviceTokenRequest(
    val userId: String,
    val deviceId: String,
    val pushToken: String,
    val platform: String = "android"
)

@Keep
data class FriendRequestDto(
    @SerializedName("id")
    val id: String,
    @SerializedName("sender")
    val sender: UserDto,
    @SerializedName("createdAt")
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

@Keep
data class AuthResponse(
    @SerializedName("message")
    val message: String,
    @SerializedName("token")
    val token: String,
    @SerializedName("user")
    val user: UserData
)

@Keep
data class UserData(
    @SerializedName("id")
    val id: String,
    @SerializedName("username")
    val username: String,
    @SerializedName("email")
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

@Keep
data class UserSearchResult(
    @SerializedName("id")
    val id: String,
    @SerializedName("username")
    val username: String,
    @SerializedName("displayName")
    val displayName: String?,
    @SerializedName("avatarUrl")
    val avatarUrl: String?
)

@Keep
data class ApiContact(
    @SerializedName("id")
    val id: String,
    @SerializedName("username")
    val username: String,
    @SerializedName("displayName")
    val displayName: String?,
    @SerializedName("avatarUrl")
    val avatarUrl: String?,
    @SerializedName("status")
    val status: String?
)

@Keep
data class EncryptedPayloadDTO(
    @SerializedName("type")
    val type: Int,
    @SerializedName("data")
    val data: String
)

@Keep
data class MessageDTO(
    @SerializedName("id")
    val id: String?,
    @SerializedName("senderId")
    val senderId: String,
    @SerializedName("payload")
    val payload: EncryptedPayloadDTO,  // ✅ Теперь объект, а не String
    @SerializedName("timestamp")
    val timestamp: Long,
    @SerializedName("delivered")
    val delivered: Boolean,
    @SerializedName("isIncoming")
    val isIncoming: Boolean
)

data class AddContactRequest(
    val userId: String,
    val contactUserId: String
)

@Keep
data class AddContactResponse(
    @SerializedName("success")
    val success: Boolean,
    @SerializedName("message")
    val message: String
)

@Keep
data class OneTimeKey(
    @SerializedName("keyId")
    val keyId: Int,
    @SerializedName("publicKey")
    val publicKey: String
)

@Keep
data class KeysUploadResponse(
    @SerializedName("success")
    val success: Boolean,
    @SerializedName("message")
    val message: String? = null,
    @SerializedName("remaining")
    val remaining: Int? = null
)

@Keep
data class KeysBundle(
    @SerializedName("identityKey")
    val identityKey: String,
    @SerializedName("signedPreKeyId")
    val signedPreKeyId: Int,
    @SerializedName("signedPreKeyPublic")
    val signedPreKeyPublic: String,
    @SerializedName("signedPreKeySig")
    val signedPreKeySig: String,
    @SerializedName("oneTimeKeys")
    val oneTimeKeys: List<OneTimeKey>,
    @SerializedName("registrationId")
    val registrationId: Int  // ✅ Добавь это поле!
)

@Keep
data class UserStatusDTO(
    @SerializedName("userId")
    val userId: String,
    @SerializedName("isOnline")
    val isOnline: Boolean
)

// Data class для ответа сервера
@Keep
data class MediaUploadResponse(
    @SerializedName("fileId")
    val fileId: String,
    @SerializedName("size")
    val size: Long,
    @SerializedName("uploadedAt")
    val uploadedAt: Long
)

@Keep
data class CreateGroupRequest(
    @SerializedName("name")
    val name: String,
    @SerializedName("memberIds")
    val memberIds: List<String>
)

@Keep
data class GroupResponse(
    @SerializedName("id")
    val id: String,
    @SerializedName("name")
    val name: String,
    @SerializedName("avatarUrl")
    val avatarUrl: String? = null,
    @SerializedName("createdById")
    val createdById: String? = null,
    @SerializedName("myRole")
    val myRole: String? = null,
    @SerializedName("members")
    val members: List<GroupMemberDto> = emptyList()
)

@Keep
data class GroupMemberDto(
    @SerializedName("userId")
    val userId: String,
    @SerializedName("role")
    val role: String? = null,
    @SerializedName("username")
    val username: String? = null,
    @SerializedName("displayName")
    val displayName: String? = null,
    @SerializedName("avatarUrl")
    val avatarUrl: String? = null
)

/** Entry зашифрованного сообщения для конкретного участника группы */
@Keep
data class GroupPayload(
    @SerializedName("userId")
    val userId: String,
    @SerializedName("type")
    val type: Int,
    @SerializedName("data")
    val data: String
)

@Keep
data class RemainingKeysResponse(
    @SerializedName("remaining")
    val remaining: Int
)

@Keep
data class OneTimeKeysUploadRequest(
    @SerializedName("oneTimeKeys")
    val oneTimeKeys: List<OneTimeKey>
)