plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

import java.util.Properties

// 签名配置 (从本地 keystore.properties 读取, 不提交仓库)
val keystoreProps = Properties()
val ksFile = rootProject.file("keystore.properties")
if (ksFile.exists()) {
    keystoreProps.load(ksFile.inputStream())
}

android {
    namespace = "com.remotecontrol.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.remotecontrol.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.2"
    }

    signingConfigs {
        create("release") {
            if (ksFile.exists()) {
                storeFile = rootProject.file("remote-control.keystore")
                storePassword = (keystoreProps["storePassword"] ?: "").toString()
                keyAlias = (keystoreProps["keyAlias"] ?: "").toString()
                keyPassword = (keystoreProps["keyPassword"] ?: "").toString()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-ktx:1.9.1")
    // WebSocket: OkHttp 提供精简可靠的 WS 客户端
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // kotlinx coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    // JSON
    implementation("org.json:json:20240303")
}
