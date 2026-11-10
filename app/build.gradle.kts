import org.gradle.kotlin.dsl.implementation
import org.gradle.kotlin.dsl.invoke

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android) // Возвращаем строку обратно
    alias(libs.plugins.google.ksp)
    id("com.google.gms.google-services")
}

android {
    namespace = "ru.insolent.castoms.ig"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "ru.insolent.castoms.ig"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true        // Включает R8 для удаления неиспользуемого кода
            isShrinkResources = true    // Включает удаление неиспользуемых ресурсов
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    buildFeatures {
        viewBinding = true // <-- Добавляем эту строку
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    ksp {
        arg("ksp.allow.missed.references", "true")
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.chrisbanes.photoview)
    implementation(libs.com.github.lincollincol.amplituda)
    implementation(libs.firebase.bom)
    implementation(libs.firebase.messaging.ktx)
    implementation(libs.jsoup)
    implementation(libs.lightcompressor)
    implementation(libs.massoudss.waveformseekbar)
    implementation(libs.room.ktx)
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    implementation(libs.androidx.security.crypto)
    implementation(libs.circleimageview)
    implementation(libs.converter.gson)
    implementation(libs.libsignal.android)
    implementation(libs.logging.interceptor)
    implementation(libs.material)
    implementation(libs.picasso)
    implementation(libs.retrofit)
    implementation(libs.socket.io.client)
    coreLibraryDesugaring(libs.tools.desugar.jdk.libs)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}