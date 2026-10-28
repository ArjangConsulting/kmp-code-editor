// Desktop sample app showing every editor feature. Not published; run with `./gradlew :sample:run`.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvm()

    sourceSets {
        jvmMain.dependencies {
            implementation(project(":code-editor"))
            implementation(libs.kmp.components)
            implementation(libs.compose.material3)
            implementation(compose.desktop.currentOs)
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.arjangconsulting.codeeditor.sample.MainKt"
        // -PcomposeLayersType=WINDOW draws Compose popups in their own windows, which keeps dialogs
        // and menus above the RSyntaxTextArea engine (see the README's engine section).
        providers.gradleProperty("composeLayersType").orNull?.let { jvmArgs("-Dcompose.layers.type=$it") }
    }
}
