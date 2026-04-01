package com.github.jetbrains.plugins.github_copilot_premium_quota_monitor.statusbar

import com.github.jetbrains.plugins.github_copilot_premium_quota_monitor.i18n.Messages
import com.github.jetbrains.plugins.github_copilot_premium_quota_monitor.icons.CopilotIcons
import com.github.jetbrains.plugins.github_copilot_premium_quota_monitor.services.AuthService
import com.github.jetbrains.plugins.github_copilot_premium_quota_monitor.services.PluginService
import com.github.jetbrains.plugins.github_copilot_premium_quota_monitor.services.QuotaListener
import com.github.jetbrains.plugins.github_copilot_premium_quota_monitor.services.QuotaNotifier
import com.github.jetbrains.plugins.github_copilot_premium_quota_monitor.settings.PluginSettings
import com.github.jetbrains.plugins.github_copilot_premium_quota_monitor.settings.PluginSettingsConfigurable
import com.github.jetbrains.plugins.github_copilot_premium_quota_monitor.settings.SettingsChangeListener
import com.github.jetbrains.plugins.github_copilot_premium_quota_monitor.settings.SettingsChangeNotifier
import com.github.jetbrains.plugins.github_copilot_premium_quota_monitor.ui.DeviceAuthFlowDialog
import com.intellij.icons.AllIcons
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages as UiMessages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.messages.MessageBusConnection
import com.intellij.util.ui.JBUI
import com.intellij.openapi.diagnostic.Logger
import java.awt.Color
import java.awt.Point
import java.awt.Toolkit
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * Status bar widget that shows the remaining GitHub Copilot premium quota.
 *
 * Implemented as [CustomStatusBarWidget] so that the [MouseAdapter] attached
 * to the [JLabel] receives **all** mouse events (including right-click) directly,
 * without the IntelliJ status bar framework intercepting them.
 *
 * - Left single-click  → context menu (Refresh / Sign in or Sign out).
 * - Left double-click  → immediate quota refresh (no menu).
 * - Auto-refresh at a configurable interval (default: 5 minutes).
 *
 * Single-click uses a short timer (system multi-click interval) to avoid
 * opening the popup on the first click of a double-click sequence.
 */
class CopilotQuotaStatusBarWidget(
    private val project: Project,
) : StatusBarWidget, CustomStatusBarWidget {

    companion object {
        private val LOG = Logger.getInstance(CopilotQuotaStatusBarWidget::class.java)

        const val WIDGET_ID = "GitHubCopilotQuotaWidget"
    }

    private var statusBar: StatusBar? = null
    private var busConnection: MessageBusConnection? = null
    private var blinkTimer: Timer? = null

    /**
     * Pending timer for a deferred single-click action (show popup).
     * Cancelled immediately if a second click arrives (double-click → refresh).
     */
    private var singleClickTimer: Timer? = null

    private val label: JLabel = JLabel(Messages.get("statusbar_widget_initial")).apply {
        icon        = CopilotIcons.Logo
        iconTextGap = 4
        // Use the smaller UI font for status bar widgets so the label matches
        // the size of other status bar components.
        font        = JBUI.Fonts.smallFont()
        border      = JBUI.Borders.empty(0, 4)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(e)) return
                when {
                    e.clickCount >= 2 -> {
                        // Double-click: cancel any pending single-click and refresh immediately.
                        singleClickTimer?.stop()
                        singleClickTimer = null
                        refresh()
                    }
                    e.clickCount == 1 -> {
                        // Single-click: defer popup until the double-click timeout elapses,
                        // so that the first click of a double-click does NOT open the popup.
                        val delay = (Toolkit.getDefaultToolkit()
                            .getDesktopProperty("awt.multiClickInterval") as? Int) ?: 300
                        singleClickTimer?.stop()
                        singleClickTimer = Timer(delay) { showPopupMenu() }.also {
                            it.isRepeats = false
                            it.start()
                        }
                    }
                }
            }
        })
    }

    // Keep the default foreground so we can restore it for non-percent states.
    private val defaultLabelForeground = label.foreground

    /** Background refresh – interval is read from [PluginSettings] and can be changed at runtime. */
    private val refreshTimer = Timer(PluginSettings.getInstance().refreshIntervalMinutes * 60 * 1_000) { refresh() }.apply {
        isRepeats  = true
        isCoalesce = true
    }

    // ── StatusBarWidget ───────────────────────────────────────────────────────

    override fun ID(): String = WIDGET_ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation? = null

    override fun getComponent(): JComponent = label

    override fun install(statusBar: StatusBar) {
        LOG.info("Installing Copilot Quota Status Bar Widget")

        this.statusBar = statusBar
        updateLabel(PluginService.getInstance().cachedResult)
        refresh()

        try {
            busConnection = ApplicationManager.getApplication().messageBus.connect()

            busConnection?.subscribe(QuotaNotifier.QUOTA_TOPIC, object : QuotaListener {
                override fun quotaUpdated(result: PluginService.QuotaResult) {
                    ApplicationManager.getApplication().invokeLater { updateLabel(result) }
                }
            })

            busConnection?.subscribe(SettingsChangeNotifier.SETTINGS_TOPIC, SettingsChangeListener {
                val newDelayMs = PluginSettings.getInstance().refreshIntervalMinutes * 60 * 1_000
                SwingUtilities.invokeLater {
                    refreshTimer.delay        = newDelayMs
                    refreshTimer.initialDelay = newDelayMs
                    refreshTimer.restart()
                }
            })
        } catch (_: Exception) { /* best-effort */ }

        refreshTimer.start()
    }

    override fun dispose() {
        LOG.info("Disposing Copilot Quota Status Bar Widget")

        refreshTimer.stop()

        blinkTimer?.stop()
        blinkTimer = null

        singleClickTimer?.stop()
        singleClickTimer = null

        try { busConnection?.disconnect() } catch (_: Exception) {}
        busConnection = null
        statusBar     = null
    }

    // ── Label update ──────────────────────────────────────────────────────────

    private fun updateLabel(result: PluginService.QuotaResult) {
        label.text = when (result) {
            is PluginService.QuotaResult.Loading   -> Messages.get("statusbar_widget_initial")
            is PluginService.QuotaResult.Available -> Messages.format("statusbar_widget_available", String.format("%.1f", result.quota.percentRemaining))
            is PluginService.QuotaResult.Unlimited -> Messages.get("statusbar_widget_unlimited")
            is PluginService.QuotaResult.NoAccount -> Messages.get("statusbar_widget_signin")
            is PluginService.QuotaResult.Error     -> Messages.get("statusbar_widget_error")
        }
        label.toolTipText = when (result) {
            is PluginService.QuotaResult.Loading   -> Messages.get("statusbar_tooltip_loading")
            is PluginService.QuotaResult.Available -> {
                val ts = result.quota.renewalDate
                val formatted = if (ts != null) formatTimestamp(ts) else ""
                val interactions = result.quota.quotaRemaining?.let { String.format("%.0f", it) } ?: ""
                val total = result.quota.quotaTotal
                if (total != null) {
                    val totalStr = String.format("%.0f", total)
                    Messages.format("statusbar_tooltip_html_with_total", String.format("%.1f", result.quota.percentRemaining), formatted, interactions, totalStr)
                } else {
                    Messages.format("statusbar_tooltip_html", String.format("%.1f", result.quota.percentRemaining), formatted, interactions)
                }
            }
            is PluginService.QuotaResult.Unlimited -> Messages.get("statusbar_tooltip_unlimited")
            is PluginService.QuotaResult.NoAccount -> Messages.get("statusbar_tooltip_noaccount_html")
            is PluginService.QuotaResult.Error     -> Messages.format("statusbar_tooltip_error", result.message)
        }

        // Color the percentage label based on remaining quota:
        // - red if <= 10%
        // - orange if <= 20%
        // Otherwise use the default label foreground.
        when (result) {
            is PluginService.QuotaResult.Available -> {
                val percent = result.quota.percentRemaining
                label.foreground = when {
                    percent <= 10.0 -> JBColor(Color(0xD32F2F), Color(0xFF5252)) // red (light/dark)
                    percent <= 20.0 -> JBColor(Color(0xF57C00), Color(0xFFB74D)) // orange (light/dark)
                    else -> defaultLabelForeground
                }
            }
            else -> label.foreground = defaultLabelForeground
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun showPopupMenu() {
        val dataContext = DataManager.getInstance().getDataContext(label)
        val popup = JBPopupFactory.getInstance().createActionGroupPopup(
            null,
            CopilotQuotaPopupGroup(project, ::refresh),
            dataContext,
            JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
            false,
        )
        popup.show(RelativePoint(label, Point(0, -popup.content.preferredSize.height)))
    }

    fun refresh() {
        LOG.debug("Manual quota refresh triggered")

        PluginService.getInstance().refreshQuota { result ->
            updateLabel(result)
            blinkLabel()
        }
    }

    /**
     * Makes the widget label blink twice after a quota refresh.
     * Alternates the label foreground between its current color and
     * the same color at 50 % opacity (alpha = 128).
     * Each half-cycle lasts 400 ms → full animation ~1 200 ms.
     *
     * Timeline:
     *   t =    0 ms  dim  (1st blink)
     *   t =  400 ms  full (1st blink end)
     *   t =  800 ms  dim  (2nd blink)
     *   t = 1 200 ms full (2nd blink end) → stop
     */
    private fun blinkLabel() {
        blinkTimer?.stop()
        val fullColor = label.foreground
        @Suppress("UseJBColor")
        val dimColor  = Color(fullColor.red, fullColor.green, fullColor.blue, 128)
        var step = 0
        blinkTimer = Timer(400, null).also { t ->
            t.addActionListener {
                step++
                label.foreground = if (step % 2 == 1) fullColor else dimColor
                if (step >= 3) {
                    label.foreground = fullColor
                    t.stop()
                }
            }
            t.isRepeats = true
            label.foreground = dimColor   // 1st blink: start dim
            t.start()
        }
    }

    // Format an ISO-8601 timestamp string into the current locale date/time format.
    // Falls back to the original string on parse errors.
    private fun formatTimestamp(ts: String): String {
        return try {
            val locale = Messages.locale()

            // Try full timestamp: Instant (UTC) → ZonedDateTime
            val zoned: ZonedDateTime? = try {
                Instant.parse(ts).atZone(ZoneId.systemDefault())
            } catch (_: Exception) {
                // Try timestamp with offset (OffsetDateTime)
                try {
                    OffsetDateTime.parse(ts).toZonedDateTime().withZoneSameInstant(ZoneId.systemDefault())
                } catch (_: Exception) {
                    null
                }
            }

            if (zoned != null) {
                return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
                    .withLocale(locale)
                    .format(zoned)
            }

            // Try date-only (e.g. "2026-03-31")
            try {
                return DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                    .withLocale(locale)
                    .format(LocalDate.parse(ts))
            } catch (_: Exception) {}

            ts // last-resort fallback: return raw string
        } catch (_: Exception) {
            ts
        }
    }
}

// ── Popup Action Group ────────────────────────────────────────────────────────

/**
 * Context menu shown on left-click.
 *
 * Order:
 *   1. Settings…
 *   2. Refresh
 *   ─────────────
 *   3. Sign in  /  Sign out
 *
 * [onRefresh] is invoked when the user selects "Refresh"; defaults to a plain
 * [PluginService.refreshQuota] call but callers may supply the widget's own
 * [CopilotQuotaStatusBarWidget.refresh] to also trigger the blink animation.
 */
class CopilotQuotaPopupGroup(
    private val project: Project,
    private val onRefresh: () -> Unit = { PluginService.getInstance().refreshQuota() },
) : ActionGroup() {

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        // Use the cached (non-blocking) check — this may run under a read lock.
        val signAction: AnAction = if (AuthService.getInstance().isAuthenticatedCached())
            SignOutAction()
        else
            SignInAction(project)
        return arrayOf(OpenSettingsAction(project), RefreshAction(onRefresh), Separator.getInstance(), signAction)
    }
}

/** Triggers an immediate quota refresh. */
class RefreshAction(
    private val onRefresh: () -> Unit = { PluginService.getInstance().refreshQuota() },
) : AnAction(
    Messages.get("statusbar_action_refresh"),
    null,
    AllIcons.Actions.Refresh,
) {
    override fun actionPerformed(e: AnActionEvent) {
        onRefresh()
    }
}

/** Starts a GitHub OAuth Device Flow sign-in. */
class SignInAction(private val project: Project) : AnAction(
    Messages.get("statusbar_action_signin"),
    null,
    AllIcons.General.User,
) {

    companion object {
        private val LOG = Logger.getInstance(SignInAction::class.java)
    }

    override fun actionPerformed(e: AnActionEvent) {
        LOG.info("Starting GitHub OAuth Device Flow sign-in")

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val deviceCode = AuthService.getInstance().requestDeviceCode()
                ApplicationManager.getApplication().invokeLater {
                    DeviceAuthFlowDialog(project, deviceCode).show()
                    // No direct refresh needed: AuthService publishes auth-state change →
                    // PluginService refreshes quota → widget receives quota update.
                }
            } catch (ex: Exception) {
                ApplicationManager.getApplication().invokeLater {
                    UiMessages.showErrorDialog(
                        project,
                        Messages.format("statusbar_dialog_auth_error_msg", ex.message),
                        Messages.get("statusbar_dialog_auth_error_title"),
                    )
                }
            }
        }
    }
}

/** Signs the user out and clears stored credentials. */
class SignOutAction : AnAction(
    Messages.get("statusbar_action_signout"),
    null,
    AllIcons.Actions.Exit,
) {

    companion object {
        private val LOG = Logger.getInstance(SignOutAction::class.java)
    }

    override fun actionPerformed(e: AnActionEvent) {
        LOG.info("Signing out user")

        val auth = AuthService.getInstance()
        val msg  = auth.getSavedUsername()
            ?.let { Messages.format("statusbar_signout_confirm_when_username", it) }
            ?: Messages.get("statusbar_signout_confirm_no_username")

        val confirmed = UiMessages.showYesNoDialog(
            e.project,
            msg,
            Messages.get("statusbar_signout_title"),
            null,
        ) == UiMessages.YES

        if (confirmed) {
            auth.clearAuthentication()

            UiMessages.showInfoMessage(
                e.project,
                Messages.get("statusbar_signout_complete"),
                Messages.get("statusbar_signout_title"),
            )
            // PluginService refreshes quota automatically in response to auth state change.
        }
    }
}

/** Opens the plugin settings panel (Settings → Tools → GitHub Copilot Premium Quota Monitor). */
class OpenSettingsAction(private val project: Project) : AnAction(
    Messages.get("statusbar_action_settings"),
    null,
    AllIcons.General.Settings,
) {
    override fun actionPerformed(e: AnActionEvent) {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, PluginSettingsConfigurable::class.java)
    }
}

