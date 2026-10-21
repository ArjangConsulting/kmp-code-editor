import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.arjangconsulting.codeeditor.verification"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.arjangconsulting.codeeditor.verification"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { compilerOptions.jvmTarget = JvmTarget.JVM_17 }

dependencies {
    implementation(project(":"))
    implementation(libs.compose.ui)
    implementation(libs.androidx.activity.compose)
}
