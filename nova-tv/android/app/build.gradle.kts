// Module build file: structure after Drjacky/MVVMTemplate (MIT), dependencies
// chosen from nova-tv's own imports rather than copied wholesale.
//
// The rule applied here: every dependency below is there because some .kt file
// under src/ imports it. Anything nova-tv does not import is absent, which is
// how a build file stays honest instead of accumulating cargo.
import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.nova.tv"

    /**
     * Signing.
     *
     * An unsigned release APK cannot be installed, so a build that produces one
     * is not finished. A keystore is never committed -- a private key in a public
     * repository is a permanent leak, and replacing it forces every install to be
     * uninstalled first -- so the key is supplied by the environment, or derived
     * on first use.
     *
     * This is a reproducible development key, not a publishable one. It has a
     * well-known password by design, which is exactly why
     * `docs/BUILD-STATUS.md` lists "replace this before publishing" as an
     * outstanding item.
     *
     * Generating it lazily matters: a signing config is evaluated at
     * *configuration* time, so generating eagerly would create a keystore on
     * every Gradle sync and on every IDE import, for builds that never sign
     * anything.
     */
    val keystoreDir = layout.projectDirectory.dir("../keystore").asFile
    val keystoreFile = File(keystoreDir, "nova-release.jks")
    val keystoreProps = Properties().apply {
        val f = File(keystoreDir, "keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    val storePass = System.getenv("NOVA_KEYSTORE_PASSWORD")
        ?: keystoreProps.getProperty("storePassword")
        ?: "nova-development-key"
    val keyAlias = System.getenv("NOVA_KEY_ALIAS")
        ?: keystoreProps.getProperty("keyAlias")
        ?: "nova"

    signingConfigs {
        create("release") {
            storeFile = keystoreFile
            storePassword = storePass
            this.keyAlias = keyAlias
            keyPassword = storePass
        }
    }

    // Runs only when a release artefact is actually requested.
    tasks.matching { it.name.startsWith("assembleRelease") || it.name.startsWith("bundleRelease") }
        .configureEach {
            doFirst {
                if (!keystoreFile.exists()) {
                    keystoreDir.mkdirs()
                    val keytool = File(System.getProperty("java.home"), "bin/keytool")
                    providers.exec {
                        commandLine(
                            keytool.absolutePath, "-genkeypair",
                            "-keystore", keystoreFile.absolutePath,
                            "-storepass", storePass,
                            "-keypass", storePass,
                            "-alias", keyAlias,
                            "-keyalg", "RSA", "-keysize", "2048",
                            "-validity", "10000",
                            "-dname", "CN=Nova TV, OU=Development, O=nova-tv, C=ZA",
                        )
                    }.result.get()
                    logger.lifecycle("nova: generated development keystore at $keystoreFile")
                }
            }
        }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = true
        }
        release {
            signingConfig = signingConfigs.getByName("release")
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

// R8 full mode is the default on AGP 8+. The release build has never been
// compiled here, and shrinking is where a schema-based app breaks first: a
// renamed serializer is a runtime crash, not a compile error. That is why
// proguard-rules.pro keeps the serialization plumbing explicitly.

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
