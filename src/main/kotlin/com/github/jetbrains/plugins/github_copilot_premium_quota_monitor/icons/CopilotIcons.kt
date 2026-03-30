package com.github.jetbrains.plugins.github_copilot_premium_quota_monitor.icons

import com.intellij.openapi.util.IconLoader

/**
 * Icon constants for the GitHub Copilot Premium Quota Monitor plugin.
 *
 * Icons are loaded lazily from the `/icons/` resource directory.
 * IntelliJ automatically picks the `_dark` variant when a dark UI theme is active.
 */
object CopilotIcons {

    /** 16x16 GitHub Copilot logo used in the status bar widget and popup actions. */
    @JvmField
    val Logo = IconLoader.getIcon("/icons/statusbar.svg", CopilotIcons::class.java)
}
