package com.github.jetbrains.plugins.github_copilot_premium_quota_monitor.settings

import com.github.jetbrains.plugins.github_copilot_premium_quota_monitor.i18n.Messages
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.Configurable
import com.intellij.ui.ColorPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import java.awt.Color
import javax.swing.JComponent
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

/**
 * Settings panel for the GitHub Copilot Premium Quota Monitor plugin,
 * registered under Settings → Tools.
 *
 * Changes are applied immediately (no IDE restart required): when [apply] is
 * called the new values are persisted and a message-bus event is published so
 * that the status-bar widget can react on the fly.
 *
 * Constraint enforced on apply: 1 < criticalThreshold < warningThreshold < 100.
 */
class PluginSettingsConfigurable : Configurable {

    companion object {
        private val LOG = Logger.getInstance(PluginSettingsConfigurable::class.java)
    }

    // ── UI controls ───────────────────────────────────────────────────────────

    private var refreshSpinner: JSpinner?  = null
    private var criticalSpinner: JSpinner? = null
    private var criticalColor: ColorPanel? = null
    private var warningSpinner: JSpinner?  = null
    private var warningColor: ColorPanel?  = null

    // ── Configurable ──────────────────────────────────────────────────────────

    override fun getDisplayName(): String = Messages.get("settings_title")

    override fun createComponent(): JComponent {
        val s = PluginSettings.getInstance()

        refreshSpinner = JSpinner(SpinnerNumberModel(
            s.refreshIntervalMinutes,
            PluginSettings.MIN_INTERVAL_MINUTES,
            PluginSettings.MAX_INTERVAL_MINUTES, 1,
        ))

        criticalSpinner = JSpinner(SpinnerNumberModel(
            s.criticalThreshold,
            PluginSettings.MIN_THRESHOLD,
            PluginSettings.MAX_THRESHOLD - 1, // must stay < warning (≤ 98)
            1,
        ))
        @Suppress("UseJBColor")
        criticalColor = ColorPanel().also { it.selectedColor = Color(s.criticalColorRgb) }

        warningSpinner = JSpinner(SpinnerNumberModel(
            s.warningThreshold,
            PluginSettings.MIN_THRESHOLD + 1, // must stay > critical (≥ 3)
            PluginSettings.MAX_THRESHOLD, 1,
        ))
        @Suppress("UseJBColor")
        warningColor = ColorPanel().also { it.selectedColor = Color(s.warningColorRgb) }

        return panel {

            // ── Auto-refresh ──────────────────────────────────────────────────
            row(Messages.get("settings_refresh_interval_label")) {
                cell(refreshSpinner!!)
                label(Messages.get("settings_refresh_interval_unit"))
            }.rowComment(Messages.get("settings_refresh_interval_comment"))

            // ── Threshold section ─────────────────────────────────────────────
            group(Messages.get("settings_thresholds_group")) {

                row(Messages.get("settings_critical_threshold_label")) {
                    cell(criticalSpinner!!).widthGroup("thresholdSpinner")
                    label(Messages.get("settings_threshold_percent_unit"))
                    label(Messages.get("settings_threshold_color_label")).align(AlignX.RIGHT)
                    cell(criticalColor!!)
                }.rowComment(Messages.get("settings_critical_threshold_comment"))

                row(Messages.get("settings_warning_threshold_label")) {
                    cell(warningSpinner!!).widthGroup("thresholdSpinner")
                    label(Messages.get("settings_threshold_percent_unit"))
                    label(Messages.get("settings_threshold_color_label")).align(AlignX.RIGHT)
                    cell(warningColor!!)
                }.rowComment(Messages.get("settings_warning_threshold_comment"))
            }
        }
    }

    override fun isModified(): Boolean {
        val s = PluginSettings.getInstance()
        return (refreshSpinner?.value  as? Int) != s.refreshIntervalMinutes
            || (criticalSpinner?.value as? Int) != s.criticalThreshold
            || (criticalColor?.selectedColor?.rgb?.and(0xFFFFFF)) != s.criticalColorRgb
            || (warningSpinner?.value  as? Int) != s.warningThreshold
            || (warningColor?.selectedColor?.rgb?.and(0xFFFFFF))  != s.warningColorRgb
    }

    @Throws(ConfigurationException::class)
    override fun apply() {
        val critical = (criticalSpinner?.value as? Int) ?: PluginSettings.DEFAULT_CRITICAL_THRESHOLD
        val warning  = (warningSpinner?.value  as? Int) ?: PluginSettings.DEFAULT_WARNING_THRESHOLD

        // Enforce: 1 < critical < warning < 100
        if (critical <= 1 || warning >= 100 || critical >= warning) {
            throw ConfigurationException(Messages.get("settings_threshold_validation_error"))
        }

        val s = PluginSettings.getInstance()

        LOG.info("Applying settings: refreshInterval=${(refreshSpinner?.value as? Int)}, " +
                 "critical=$critical, warning=$warning")

        s.refreshIntervalMinutes = (refreshSpinner?.value as? Int) ?: PluginSettings.DEFAULT_INTERVAL_MINUTES
        s.criticalThreshold      = critical
        s.criticalColorRgb       = criticalColor?.selectedColor?.rgb?.and(0xFFFFFF)
                                   ?: PluginSettings.DEFAULT_CRITICAL_COLOR_RGB
        s.warningThreshold       = warning
        s.warningColorRgb        = warningColor?.selectedColor?.rgb?.and(0xFFFFFF)
                                   ?: PluginSettings.DEFAULT_WARNING_COLOR_RGB

        // Broadcast to all subscribers (e.g. status-bar widget).
        ApplicationManager.getApplication().messageBus
            .syncPublisher(SettingsChangeNotifier.SETTINGS_TOPIC)
            .settingsChanged()
    }

    override fun reset() {
        val s = PluginSettings.getInstance()
        refreshSpinner?.value  = s.refreshIntervalMinutes
        criticalSpinner?.value = s.criticalThreshold
        @Suppress("UseJBColor")
        criticalColor?.selectedColor = Color(s.criticalColorRgb)
        warningSpinner?.value  = s.warningThreshold
        @Suppress("UseJBColor")
        warningColor?.selectedColor  = Color(s.warningColorRgb)
    }

    override fun disposeUIResources() {
        refreshSpinner  = null
        criticalSpinner = null
        criticalColor   = null
        warningSpinner  = null
        warningColor    = null
    }
}

