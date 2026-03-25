package com.github.intellij.plugins.github_copilot_quota_monitor.statusbar

import com.github.intellij.plugins.github_copilot_quota_monitor.services.AuthService
import com.github.intellij.plugins.github_copilot_quota_monitor.services.PluginService
import com.github.intellij.plugins.github_copilot_quota_monitor.ui.DeviceAuthFlowDialog
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.ui.JBUI
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
// ...existing code...
import com.github.intellij.plugins.github_copilot_quota_monitor.i18n.Messages
import com.github.intellij.plugins.github_copilot_quota_monitor.services.QuotaNotifier
import com.github.intellij.plugins.github_copilot_quota_monitor.services.QuotaListener
import com.intellij.util.messages.MessageBusConnection
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import javax.swing.Timer

// Using centralized Messages helper

/**
 * Status bar widget that shows the remaining GitHub Copilot premium quota.
 *
 * Implemented as [CustomStatusBarWidget] so that the [MouseAdapter] attached
 * to the [JLabel] receives **all** mouse events (including right-click) directly,
 * without the IntelliJ status bar framework intercepting them.
 *
 * - Left-click  → immediate quota refresh.
 * - Right-click → Sign in / Sign out context menu.
 * - Auto-refresh every 5 minutes.
 */
class CopilotQuotaStatusBarWidget(
    private val project: Project
) : StatusBarWidget, CustomStatusBarWidget {

    companion object {
        const val WIDGET_ID = "GitHubCopilotQuotaWidget"
    }

    private var statusBar: StatusBar? = null
    private var busConnection: MessageBusConnection? = null

    /**
     * The label that lives in the status bar.
     * Left-click opens the popup menu (Refresh / Sign in or Sign out).
     */
    private val label: JLabel = JLabel(Messages.get("statusbar_widget_initial")).apply {
        border = JBUI.Borders.empty(0, 4)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(e)) showPopupMenu()
            }
        })
    }

    /** Timer that triggers a background refresh every 5 minutes. */
    private val refreshTimer = Timer(5 * 60 * 1_000) { _ -> refresh() }.apply {
        isRepeats = true
        isCoalesce = true
    }

    // ── StatusBarWidget ───────────────────────────────────────────────────────

    override fun ID(): String = WIDGET_ID

    /** Not used for CustomStatusBarWidget. */
    override fun getPresentation(): StatusBarWidget.WidgetPresentation? = null

    override fun getComponent(): JComponent = label

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        // Display the current cached state immediately
        updateLabel(PluginService.getInstance().cachedResult)
        refresh()
        // Subscribe to quota update notifications so we can update the label
        try {
            busConnection = ApplicationManager.getApplication().messageBus.connect()
            busConnection?.subscribe(QuotaNotifier.QUOTA_TOPIC, object : QuotaListener {
                override fun quotaUpdated(result: PluginService.QuotaResult) {
                    // Ensure UI update happens on EDT
                    ApplicationManager.getApplication().invokeLater { updateLabel(result) }
                }
            })
        } catch (_: Exception) {
            // best-effort
        }
        refreshTimer.start()
    }

    override fun dispose() {
        refreshTimer.stop()
        try { busConnection?.disconnect() } catch (_: Exception) {}
        busConnection = null
        statusBar = null
    }

    // ── Label update ──────────────────────────────────────────────────────────

    private fun updateLabel(result: PluginService.QuotaResult) {
        label.text = when (result) {
            is PluginService.QuotaResult.Loading   -> Messages.get("statusbar_widget_initial")
            is PluginService.QuotaResult.Available -> Messages.format("statusbar_widget_available", result.quota.remaining, result.quota.total)
            is PluginService.QuotaResult.Unlimited -> Messages.get("statusbar_widget_unlimited")
            is PluginService.QuotaResult.NoAccount -> Messages.get("statusbar_widget_signin")
            is PluginService.QuotaResult.Error     -> Messages.get("statusbar_widget_error")
        }
        label.toolTipText = when (result) {
            is PluginService.QuotaResult.Loading ->
                Messages.get("statusbar_tooltip_loading")

            is PluginService.QuotaResult.Available -> {
                val q = result.quota
                Messages.format("statusbar_tooltip_available_html", q.remaining, q.total, q.used, String.format("%.1f", q.percentUsed))
            }

            is PluginService.QuotaResult.Unlimited -> Messages.get("statusbar_tooltip_unlimited")

            is PluginService.QuotaResult.NoAccount -> Messages.get("statusbar_tooltip_noaccount_html")

            is PluginService.QuotaResult.Error -> Messages.format("statusbar_tooltip_error", result.message)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun showPopupMenu() {
        val dataContext = DataManager.getInstance().getDataContext(label)
        val popup = JBPopupFactory.getInstance()
            .createActionGroupPopup(
                null,
                CopilotQuotaPopupGroup(project),
                dataContext,
                JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
                false
            )
        popup.show(RelativePoint(label, Point(0, -popup.content.preferredSize.height)))
    }

    fun refresh() {
        PluginService.getInstance().refreshQuota { updateLabel(it) }
    }
}

// ── Popup Action Group ────────────────────────────────────────────────────────

/**
 * Popup menu shown on left-click.
 * Always shows Refresh; shows Sign in or Sign out based on authentication state.
 */
class CopilotQuotaPopupGroup(private val project: Project) : ActionGroup() {

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val auth = AuthService.getInstance()
        // Use non-blocking cached check to avoid calling PasswordSafe from inside
        // read actions / UI update code which may be executed under read lock.
        val authAction: AnAction = if (auth.isAuthenticatedCached()) SignOutAction() else SignInAction(project)
        return arrayOf(RefreshAction(), authAction)
    }
}

/**
 * Action: force an immediate quota refresh.
 */
class RefreshAction : AnAction(Messages.get("statusbar_action_refresh")) {

    override fun actionPerformed(e: AnActionEvent) {
        PluginService.getInstance().refreshQuota()
    }
}

/**
 * Action: sign in via GitHub OAuth Device Flow.
 */
class SignInAction(private val project: Project) : AnAction(Messages.get("statusbar_action_signin")) {

    override fun actionPerformed(e: AnActionEvent) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val deviceCode = AuthService.getInstance().requestDeviceCode()
                ApplicationManager.getApplication().invokeLater {
                    val dlg = DeviceAuthFlowDialog(project, deviceCode)
                    dlg.show()
                    // No direct refresh needed: AuthService publishes auth state and
                    // PluginService will refresh quota; widget listens to quota updates.
                }
            } catch (ex: Exception) {
                ApplicationManager.getApplication().invokeLater {
                    JOptionPane.showMessageDialog(
                        null,
                        Messages.format("statusbar_dialog_auth_error_msg", ex.message),
                        Messages.get("statusbar_dialog_auth_error_title"),
                        JOptionPane.ERROR_MESSAGE
                    )
                }
            }
        }
    }
}

/**
 * Action: sign out and clear stored credentials.
 */
class SignOutAction : AnAction(Messages.get("statusbar_action_signout")) {

    override fun actionPerformed(e: AnActionEvent) {
        val auth = AuthService.getInstance()
        val username = auth.getSavedUsername()
        val msg = if (username != null) Messages.format("statusbar_signout_confirm_when_username", username) else Messages.get("statusbar_signout_confirm_no_username")

        if (JOptionPane.showConfirmDialog(
                null, msg, Messages.get("statusbar_signout_title"), JOptionPane.YES_NO_OPTION
            ) == JOptionPane.YES_OPTION
        ) {
            auth.clearAuthentication()
            JOptionPane.showMessageDialog(
                null,
                Messages.get("statusbar_signout_complete"),
                Messages.get("statusbar_signout_title"),
                JOptionPane.INFORMATION_MESSAGE
            )
            // PluginService will refresh the quota in response to auth state change
        }
    }
}
