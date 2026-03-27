import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java")
    kotlin("jvm")
    id("org.jetbrains.intellij.platform")
}

// Blocco pluginVerification rimosso temporaneamente per evitare errori di compilazione

// ── Properties from gradle.properties ────────────────────────────────────────
val pluginGroup: String by project
val pluginVersion: String by project
val platformVersion: String by project
val pluginSinceBuild: String by project
val javaVersion: String by project

group   = pluginGroup
version = pluginVersion

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
        intellijIdeaCommunity(platformVersion)
        jetbrainsRuntime()
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = pluginSinceBuild
            // untilBuild is intentionally left unset to allow all future builds
        }
        // name, description e changeNotes sono ora gestiti in plugin.xml
    }
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = javaVersion
        targetCompatibility = javaVersion
    }

    // instrumentCode uses JBR-specific "Packages" directory not present in standard JDKs
    named("instrumentCode")     { enabled = false }
    named("instrumentTestCode") { enabled = false }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(javaVersion))
    }
}
