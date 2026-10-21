import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The published library. Depends only on Compose, Material 3, coroutines, and (desktop) RSyntaxTextArea.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.multiplatform.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.maven.publish)
}

kotlin {
    explicitApi()

    android {
        namespace = "com.arjangconsulting.codeeditor"
        compileSdk = 37
        minSdk = 26
        compilerOptions.jvmTarget = JvmTarget.JVM_17
        withHostTest {}
    }
    jvm {
        compilerOptions.jvmTarget = JvmTarget.JVM_17
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(libs.compose.runtime)
            api(libs.compose.foundation)
            api(libs.compose.ui)
            api(libs.compose.material3)
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmMain.dependencies {
            implementation(libs.rsyntaxtextarea)
            implementation(libs.rsta.autocomplete)
            implementation(libs.kotlinx.coroutines.swing)
        }
        jvmTest.dependencies {
            // Compose UI tests run on desktop only: they need a Skiko renderer, which Android host
            // tests lack.
            implementation(compose.desktop.currentOs)
            implementation(libs.compose.ui.test)
        }
    }
}

tasks.withType<Test>().configureEach {
    // RSyntaxTextArea panel tests construct Swing components without a display.
    systemProperty("java.awt.headless", "true")
}

mavenPublishing {
    publishToMavenCentral()
    if (providers.gradleProperty("RELEASE_SIGNING_ENABLED").orElse("true").get().toBoolean()) {
        signAllPublications()
    }
    pom {
        name = "KMP Code Editor"
        description = "Compose Multiplatform code editor with pluggable, LSP-shaped language intelligence"
        inceptionYear = "2026"
        url = "https://github.com/ArjangConsulting/kmp-code-editor"
        licenses {
            license {
                name = "MIT License"
                url = "https://opensource.org/licenses/MIT"
            }
        }
        developers {
            developer {
                id = "maniramezan"
                name = "Mani Ramezan"
            }
        }
        scm {
            url = "https://github.com/ArjangConsulting/kmp-code-editor"
            connection = "scm:git:git://github.com/ArjangConsulting/kmp-code-editor.git"
            developerConnection = "scm:git:ssh://git@github.com/ArjangConsulting/kmp-code-editor.git"
        }
    }
}
