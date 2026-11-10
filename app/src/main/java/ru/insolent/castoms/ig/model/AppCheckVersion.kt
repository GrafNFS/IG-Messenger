package ru.insolent.castoms.ig.model

data class AppCheckVersion(
    val latestVersion:String,
    val changeLog:String,
    val fileSize: Long,
    val mandatoryUpdate: Boolean? = false
)