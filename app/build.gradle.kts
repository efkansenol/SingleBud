plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.singlebud"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.singlebud"
        minSdk = 24
        versionCode = 2
        versionName = "0.2.0"
    }

    // Fixed key committed to the repo so every CI build can be installed
    // over the previous one. It is a debug key, not a secret.
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    // standard: installs normally; changes the mono setting through Shizuku or root.
    // legacy:   targets API 22 so Android lets it write the setting directly;
    //           on Android 14+ it must be installed with
    //           adb install --bypass-low-target-sdk-block
    flavorDimensions += "target"
    productFlavors {
        create("standard") {
            dimension = "target"
            targetSdk = 34
        }
        create("legacy") {
            dimension = "target"
            targetSdk = 22
            versionNameSuffix = "-legacy"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
