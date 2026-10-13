plugins {
    id("com.android.application")
}

android {
    namespace = "com.savage.soulbrowser"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.savage.soulbrowser"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "3.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        // Stub-returning defaults would let a unit test silently pass while touching Android
        // APIs that do not exist on the JVM; every test must fail loudly instead.
        unitTests.isReturnDefaultValues = false
    }

    // Release signing is only configured when real credentials are supplied by the operator
    // (shell/android_project/keystore.properties, which is git-ignored). No key material is
    // ever committed to this repository. Without it, `assembleRelease` produces an unsigned
    // APK, which is the honest result.
    val keystorePropertiesFile = rootProject.file("keystore.properties")
    if (keystorePropertiesFile.exists()) {
        val keystoreProperties = java.util.Properties().apply {
            keystorePropertiesFile.inputStream().use { load(it) }
        }
        signingConfigs.create("release") {
            storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
            storePassword = keystoreProperties.getProperty("storePassword")
            keyAlias = keystoreProperties.getProperty("keyAlias")
            keyPassword = keystoreProperties.getProperty("keyPassword")
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        getByName("debug") {
            isDebuggable = true
            isMinifyEnabled = false
            // v1 signing is kept on purpose: the repository's own APK verifier
            // (factory-3/tools/android_vm_verifier.py) validates META-INF/CERT.RSA, and v1
            // signatures are the compatibility path for API 26-27 devices.
            signingConfig = signingConfigs.getByName("debug").apply {
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = false
    }

    packaging {
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }
}

// The Java sources contain non-ASCII characters (typographic dashes and accented letters in
// javadoc and test data). Pin the encoding so that a developer or CI machine with a non-UTF-8
// default charset compiles exactly what the repository contains.
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.12.0")

    testImplementation("junit:junit:4.13.2")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
