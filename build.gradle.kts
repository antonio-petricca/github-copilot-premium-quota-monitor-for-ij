plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.7.1"
}

group = "com.github.intellij.plugins.github_copilot_quota_monitor"
version = "1.0-SNAPSHOT"

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // Build against Community edition — the plugin uses only platform APIs
        // and therefore runs on all IntelliJ-based IDEs (Community, Ultimate,
        // PyCharm, WebStorm, GoLand, …).
        intellijIdeaCommunity("2025.2.4")
        jetbrainsRuntime()
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "252"
            // untilBuild is intentionally left unset to allow all future builds
        }

        changeNotes = """
            Initial version
        """.trimIndent()
    }
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }

    // instrumentCode uses JBR-specific "Packages" directory not present in standard JDKs
    named("instrumentCode") { enabled = false }
    named("instrumentTestCode") { enabled = false }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}
