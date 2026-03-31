package com.github.jetbrains.plugins.github_copilot_premium_quota_monitor.statusbar

import com.github.jetbrains.plugins.github_copilot_premium_quota_monitor.i18n.Messages
import com.github.jetbrains.plugins.github_copilot_premium_quota_monitor.icons.CopilotIcons
import com.github.jetbrains.plugins.github_copilot_premium_quota_monitor.services.AuthService
import com.intellij.icons.AllIcons
import com.github.jetbrains.plugins.github_copilot_premium_quota_monitor.services.PluginService
import com.github.jetbrains.plugins.github_copilot_premium_quota_monitor.services.QuotaListener
import com.github.jetbrains.plugins.github_copilot_premium_quota_monitor.services.QuotaNotifier
import com.github.jetbrains.plugins.github_copilot_premium_quota_monitor.ui.DeviceAuthFlowDialog
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages as UiMessages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.messages.MessageBusConnection
import com.intellij.util.ui.JBUI
import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.SwingUtilities
import javax.swing.Timer
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Status bar widget that shows the remaining GitHub Copilot premium quota.
 *
 * Implemented as [CustomStatusBarWidget] so that the [MouseAdapter] attached
 * to the [JLabel] receives **all** mouse events (including right-click) directly,
 * without the IntelliJ status bar framework intercepting them.
 *
 * - Left-click  → context menu (Refresh / Sign in or Sign out).
 * - Auto-refresh every 5 minutes.
 */
class CopilotQuotaStatusBarWidget(
    private val project: Project,
) : StatusBarWidget, CustomStatusBarWidget {

    companion object {
        const val WIDGET_ID = "GitHubCopilotQuotaWidget"
    }

    private var statusBar: StatusBar? = null
    private var busConnection: MessageBusConnection? = null

    private val label: JLabel = JLabel(Messages.get("statusbar_widget_initial")).apply {
        icon        = CopilotIcons.Logo
        iconTextGap = 4
        // Use the smaller UI font for status bar widgets so the label matches
        // the size of other status bar components.
        font        = JBUI.Fonts.smallFont()
        border      = JBUI.Borders.empty(0, 4)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(e)) showPopupMenu()
            }
        })
    }

    // Keep the default foreground so we can restore it for non-percent states.
    private val defaultLabelForeground = label.foreground

    /** Background refresh every 5 minutes. */
    private val refreshTimer = Timer(5 * 60 * 1_000) { refresh() }.apply {
        isRepeats  = true
        isCoalesce = true
    }

    // ── StatusBarWidget ───────────────────────────────────────────────────────

    override fun ID(): String = WIDGET_ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation? = null

    override fun getComponent(): JComponent = label

    override fun install(statusBar: StatusBar) {
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
        } catch (_: Exception) { /* best-effort */ }

        refreshTimer.start()
    }

    override fun dispose() {
        refreshTimer.stop()
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
            CopilotQuotaPopupGroup(project),
            dataContext,
            JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
            false,
        )
        popup.show(RelativePoint(label, Point(0, -popup.content.preferredSize.height)))
    }

    fun refresh() {
        PluginService.getInstance().refreshQuota { updateLabel(it) }
    }

    // Format an ISO-8601 timestamp string (UTC or with offset) into a user-friendly
    // local date/time string. Falls back to the original string on parse errors.
    @Suppress("unused")
    private fun formatTimestamp(ts: String): String {
        return try {
            val zoned: ZonedDateTime = try {
                Instant.parse(ts).atZone(ZoneId.systemDefault())
            } catch (_: Exception) {
                try {
                    OffsetDateTime.parse(ts).toZonedDateTime().withZoneSameInstant(ZoneId.systemDefault())
                } catch (_: Exception) {
                    return ts
                }
            }

            val fmt = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm z", Locale.getDefault())
            zoned.format(fmt)
        } catch (_: Exception) {
            ts
        }
    }
}

// ── Popup Action Group ────────────────────────────────────────────────────────

/**
 * Context menu shown on left-click.
 * Always shows Refresh; shows Sign in or Sign out based on authentication state.
 */
class CopilotQuotaPopupGroup(private val project: Project) : ActionGroup() {

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        // Use the cached (non-blocking) check — this may run under a read lock.
        val signAction: AnAction = if (AuthService.getInstance().isAuthenticatedCached())
            SignOutAction()
        else
            SignInAction(project)
        return arrayOf(RefreshAction(), signAction)
    }
}

/** Triggers an immediate quota refresh. */
class RefreshAction : AnAction(
    Messages.get("statusbar_action_refresh"),
    null,
    AllIcons.Actions.Refresh,
) {
    override fun actionPerformed(e: AnActionEvent) {
        PluginService.getInstance().refreshQuota()
    }
}

/** Starts a GitHub OAuth Device Flow sign-in. */
class SignInAction(private val project: Project) : AnAction(
    Messages.get("statusbar_action_signin"),
    null,
    // Usa una icona standard disponibile per il login (utente generico)
    AllIcons.General.User,
) {

    override fun actionPerformed(e: AnActionEvent) {
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
    // Usa una icona standard di uscita/logout compatibile con tutte le versioni
    AllIcons.Actions.Exit,
) {

    override fun actionPerformed(e: AnActionEvent) {
        val auth     = AuthService.getInstance()
        val username = auth.getSavedUsername()
        val msg      = if (username != null)
            Messages.format("statusbar_signout_confirm_when_username", username)
        else
            Messages.get("statusbar_signout_confirm_no_username")

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
