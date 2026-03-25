package com.github.intellij.plugins.github_copilot_quota_monitor.services

import com.intellij.util.messages.Topic

/**
 * MessageBus topic to notify listeners when quota data is updated.
 */
interface QuotaListener {
    fun quotaUpdated(result: PluginService.QuotaResult)
}

object QuotaNotifier {
    val QUOTA_TOPIC: Topic<QuotaListener> = Topic.create("GitHubCopilotQuotaMonitor.Quota", QuotaListener::class.java)
}

