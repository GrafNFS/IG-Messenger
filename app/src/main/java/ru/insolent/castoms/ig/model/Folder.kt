package com.yourapp.igmessenger.models

data class Folder(
    val id: String,
    val name: String,
    val chatIds: List<String>, // ID чатов, которые входят в папку
    val color: String = "#5B8CFF" // Цвет Chip'а
)