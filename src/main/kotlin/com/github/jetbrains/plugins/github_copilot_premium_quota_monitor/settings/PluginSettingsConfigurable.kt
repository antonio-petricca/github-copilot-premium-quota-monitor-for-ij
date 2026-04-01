package com.github.jetbrains.plugins.github_copilot_premium_quota_monitor.settings

import com.github.jetbrains.plugins.github_copilot_premium_quota_monitor.i18n.Messages
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.Configurable
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

/**
 * Settings panel for the GitHub Copilot Premium Quota Monitor plugin,
 * registered under Settings → Tools.
 *
 * Changes are applied immediately (no IDE restart required): when [apply] is
 * called the new value is persisted and a message-bus event is published so
 * that the status-bar widget can update its refresh timer on the fly.
 */
class PluginSettingsConfigurable : Configurable {

    companion object {
        private val LOG = Logger.getInstance(PluginSettingsConfigurable::class.java)
    }

    private var spinner: JSpinner? = null

    // ── Configurable ──────────────────────────────────────────────────────────

    override fun getDisplayName(): String = Messages.get("settings_title")

    override fun createComponent(): JComponent {
        val settings = PluginSettings.getInstance()
        val model = SpinnerNumberModel(
            settings.refreshIntervalMinutes,
            PluginSettings.MIN_INTERVAL_MINUTES,
            PluginSettings.MAX_INTERVAL_MINUTES,
            1,
        )
        val sp = JSpinner(model)
        spinner = sp

        return panel {
            row(Messages.get("settings_refresh_interval_label")) {
                cell(sp)
                label(Messages.get("settings_refresh_interval_unit"))
            }.rowComment(Messages.get("settings_refresh_interval_comment"))
        }
    }

    override fun isModified(): Boolean {
        val settings = PluginSettings.getInstance()
        return (spinner?.value as? Int) != settings.refreshIntervalMinutes
    }

    override fun apply() {
        val settings = PluginSettings.getInstance()
        val newValue = (spinner?.value as? Int) ?: PluginSettings.DEFAULT_INTERVAL_MINUTES

        LOG.info("Applying new refresh interval: $newValue minutes")

        settings.refreshIntervalMinutes = newValue

        // Broadcast to all subscribers (e.g. status-bar widget) so they can
        // react without requiring an IDE restart.
        ApplicationManager.getApplication().messageBus
            .syncPublisher(SettingsChangeNotifier.SETTINGS_TOPIC)
            .settingsChanged()
    }

    override fun reset() {
        spinner?.value = PluginSettings.getInstance().refreshIntervalMinutes
    }

    override fun disposeUIResources() {
        spinner = null
    }
}

