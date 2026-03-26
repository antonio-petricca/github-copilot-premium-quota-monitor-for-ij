package com.github.jetbrains.plugins.github_copilot_premium_quota_monitor.services

import com.intellij.util.messages.Topic

/**
 * MessageBus topic used to notify interested components when authentication
 * state changes (sign in / sign out).
 */
interface AuthStateListener {
    fun authStateChanged()
}

object AuthStateNotifier {
    @JvmField
    val AUTH_TOPIC: Topic<AuthStateListener> = Topic.create("GitHubCopilotQuotaMonitor.AuthState", AuthStateListener::class.java)
}

