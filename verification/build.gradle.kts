import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.multiplatform.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    android {
        namespace = "com.arjangconsulting.codeeditor.verification.shared"
        compileSdk = 37
        minSdk = 26
        compilerOptions.jvmTarget = JvmTarget.JVM_17
    }
    iosSimulatorArm64 {
        binaries.framework {
            baseName = "EditorVerificationKit"
            isStatic = true
        }
    }
    sourceSets.commonMain.dependencies {
        implementation("com.arjangconsulting.codeeditor:code-editor:0.0.0-SNAPSHOT")
    }
}
