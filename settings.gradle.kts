pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

if (providers.gradleProperty("useLocalKmpComponents").orNull.toBoolean()) {
    val localKmpComponents = file(providers.gradleProperty("kmpComponentsDir").orElse("../KMPComponents").get())
    require(localKmpComponents.resolve("settings.gradle.kts").isFile) {
        "useLocalKmpComponents requires a KMPComponents checkout at $localKmpComponents (override with -PkmpComponentsDir=<path>)"
    }
    includeBuild(localKmpComponents) {
        dependencySubstitution {
            substitute(module("io.github.maniramezan.kmpcomponents:components")).using(project(":components"))
        }
    }
}

rootProject.name = "kmp-code-editor"
include(":code-editor")
include(":sample")
