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
        // Tuya Smart App SDK
        maven("https://maven-other.tuya.com/repository/maven-releases/")
        maven("https://maven-other.tuya.com/repository/maven-commercial-releases/")
    }
}

rootProject.name = "GreenGanjaGrowGuardian"
include(":app")
