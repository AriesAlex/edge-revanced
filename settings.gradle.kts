rootProject.name = "edge-revanced"

pluginManagement {
    includeBuild("local/revanced-patches-gradle-plugin")

    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        flatDir {
            dirs(rootDir.resolve("local"))
        }
    }
}

plugins {
    id("app.revanced.patches") version "1.0.0-dev.11"
}
