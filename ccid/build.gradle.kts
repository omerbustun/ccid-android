import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
}

group = "io.ustun"
version = "0.1.0"

android {
    namespace = "io.ustun.ccid"
    compileSdk = 37

    defaultConfig {
        // USB host mode is the only platform requirement, and API 21 carries
        // it. Later APIs are used behind version guards.
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget = JvmTarget.JVM_17 }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}
