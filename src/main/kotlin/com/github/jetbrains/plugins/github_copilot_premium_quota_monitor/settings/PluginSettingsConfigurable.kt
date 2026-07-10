package com.github.jetbrains.plugins.github_copilot_premium_quota_monitor.settings

import com.github.jetbrains.plugins.github_copilot_premium_quota_monitor.i18n.Messages
import com.github.jetbrains.plugins.github_copilot_premium_quota_monitor.services.AuthService
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.Messages as UiMessages
import com.intellij.ui.ColorPanel
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import java.awt.Color
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import javax.swing.SwingUtilities
import javax.swing.event.ChangeListener

/**
 * Settings panel for the GitHub Copilot Premium Quota Monitor plugin,
 * registered under Settings → Tools.
 *
 * Changes are applied immediately (no IDE restart required): when [apply] is
 * called the new values are persisted and a message-bus event is published so
 * that the status-bar widget can react on the fly.
 *
 * Constraint enforced on apply: 1 < criticalThreshold < warningThreshold < 100.
 * The same constraint is also validated inline (on change and on focus loss) via
 * `validationOnInput` so the user receives immediate feedback while editing.
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

    // GitHub Enterprise (Cloud with data residency, or self-hosted Server)
    private var gheTypeCombo: JComboBox<GitHubServerType>? = null
    private var gheUrlField: JBTextField?                  = null
    private var gheClientIdField: JBTextField?              = null

    /**
     * Reference to the built [DialogPanel] so that [ChangeListener]s on both
     * spinners can call [DialogPanel.validateAll] and refresh the inline error
     * indicator on the **other** spinner as well (cross-field validation).
     */
    private var dialogPanel: DialogPanel? = null

    // ── Reset actions ─────────────────────────────────────────────────────────

    private fun resetRefresh() {
        refreshSpinner?.value = PluginSettings.DEFAULT_INTERVAL_MINUTES
    }

    private fun resetCritical() {
        criticalSpinner?.value = PluginSettings.DEFAULT_CRITICAL_THRESHOLD
        @Suppress("UseJBColor")
        criticalColor?.selectedColor = Color(PluginSettings.DEFAULT_CRITICAL_COLOR_RGB)
    }

    private fun resetWarning() {
        warningSpinner?.value = PluginSettings.DEFAULT_WARNING_THRESHOLD
        @Suppress("UseJBColor")
        warningColor?.selectedColor = Color(PluginSettings.DEFAULT_WARNING_COLOR_RGB)
    }

    /**
     * Creates a small icon-only "Reset to default" button styled as a flat
     * toolbar button (borderless, no fill) consistent with the JetBrains UI.
     */
    private fun resetButton(action: () -> Unit): JButton =
        JButton(AllIcons.General.Reset).apply {
            toolTipText         = Messages.get("settings_reset_to_default")
            putClientProperty("JButton.buttonType", "toolbarButton")
            isContentAreaFilled = false
            isBorderPainted     = false
            addActionListener   { action() }
        }

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

        val p = panel {

            // ── Auto-refresh ──────────────────────────────────────────────────
            row(Messages.get("settings_refresh_interval_label")) {
                cell(refreshSpinner!!)
                label(Messages.get("settings_refresh_interval_unit"))
                cell(resetButton(::resetRefresh))
            }.rowComment(Messages.get("settings_refresh_interval_comment"))

            // ── Threshold section ─────────────────────────────────────────────
            group(Messages.get("settings_thresholds_group")) {

                row(Messages.get("settings_critical_threshold_label")) {
                    cell(criticalSpinner!!)
                        .widthGroup("thresholdSpinner")
                        .validationOnInput { sp ->
                            val c = sp.value as? Int ?: return@validationOnInput null
                            val w = warningSpinner?.value as? Int ?: return@validationOnInput null
                            if (c >= w) error(Messages.get("settings_threshold_cross_validation_error"))
                            else null
                        }
                    label(Messages.get("settings_threshold_percent_unit"))
                    label(Messages.get("settings_threshold_color_label")).align(AlignX.RIGHT)
                    cell(criticalColor!!)
                    cell(resetButton(::resetCritical))
                }.rowComment(Messages.get("settings_critical_threshold_comment"))

                row(Messages.get("settings_warning_threshold_label")) {
                    cell(warningSpinner!!)
                        .widthGroup("thresholdSpinner")
                        .validationOnInput { sp ->
                            val c = criticalSpinner?.value as? Int ?: return@validationOnInput null
                            val w = sp.value as? Int ?: return@validationOnInput null
                            if (c >= w) error(Messages.get("settings_threshold_cross_validation_error"))
                            else null
                        }
                    label(Messages.get("settings_threshold_percent_unit"))
                    label(Messages.get("settings_threshold_color_label")).align(AlignX.RIGHT)
                    cell(warningColor!!)
                    cell(resetButton(::resetWarning))
                }.rowComment(Messages.get("settings_warning_threshold_comment"))
            }

            // ── GitHub Enterprise section ──────────────────────────────────────
            group(Messages.get("settings_ghe_group")) {
                row(Messages.get("settings_ghe_type_label")) {
                    gheTypeCombo = comboBox(
                        listOf(GitHubServerType.GITHUB_COM, GitHubServerType.ENTERPRISE_CLOUD, GitHubServerType.ENTERPRISE_SERVER),
                        renderer = SimpleListCellRenderer.create("") { type ->
                            when (type) {
                                GitHubServerType.GITHUB_COM        -> Messages.get("settings_ghe_type_none")
                                GitHubServerType.ENTERPRISE_CLOUD  -> Messages.get("settings_ghe_type_cloud")
                                GitHubServerType.ENTERPRISE_SERVER -> Messages.get("settings_ghe_type_server")
                                else                                -> ""
                            }
                        },
                    ).component
                }.rowComment(Messages.get("settings_ghe_type_comment"))

                row(Messages.get("settings_ghe_url_label")) {
                    gheUrlField = textField().align(AlignX.FILL)
                        .validationOnInput { tf ->
                            if (gheTypeCombo?.selectedItem != GitHubServerType.GITHUB_COM) {
                                val v = tf.text.trim()
                                if (v.isBlank() || !(v.startsWith("http://") || v.startsWith("https://")))
                                    error(Messages.get("settings_ghe_url_validation_error"))
                                else null
                            } else null
                        }.component
                }.rowComment(Messages.get("settings_ghe_url_comment"))

                row(Messages.get("settings_ghe_client_id_label")) {
                    gheClientIdField = textField().align(AlignX.FILL)
                        .validationOnInput { tf ->
                            if (gheTypeCombo?.selectedItem != GitHubServerType.GITHUB_COM && tf.text.isBlank())
                                error(Messages.get("settings_ghe_client_id_validation_error"))
                            else null
                        }.component
                }.rowComment(Messages.get("settings_ghe_client_id_comment"))
            }
        }

        // Enable the Server URL / OAuth Client ID fields only when a GitHub Enterprise
        // deployment (Cloud or Server) is selected; keep them disabled for github.com.
        // Also re-run validators so their inline error state updates immediately.
        fun updateGheFieldsEnabled() {
            val enabled = gheTypeCombo?.selectedItem != GitHubServerType.GITHUB_COM
            gheUrlField?.isEnabled      = enabled
            gheClientIdField?.isEnabled = enabled
            SwingUtilities.invokeLater { p.validateAll() }
        }
        gheTypeCombo?.addItemListener { updateGheFieldsEnabled() }
        updateGheFieldsEnabled()

        // When EITHER spinner changes, re-run ALL registered validators so that
        // the inline error on the OTHER spinner also clears / appears at once.
        val revalidate = ChangeListener { SwingUtilities.invokeLater { p.validateAll() } }
        criticalSpinner!!.addChangeListener(revalidate)
        warningSpinner!!.addChangeListener(revalidate)

        dialogPanel = p
        return p
    }

    override fun isModified(): Boolean {
        val s = PluginSettings.getInstance()
        return (refreshSpinner?.value  as? Int) != s.refreshIntervalMinutes
            || (criticalSpinner?.value as? Int) != s.criticalThreshold
            || (criticalColor?.selectedColor?.rgb?.and(0xFFFFFF)) != s.criticalColorRgb
            || (warningSpinner?.value  as? Int) != s.warningThreshold
            || (warningColor?.selectedColor?.rgb?.and(0xFFFFFF))  != s.warningColorRgb
            || (gheTypeCombo?.selectedItem as? GitHubServerType ?: GitHubServerType.GITHUB_COM) != s.gitHubServerType
            || (gheUrlField?.text?.trim() ?: "")                  != s.gitHubEnterpriseUrl
            || (gheClientIdField?.text?.trim() ?: "")             != s.gitHubEnterpriseClientId
    }

    @Throws(ConfigurationException::class)
    override fun apply() {
        val critical = (criticalSpinner?.value as? Int) ?: PluginSettings.DEFAULT_CRITICAL_THRESHOLD
        val warning  = (warningSpinner?.value  as? Int) ?: PluginSettings.DEFAULT_WARNING_THRESHOLD

        // Enforce: 1 < critical < warning < 100 (safety net — inline validators should
        // have already blocked Apply via the UI, but we guard here too).
        if (critical <= 1 || warning >= 100 || critical >= warning) {
            throw ConfigurationException(
                Messages.get("settings_threshold_validation_error"),
                Messages.get("settings_threshold_validation_title"),
            )
        }

        val gheType     = (gheTypeCombo?.selectedItem as? GitHubServerType) ?: GitHubServerType.GITHUB_COM
        val gheEnabled  = gheType != GitHubServerType.GITHUB_COM
        val gheUrl      = gheUrlField?.text?.trim() ?: ""
        val gheClientId = gheClientIdField?.text?.trim() ?: ""

        // Enforce GHE constraints (safety net — inline validators should have
        // already blocked Apply via the UI, but we guard here too).
        if (gheEnabled && (gheUrl.isBlank() || !(gheUrl.startsWith("http://") || gheUrl.startsWith("https://")))) {
            throw ConfigurationException(
                Messages.get("settings_ghe_url_validation_error"),
                Messages.get("settings_ghe_validation_title"),
            )
        }
        if (gheEnabled && gheClientId.isBlank()) {
            throw ConfigurationException(
                Messages.get("settings_ghe_client_id_validation_error"),
                Messages.get("settings_ghe_validation_title"),
            )
        }

        val s = PluginSettings.getInstance()

        LOG.info("Applying settings: refreshInterval=${(refreshSpinner?.value as? Int)}, " +
                 "critical=$critical, warning=$warning, gheType=$gheType")

        // Detect whether the GHE configuration actually changed, so we can
        // force a re-authentication (an existing token is bound to a single host).
        val gheConfigChanged = gheType != s.gitHubServerType
            || gheUrl != s.gitHubEnterpriseUrl
            || gheClientId != s.gitHubEnterpriseClientId

        s.refreshIntervalMinutes = (refreshSpinner?.value as? Int) ?: PluginSettings.DEFAULT_INTERVAL_MINUTES
        s.criticalThreshold      = critical
        s.criticalColorRgb       = criticalColor?.selectedColor?.rgb?.and(0xFFFFFF)
                                   ?: PluginSettings.DEFAULT_CRITICAL_COLOR_RGB
        s.warningThreshold       = warning
        s.warningColorRgb        = warningColor?.selectedColor?.rgb?.and(0xFFFFFF)
                                   ?: PluginSettings.DEFAULT_WARNING_COLOR_RGB
        s.gitHubServerType           = gheType
        s.gitHubEnterpriseUrl        = gheUrl
        s.gitHubEnterpriseClientId   = gheClientId

        if (gheConfigChanged && AuthService.getInstance().isAuthenticatedCached()) {
            LOG.info("GitHub Enterprise configuration changed — clearing existing authentication")

            AuthService.getInstance().clearAuthentication()
            UiMessages.showInfoMessage(
                Messages.get("settings_ghe_reauth_required_msg"),
                Messages.get("settings_ghe_reauth_required_title"),
            )
        }

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
        gheTypeCombo?.selectedItem      = s.gitHubServerType
        gheUrlField?.text               = s.gitHubEnterpriseUrl
        gheClientIdField?.text          = s.gitHubEnterpriseClientId
    }

    override fun disposeUIResources() {
        refreshSpinner  = null
        criticalSpinner = null
        criticalColor   = null
        warningSpinner  = null
        warningColor    = null
        gheTypeCombo     = null
        gheUrlField      = null
        gheClientIdField = null
        dialogPanel     = null
    }
}
