plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseStorePassword: String =
    (findProperty("RELEASE_STORE_PASSWORD") as String?) ?: System.getenv("RELEASE_STORE_PASSWORD") ?: ""
val releaseKeyPassword: String =
    (findProperty("RELEASE_KEY_PASSWORD") as String?) ?: System.getenv("RELEASE_KEY_PASSWORD") ?: ""
val releaseKeyAlias: String =
    (findProperty("RELEASE_KEY_ALIAS") as String?) ?: System.getenv("RELEASE_KEY_ALIAS") ?: "mifan_release"

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
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mifan.kt"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("release") {
            storeFile = file("release-key.jks")
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
        }
    }

    buildTypes {
        release {
            // Shrink/obfuscate code and remove unused Android resources to reduce APK size.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
}