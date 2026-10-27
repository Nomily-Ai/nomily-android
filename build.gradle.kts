// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    // Define the version here without applying it; :core will alias it once more.
    // (AGP already brings the Kotlin plugin onto the classpath; specifying a separate version in submodules will cause
    //  "already on the classpath with an unknown version".)
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.roborazzi) apply false
}
