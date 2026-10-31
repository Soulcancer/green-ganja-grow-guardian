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
        // Tuya Smart App SDK (Liste laut Tuya-Doku „Fast Integration“)
        maven("https://maven-other.tuya.com/repository/maven-releases/")
        maven("https://maven-other.tuya.com/repository/maven-commercial-releases/")
        maven("https://jitpack.io")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://developer.huawei.com/repo/")
        maven("https://central.sonatype.com/repository/maven-snapshots/")
    }
}

rootProject.name = "GreenGanjaGrowGuardian"
include(":app")
