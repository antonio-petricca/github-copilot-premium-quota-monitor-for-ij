package com.github.jetbrains.plugins.github_copilot_premium_quota_monitor

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId

/**
 * Provides plugin-level metadata (e.g. version) resolved at runtime from the
 * IntelliJ plugin descriptor, so that values such as the HTTP User-Agent header
 * always reflect the currently installed version without any manual constant to keep
 * in sync.
 */
object PluginInfo {

    private const val PLUGIN_ID =
        "com.github.jetbrains.plugins.github_copilot_premium_quota_monitor" +
        ".github-copilot-premium-quota-monitor-for-ij"

    /** The plugin version as declared in plugin.xml / gradle.properties (e.g. "1.0.6"). */
    val version: String by lazy {
        PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))?.version ?: "unknown"
    }

    /**
     * Ready-to-use value for the HTTP `User-Agent` request header.
     * Format: `github-copilot-quota-monitor-ij/<version>`
     */
    val userAgent: String by lazy {
        "github-copilot-quota-monitor-ij/$version"
    }
}

