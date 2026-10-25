pluginManagement {
    repositories {
        maven { url = uri(System.getenv("PEYGIR_GOOGLE_MAVEN") ?: "https://dl.google.com/dl/android/maven2/") }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri(System.getenv("PEYGIR_GOOGLE_MAVEN") ?: "https://dl.google.com/dl/android/maven2/") }
        mavenCentral()
    }
}
rootProject.name = "Peygir"
include(":app")
