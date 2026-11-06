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
        // Tesseract4Android (offline OCR that reads Cyrillic)
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "csearchrevamped"
include(":app")
