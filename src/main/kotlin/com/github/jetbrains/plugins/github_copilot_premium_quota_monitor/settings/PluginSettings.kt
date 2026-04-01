package com.github.jetbrains.plugins.github_copilot_premium_quota_monitor.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * Application-level persistent settings for the GitHub Copilot Premium Quota Monitor plugin.
 *
 * Stored in `ghcp-quota-monitor-settings.xml` inside the IDE configuration directory.
 */
@Service(Service.Level.APP)
@State(
    name = "GithubCopilotQuotaMonitorSettings",
    storages = [Storage("ghcp-quota-monitor-settings.xml")]
)
class PluginSettings : PersistentStateComponent<PluginSettings.State> {

    data class State(
        var refreshIntervalMinutes: Int = DEFAULT_INTERVAL_MINUTES,
    )

    companion object {
        const val DEFAULT_INTERVAL_MINUTES = 5
        const val MIN_INTERVAL_MINUTES     = 1
        const val MAX_INTERVAL_MINUTES     = 60

        @JvmStatic
        fun getInstance(): PluginSettings =
            ApplicationManager.getApplication().getService(PluginSettings::class.java)
    }

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    /** The number of minutes between automatic quota refreshes. */
    var refreshIntervalMinutes: Int
        get() = myState.refreshIntervalMinutes
        set(value) {
            myState.refreshIntervalMinutes = value.coerceIn(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES)
        }
}

