package ru.insolent.castoms.ig.network

data class UserProfileDto(
    val id: String,
    val username: String,
    val email: String? = null,
    val phone: String? = null,
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val status: String = "ACTIVE",
    val createdAt: String? = null,
    val updatedAt: String? = null
)

// Для обновления профиля (отправляем на сервер)
data class UpdateProfileRequest(
    val displayName: String?,
    val phone: String?,
    val avatarUrl: String?
)