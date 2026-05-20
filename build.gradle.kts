import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java")
    kotlin("jvm")
    id("org.jetbrains.intellij.platform")
}

// ── Properties from gradle.properties ────────────────────────────────────────
val pluginGroup: String by project
val pluginVersion: String by project
val platformType: String by project
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
        create(platformType, platformVersion)
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
        // name, description and changeNotes are managed in plugin.xml
    }

    pluginVerification {
        ides {
            // Verify against the same baseline IDE used to build the plugin.
            create(platformType, platformVersion)
        }
    }
}

tasks {
    processResources {
        filesMatching("plugin-version.properties") {
            expand("version" to pluginVersion)
        }
    }

    withType<JavaCompile> {
        sourceCompatibility = javaVersion
        targetCompatibility = javaVersion
    }

    // instrumentCode uses JBR-specific "Packages" directory not present in standard JDKs
    named("instrumentCode")     { enabled = false }
    named("instrumentTestCode") { enabled = false }

    // The verifyPlugin task spawns a child Java process that does NOT inherit
    // the Gradle daemon's system properties, so we must re-pass the Windows
    // certificate store flag explicitly to fix SSL handshake failures behind
    // a corporate HTTPS inspection proxy.
    named<JavaExec>("verifyPlugin") {
        jvmArgs(
            "-Djavax.net.ssl.trustStoreType=Windows-ROOT",
            "-Djavax.net.ssl.trustStore=NUL",
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(javaVersion))
    }
}
