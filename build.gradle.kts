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
        intellijIdeaUltimate("2025.2.4")
        jetbrainsRuntime()
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)

        // Kotlin plugin (bundled in IntelliJ IDEA Ultimate)
        bundledPlugin("org.jetbrains.kotlin")

        // GitHub integration plugin (bundled in IntelliJ IDEA Ultimate).
        // Provides GHAccountManager used to retrieve the OAuth token
        // that the GitHub Copilot plugin has stored for the signed-in user.
        bundledPlugin("org.jetbrains.plugins.github")
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "252"
            // untilBuild is intentionally left unset to allow all future 252.x builds
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
