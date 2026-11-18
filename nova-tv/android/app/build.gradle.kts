// Module build file: structure after Drjacky/MVVMTemplate (MIT), dependencies
// chosen from nova-tv's own imports rather than copied wholesale.
//
// The rule applied here: every dependency below is there because some .kt file
// under src/ imports it. Anything nova-tv does not import is absent, which is
// how a build file stays honest instead of accumulating cargo.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.nova.tv"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "dev.nova.tv"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// JVM target is set through compileOptions above; on AGP 8.5 the Kotlin compiler
// follows javaTarget automatically. An explicit `kotlin { compilerOptions {} }`
// block is a newer-AGP idiom and is deliberately not used.

dependencies {
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // Compose — imported by ui/, screens/, player/
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.animation)
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    // Android TV — the form factor this app targets
    implementation(libs.tv.material)
    implementation(libs.tv.foundation)

    // Activity + lifecycle (collectAsStateWithLifecycle, viewModelScope)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)

    // Media3 — PlayerState and NovaPlayer import common + exoplayer
    implementation(libs.media3.common)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)

    // Networking + JSON — HttpClient.kt uses OkHttp, Models.kt uses serialization
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // Unit tests — PlayerStateTest and UrlGuardTest run on the JVM
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
