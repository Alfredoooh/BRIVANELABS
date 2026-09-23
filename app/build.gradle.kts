// app/build.gradle.kts
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystoreProperties = Properties()
val localKeystorePropertiesFile = rootProject.file("keystore/key.properties")
if (localKeystorePropertiesFile.exists()) {
    localKeystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
}

android {
    namespace = "com.brivanelabs.notes"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.brivanelabs.notes"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.3.0"
    }

    signingConfigs {
        create("release") {
            val ci = System.getenv("CI")?.equals("true", ignoreCase = true) == true
            if (ci && !System.getenv("CM_KEYSTORE_PATH").isNullOrBlank()) {
                storeFile = file(System.getenv("CM_KEYSTORE_PATH")!!)
                storePassword = System.getenv("CM_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("CM_KEY_ALIAS")
                keyPassword = System.getenv("CM_KEY_PASSWORD")
            } else if (localKeystorePropertiesFile.exists()) {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug { isMinifyEnabled = false }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.biometric:biometric:1.1.0")
}