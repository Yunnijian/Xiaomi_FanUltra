plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseStorePassword: String =
    (findProperty("RELEASE_STORE_PASSWORD") as String?) ?: System.getenv("RELEASE_STORE_PASSWORD") ?: ""
val releaseKeyPassword: String =
    (findProperty("RELEASE_KEY_PASSWORD") as String?) ?: System.getenv("RELEASE_KEY_PASSWORD") ?: ""
val releaseKeyAlias: String =
    (findProperty("RELEASE_KEY_ALIAS") as String?) ?: System.getenv("RELEASE_KEY_ALIAS") ?: "mifan_release"
val releaseStoreFilePath: String =
    (findProperty("RELEASE_STORE_FILE") as String?) ?: System.getenv("RELEASE_STORE_FILE") ?: "release-key.jks"
val releaseStoreFile = file(releaseStoreFilePath)
val hasReleaseSigningCredentials = releaseStoreFile.isFile &&
    releaseStorePassword.isNotBlank() &&
    releaseKeyPassword.isNotBlank() &&
    releaseKeyAlias.isNotBlank()

if (!hasReleaseSigningCredentials) {
    logger.lifecycle(
        "Release signing credentials are unavailable; using the debug keystore for this local " +
            "release build. Provide RELEASE_STORE_FILE, RELEASE_STORE_PASSWORD, " +
            "RELEASE_KEY_PASSWORD, and RELEASE_KEY_ALIAS for a distributable signature."
    )
}

android {
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    namespace = "com.mifan.kt"
    // Match KernelSU manager MIUIX 0.9.3 requirement.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.mifan.kt"
        // MIUIX 0.9.3 (blur) requires 33+; KernelSU manager targets modern devices similarly.
        minSdk = 33
        targetSdk = 34
        versionCode = 7
        versionName = "1.2.1"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    buildFeatures {
        compose = true
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigningCredentials) {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Keep local release verification usable when the ignored signing key is absent or
            // its credentials are not available. CI/distribution builds still use the release key.
            signingConfig = if (hasReleaseSigningCredentials) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("org.luckypray:dexkit:2.2.0")

    // Align with KernelSU manager (libs.versions.toml) where practical.
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // Exact MIUIX stack KernelSU manager uses.
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-icons-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-blur-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.3")

    implementation("me.zhanghai.android.appiconloader:appiconloader:1.5.0")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
}
