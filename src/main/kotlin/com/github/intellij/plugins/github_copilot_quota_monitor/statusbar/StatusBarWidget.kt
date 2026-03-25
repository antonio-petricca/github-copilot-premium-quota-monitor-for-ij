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

    /**
     * The label that lives in the status bar.
     * Left-click opens the popup menu (Refresh / Sign in or Sign out).
     */
    private val label: JLabel = JLabel(Messages.get("widget_initial")).apply {
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
        refreshTimer.start()
    }

    override fun dispose() {
        refreshTimer.stop()
        statusBar = null
    }

    // ── Label update ──────────────────────────────────────────────────────────

    private fun updateLabel(result: PluginService.QuotaResult) {
        label.text = when (result) {
            is PluginService.QuotaResult.Loading   -> Messages.get("widget_initial")
            is PluginService.QuotaResult.Available -> Messages.format("widget_available", result.quota.remaining, result.quota.total)
            is PluginService.QuotaResult.Unlimited -> Messages.get("widget_unlimited")
            is PluginService.QuotaResult.NoAccount -> Messages.get("widget_signin")
            is PluginService.QuotaResult.Error     -> Messages.get("widget_error")
        }
        label.toolTipText = when (result) {
            is PluginService.QuotaResult.Loading ->
                Messages.get("tooltip_loading")

            is PluginService.QuotaResult.Available -> {
                val q = result.quota
                Messages.format("tooltip_available_html", q.remaining, q.total, q.used, String.format("%.1f", q.percentUsed))
            }

            is PluginService.QuotaResult.Unlimited -> Messages.get("tooltip_unlimited")

            is PluginService.QuotaResult.NoAccount -> Messages.get("tooltip_noaccount_html")

            is PluginService.QuotaResult.Error -> Messages.format("tooltip_error", result.message)
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
class RefreshAction : AnAction(Messages.get("action_refresh")) {

    override fun actionPerformed(e: AnActionEvent) {
        PluginService.getInstance().refreshQuota()
    }
}

/**
 * Action: sign in via GitHub OAuth Device Flow.
 */
class SignInAction(private val project: Project) : AnAction(Messages.get("action_signin")) {

    override fun actionPerformed(e: AnActionEvent) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val deviceCode = AuthService.getInstance().requestDeviceCode()
                ApplicationManager.getApplication().invokeLater {
                    val dlg = DeviceAuthFlowDialog(project, deviceCode)
                    dlg.show()
                    // Force a refresh of the quota and the widget if present
                    if (dlg.authenticated) {
                        PluginService.getInstance().refreshQuota()
                        val statusBar = com.intellij.openapi.wm.WindowManager.getInstance().getStatusBar(project)
                        statusBar?.getWidget(CopilotQuotaStatusBarWidget.WIDGET_ID)?.let { widget ->
                            if (widget is CopilotQuotaStatusBarWidget) {
                                widget.refresh()
                            }
                        }
                    }
                }
            } catch (ex: Exception) {
                ApplicationManager.getApplication().invokeLater {
                    JOptionPane.showMessageDialog(
                        null,
                        Messages.format("dialog_auth_error_msg", ex.message),
                        Messages.get("dialog_auth_error_title"),
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
class SignOutAction : AnAction(Messages.get("action_signout")) {

    override fun actionPerformed(e: AnActionEvent) {
        val auth = AuthService.getInstance()
        val username = auth.getSavedUsername()
        val msg = if (username != null) Messages.format("signout_confirm_when_username", username) else Messages.get("signout_confirm_no_username")

        if (JOptionPane.showConfirmDialog(
                null, msg, Messages.get("signout_title"), JOptionPane.YES_NO_OPTION
            ) == JOptionPane.YES_OPTION
        ) {
            auth.clearAuthentication()
            JOptionPane.showMessageDialog(
                null,
                Messages.get("signout_complete"),
                Messages.get("signout_title"),
                JOptionPane.INFORMATION_MESSAGE
            )
            PluginService.getInstance().refreshQuota()
        }
    }
}
