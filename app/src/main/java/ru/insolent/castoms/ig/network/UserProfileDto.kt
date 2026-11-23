package ru.insolent.castoms.ig.network

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

@Keep
data class UserProfileDto(
    @SerializedName("id")
    val id: String,
    @SerializedName("username")
    val username: String,
    @SerializedName("email")
    val email: String? = null,
    @SerializedName("phone")
    val phone: String? = null,
    @SerializedName("displayName")
    val displayName: String? = null,
    @SerializedName("avatarUrl")
    val avatarUrl: String? = null,
    @SerializedName("status")
    val status: String = "ACTIVE",
    @SerializedName("createdAt")
    val createdAt: String? = null,
    @SerializedName("updatedAt")
    val updatedAt: String? = null
)

// Для обновления профиля (отправляем на сервер)
@Keep
data class UpdateProfileRequest(
    @SerializedName("displayName")
    val displayName: String?,
    @SerializedName("phone")
    val phone: String?,
    @SerializedName("avatarUrl")
    val avatarUrl: String?
)