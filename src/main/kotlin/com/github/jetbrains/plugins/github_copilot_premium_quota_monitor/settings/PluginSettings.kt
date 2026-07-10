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
    name = "GithubCopilotPremiumQuotaMonitorSettings",
    storages = [Storage("ghcp-premium-quota-monitor-settings.xml")]
)
class PluginSettings : PersistentStateComponent<PluginSettings.State> {

    data class State(
        var refreshIntervalMinutes: Int = DEFAULT_INTERVAL_MINUTES,
        var criticalThreshold: Int      = DEFAULT_CRITICAL_THRESHOLD,
        var criticalColorRgb: Int       = DEFAULT_CRITICAL_COLOR_RGB,
        var warningThreshold: Int       = DEFAULT_WARNING_THRESHOLD,
        var warningColorRgb: Int        = DEFAULT_WARNING_COLOR_RGB,
        // GitHub Enterprise Server (GHE) authentication
        var useGitHubEnterprise: Boolean     = false,
        var gitHubEnterpriseUrl: String      = "",
        var gitHubEnterpriseClientId: String = "",
    )

    companion object {
        const val DEFAULT_INTERVAL_MINUTES   = 5
        const val MIN_INTERVAL_MINUTES       = 1
        const val MAX_INTERVAL_MINUTES       = 60

        /** Quota % below which the widget turns red. Must satisfy: 1 < critical < warning < 100. */
        const val DEFAULT_CRITICAL_THRESHOLD = 10
        /** Quota % below which the widget turns yellow. Must satisfy: 1 < critical < warning < 100. */
        const val DEFAULT_WARNING_THRESHOLD  = 30
        /** Default pure red color for the critical state (24-bit RGB). */
        const val DEFAULT_CRITICAL_COLOR_RGB = 0xFF0000
        /** Default yellow color for the warning state (24-bit RGB). */
        const val DEFAULT_WARNING_COLOR_RGB  = 0xFFFF00

        const val MIN_THRESHOLD = 2   // critical > 1
        const val MAX_THRESHOLD = 99  // warning < 100

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

    /** Quota percentage threshold below which the status bar label turns the critical (red) color. */
    var criticalThreshold: Int
        get() = myState.criticalThreshold
        set(value) { myState.criticalThreshold = value.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD) }

    /** 24-bit RGB color used when quota is at or below [criticalThreshold]. */
    var criticalColorRgb: Int
        get() = myState.criticalColorRgb
        set(value) { myState.criticalColorRgb = value and 0xFFFFFF }

    /** Quota percentage threshold below which the status bar label turns the warning (yellow) color. */
    var warningThreshold: Int
        get() = myState.warningThreshold
        set(value) { myState.warningThreshold = value.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD) }

    /** 24-bit RGB color used when quota is at or below [warningThreshold] but above [criticalThreshold]. */
    var warningColorRgb: Int
        get() = myState.warningColorRgb
        set(value) { myState.warningColorRgb = value and 0xFFFFFF }

    // ── GitHub Enterprise Server (GHE) ────────────────────────────────────────

    /** Whether authentication and API calls should target a GitHub Enterprise Server instance. */
    var useGitHubEnterprise: Boolean
        get() = myState.useGitHubEnterprise
        set(value) { myState.useGitHubEnterprise = value }

    /** Base URL of the GitHub Enterprise Server instance (e.g. `https://github.example.com`), no trailing slash. */
    var gitHubEnterpriseUrl: String
        get() = myState.gitHubEnterpriseUrl
        set(value) { myState.gitHubEnterpriseUrl = value.trim().trimEnd('/') }

    /** OAuth App Client ID registered on the GitHub Enterprise Server instance (Device Flow enabled). */
    var gitHubEnterpriseClientId: String
        get() = myState.gitHubEnterpriseClientId
        set(value) { myState.gitHubEnterpriseClientId = value.trim() }
}

