package com.github.jetbrains.plugins.github_copilot_premium_quota_monitor.settings

/**
 * Listener notified whenever the plugin settings change (e.g. the refresh interval).
 */
fun interface SettingsChangeListener {
    fun settingsChanged()
}

