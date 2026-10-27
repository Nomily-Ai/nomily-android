import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Hard constraint: `:core` is pure Kotlin, with zero `android.*` dependencies and zero third-party dependencies.
// Once logic becomes entangled with platform APIs, it can only be tested via emulators, causing the deterministic verification loop for the entire system to fail.
dependencies {
    testImplementation(kotlin("test"))
}

// Align with :app's compileOptions (Java 11), so that :app can directly depend on this module in the future.
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
