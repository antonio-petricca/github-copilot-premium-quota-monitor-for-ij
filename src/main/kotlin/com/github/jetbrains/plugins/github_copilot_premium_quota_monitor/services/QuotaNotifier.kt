package com.github.jetbrains.plugins.github_copilot_premium_quota_monitor.services

import com.intellij.util.messages.Topic

/**
 * MessageBus topic to notify listeners when quota data is updated.
 */
interface QuotaListener {
    fun quotaUpdated(result: PluginService.QuotaResult)
}

object QuotaNotifier {
    @JvmField
    val QUOTA_TOPIC: Topic<QuotaListener> = Topic.create("GitHubCopilotQuotaMonitor.Quota", QuotaListener::class.java)
}

