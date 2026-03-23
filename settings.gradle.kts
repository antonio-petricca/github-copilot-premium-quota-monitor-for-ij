pluginManagement {
    val kotlinVersion: String by settings
    val intellijPlatformGradlePluginVersion: String by settings

    repositories {
        maven("https://cache-redirector.jetbrains.com/plugins.gradle.org/m2")
        gradlePluginPortal()
    }

    plugins {
        kotlin("jvm") version kotlinVersion
        id("org.jetbrains.intellij.platform") version intellijPlatformGradlePluginVersion
    }
}

rootProject.name = "github-copilot-premium-quota-monitor-for-ij"