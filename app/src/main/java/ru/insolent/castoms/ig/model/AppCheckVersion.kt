package ru.insolent.castoms.ig.model

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

@Keep
data class AppCheckVersion(
    @SerializedName("latestVersion")
    val latestVersion:String,
    @SerializedName("changeLog")
    val changeLog:String,
    @SerializedName("fileSize")
    val fileSize: Long,
    @SerializedName("mandatoryUpdate")
    val mandatoryUpdate: Boolean? = false
)