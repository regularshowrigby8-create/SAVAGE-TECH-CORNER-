plugins {
    id("com.android.application")
}

android {
    namespace = "com.savage.zeroplay.tv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.savage.zeroplay.tv"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.0.1"

    }

    // Xiaomi TV Box S (3rd Gen, MDZ-32-AA) ships a 32-bit Android 14
    // userspace. Produce one clearly named ARMv7 split APK instead of a
    // universal APK. AGP forbids combining this split with ndk.abiFilters.
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a")
            isUniversalApk = false
        }
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Sideload build: Android's debug certificate makes the APK
            // installable without committing a private release key to Git.
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
        }
    }

    lint {
        abortOnError = false
    }
}
