import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
}

/**
 * Release signing. The build reads an optional `keystore.properties`
 * (`storeFile` / `storePassword` / `keyPassword` / `keyAlias`); the keystore and
 * its passphrases live outside the repository and are never committed.
 *
 * Lookup order: `-Pnomily.keystoreProperties=<path>` -> environment variable
 * `NOMILY_KEYSTORE_PROPERTIES` -> `../docs/android/keystore.properties`.
 *
 * When no properties file is found, no release signing config is registered, so
 * `assembleDebug`, unit tests and the screenshot baseline still build. Contributors
 * and CI must not be blocked by a publishing-only setting.
 *
 * Under Play App Signing the app signing key is generated and held by Google; this
 * key only proves that an AAB was uploaded by us. A lost upload key can be reset in
 * the Play Console, but a leaked one must be reset immediately.
 */
val keystoreProps: Properties? = run {
    val path = (findProperty("nomily.keystoreProperties") as String?)
        ?: System.getenv("NOMILY_KEYSTORE_PROPERTIES")
        ?: rootProject.file("../docs/android/keystore.properties").path
    val f = file(path)
    if (!f.isFile) return@run null
    Properties().apply { f.inputStream().use { load(it) } }
}

android {
    namespace = "com.nomily.app"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.nomily.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 3
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystoreProps != null) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                // Keep v1 as well: devices with minSdk 24 recognize APK Signature Scheme v1/v2.
                // The uploaded artifact is an AAB; Play will re‑sign it with its own key for the device, which only affects the local build output.
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    testOptions {
        unitTests {
            // Roborazzi renders the real UI on the JVM, so it must be able to read res/ (strings, colors, drawables).
            isIncludeAndroidResources = true
            all { it.systemProperty("robolectric.graphicsMode", "NATIVE") }
        }
    }
    buildFeatures {
        compose = true
        // The settings page's version line should read versionName/versionCode,
        // AGP 8 does not generate BuildConfig by default; it must be explicitly enabled.
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.okhttp)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    testImplementation(libs.junit)
    // Screenshot baseline (JVM side, no emulator needed). Store baseline image in the database; during regression, verify to generate a diff image.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    implementation(libs.argon2kt)          // Argon2 primitive for the shipping path (NDK)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}