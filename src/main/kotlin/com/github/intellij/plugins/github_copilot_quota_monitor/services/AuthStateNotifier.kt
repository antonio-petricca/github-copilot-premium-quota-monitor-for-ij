package com.github.intellij.plugins.github_copilot_quota_monitor.services

import com.intellij.util.messages.Topic

/**
 * MessageBus topic used to notify interested components when authentication
 * state changes (sign in / sign out).
 */
interface AuthStateListener {
    fun authStateChanged()
}

object AuthStateNotifier {
    val AUTH_TOPIC: Topic<AuthStateListener> = Topic.create("GitHubCopilotQuotaMonitor.AuthState", AuthStateListener::class.java)
}

