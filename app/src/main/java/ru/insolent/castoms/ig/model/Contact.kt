package ru.insolent.castoms.ig.models

import ru.insolent.castoms.ig.R

data class Contact(
    val id: String,
    val name: String,
    val status: String = "", // "Online", "Last seen...", или номер телефона
    val avatarResId: Int = R.drawable.ic_default_avatar,
    val isOnline: Boolean = false,
    val avatarUrl: String? = null,
    val isMyContact: Boolean = true,
    val isRequested: Boolean = false
)