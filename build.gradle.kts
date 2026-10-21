plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.android.multiplatform.library) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.maven.publish) apply false
}

allprojects {
    group = "com.arjangconsulting.codeeditor"
    version = providers.gradleProperty("VERSION_NAME").orElse("0.0.0-SNAPSHOT").get()
}
