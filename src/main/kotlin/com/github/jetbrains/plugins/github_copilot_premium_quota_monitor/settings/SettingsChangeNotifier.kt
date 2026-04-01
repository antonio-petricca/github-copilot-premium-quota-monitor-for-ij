package com.github.jetbrains.plugins.github_copilot_premium_quota_monitor.settings

import com.intellij.util.messages.Topic

/**
 * Application-level message bus topic published whenever plugin settings are applied.
 */
object SettingsChangeNotifier {
    val SETTINGS_TOPIC: Topic<SettingsChangeListener> =
        Topic.create("GHCPQuotaMonitorSettingsChanged", SettingsChangeListener::class.java)
}

