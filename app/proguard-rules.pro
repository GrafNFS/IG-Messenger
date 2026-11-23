# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

-dontwarn linc.com.amplituda.Amplituda
-dontwarn linc.com.amplituda.AmplitudaProcessingOutput
-dontwarn linc.com.amplituda.AmplitudaResult
-dontwarn linc.com.amplituda.callback.AmplitudaErrorListener
# --- Kotlin / общее ---
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses

# --- Gson: критично ---
-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.**

# ВСЕ модели, которые Gson сериализует/десериализует:
# DTO сети (AuthResponse, KeysUploadRequest, KeysBundle, GroupResponse...) +
# крипто-обёртки (EncryptedPayload/MessageContent/MediaAttachment ходят
# в JSON внутри Room и сокетов) + Room-сущности.
-keep class ru.insolent.castoms.ig.network.** { *; }
-keep class ru.insolent.castoms.ig.crypto.** { *; }
-keep class ru.insolent.castoms.ig.database.** { *; }

# --- Retrofit ---
-dontwarn retrofit2.**
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}

# --- OkHttp / Okio ---
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**

# --- Socket.IO ---
-keep class io.socket.** { *; }
-dontwarn io.socket.**

# --- libsignal (Signal Protocol) ---
-keep class org.signal.** { *; }
-dontwarn org.signal.**

# --- Прочие библиотеки (безопасно) ---
-dontwarn com.google.android.exoplayer2.**
-dontwarn com.squareup.picasso.**
-dontwarn com.masoudss.**
-dontwarn com.linc.amplituda.**