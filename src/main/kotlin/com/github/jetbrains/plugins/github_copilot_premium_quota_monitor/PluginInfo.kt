package com.github.jetbrains.plugins.github_copilot_premium_quota_monitor

/**
 * Provides plugin-level metadata (e.g. version) resolved at runtime from the
 * plugin-version.properties resource file generated during the Gradle build,
 * so that values such as the HTTP User-Agent header always reflect the currently
 * installed version without relying on any IntelliJ-internal API.
 */
object PluginInfo {

    /** The plugin version as declared in gradle.properties (e.g. "1.0.6"). */
    val version: String by lazy {
        PluginInfo::class.java
            .getResourceAsStream("/plugin-version.properties")
            ?.use { stream ->
                java.util.Properties()
                    .apply { load(stream) }
                    .getProperty("version", "unknown")
            } ?: "unknown"
    }

    /**
     * Ready-to-use value for the HTTP `User-Agent` request header.
     * Format: `github-copilot-quota-monitor-ij/<version>`
     */
    val userAgent: String by lazy {
        "github-copilot-quota-monitor-ij/$version"
    }
}

