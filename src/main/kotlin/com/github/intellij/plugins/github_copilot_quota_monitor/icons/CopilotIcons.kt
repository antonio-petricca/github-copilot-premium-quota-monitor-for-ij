package com.github.intellij.plugins.github_copilot_quota_monitor.icons

import com.intellij.openapi.util.IconLoader

/**
 * Icon constants for the GitHub Copilot Premium Quota Monitor plugin.
 *
 * Icons are loaded lazily from the `/icons/` resource directory.
 * IntelliJ automatically picks the `_dark` variant when a dark UI theme is active.
 */
object CopilotIcons {

    /** 16×16 GitHub Copilot logo used in the status bar widget and popup actions. */
    @JvmField
    val Logo = IconLoader.getIcon("/icons/copilot.svg", CopilotIcons::class.java)
}

