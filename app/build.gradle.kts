plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.questfilemanager.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.questfilemanager.app"
        minSdk = 29
        targetSdk = 32
        versionCode = 1
        versionName = "0.1.0"
    }
}

dependencies {
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("com.android.tools.build:apksig:8.7.3")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
}
