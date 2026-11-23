import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The one place to bump for a release; build.sh reads it too
val appVersion = "1.25"

android {
    namespace = "io.github.altoxheater"
    compileSdk = 36
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "io.github.altoxheater"
        minSdk = 26
        targetSdk = 36
        // major * 100 + minor: 1.24 -> 124. Must only grow (v1.24 went out as 25); minor stays under 100
        versionCode = appVersion.split(".").let { it[0].toInt() * 100 + it[1].toInt() }
        versionName = appVersion
    }

    // The key and its password are not in git, see build.sh
    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("ANDROID_KEYSTORE") ?: "../release.jks")
            storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
            keyAlias = "altox-heater"
            keyPassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("release")
        }
    }

    // Robolectric tests load the app's resources
    testOptions { unitTests { isIncludeAndroidResources = true } }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
}
