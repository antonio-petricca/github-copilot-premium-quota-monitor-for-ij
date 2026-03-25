package com.github.intellij.plugins.github_copilot_quota_monitor.statusbar

import com.github.intellij.plugins.github_copilot_quota_monitor.services.Service
import com.github.intellij.plugins.github_copilot_quota_monitor.services.AuthService
import com.github.intellij.plugins.github_copilot_quota_monitor.ui.DeviceAuthFlowDialog
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.ide.DataManager
import com.intellij.ui.awt.RelativePoint
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.util.ui.JBUI
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import javax.swing.Timer

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
    private val label: JLabel = JLabel("⊙ Copilot").apply {
        border = JBUI.Borders.empty(0, 4)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(e)) showPopupMenu(e)
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
        // Mostra subito lo stato corrente (anche se la quota è già aggiornata)
        updateLabel(quotaService().cachedResult)
        refresh()
        refreshTimer.start()
    }

    override fun dispose() {
        refreshTimer.stop()
        statusBar = null
    }

    // ── Label update ──────────────────────────────────────────────────────────

    private fun updateLabel(result: Service.QuotaResult) {
        label.text = when (result) {
            is Service.QuotaResult.Loading   -> "⊙ Copilot"
            is Service.QuotaResult.Available -> "⊙ ${result.quota.remaining}/${result.quota.total}"
            is Service.QuotaResult.Unlimited -> "⊙ Copilot ∞"
            is Service.QuotaResult.NoAccount -> "⊙ Copilot — Sign in"
            is Service.QuotaResult.Error     -> "⊙ Copilot ✗"
        }
        label.toolTipText = when (result) {
            is Service.QuotaResult.Loading ->
                "GitHub Copilot Premium Quota — loading…"

            is Service.QuotaResult.Available -> {
                val q = result.quota
                "<html>GitHub Copilot — Premium quota<br>" +
                "Remaining: <b>${q.remaining} / ${q.total}</b><br>" +
                "Used: ${q.used} (${String.format("%.1f", q.percentUsed)} %)<br>" +
                "<i>Click for options</i></html>"
            }

            is Service.QuotaResult.Unlimited ->
                "GitHub Copilot — Premium quota: unlimited for your plan"

            is Service.QuotaResult.NoAccount ->
                "<html>GitHub Copilot — ⚠ Not signed in.<br>" +
                "<i>Click to sign in.</i></html>"

            is Service.QuotaResult.Error ->
                "GitHub Copilot — ✗ Error: ${result.message}"
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun showPopupMenu(event: MouseEvent) {
        val dataContext = DataManager.getInstance().getDataContext(label)
        val popup = JBPopupFactory.getInstance()
            .createActionGroupPopup(
                null,
                CopilotQuotaPopupGroup(project),
                dataContext,
                JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
                false
            )
        // Show the popup above the status bar widget
        popup.show(RelativePoint(label, Point(0, -popup.content.preferredSize.height)))
    }

    private fun quotaService(): Service = service()

    fun refresh() {
        // Mostra subito stato intermedio
        label.text = "⊙ Copilot (checking…)"
        label.toolTipText = "Checking Copilot quota…"
        quotaService().refreshAsync { result ->
            updateLabel(result)
        }
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
        val authAction: AnAction = if (auth.isAuthenticated()) SignOutAction(project) else SignInAction(project)
        return arrayOf(RefreshAction(project), authAction)
    }
}

/**
 * Action: force an immediate quota refresh.
 */
class RefreshAction(private val project: Project) : AnAction("Refresh") {

    override fun actionPerformed(e: AnActionEvent) {
        Service.getInstance().refreshAsync()
    }
}

/**
 * Action: sign in via GitHub OAuth Device Flow.
 */
class SignInAction(private val project: Project) : AnAction("Sign in with GitHub") {

    override fun actionPerformed(e: AnActionEvent) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val deviceCode = AuthService.getInstance().requestDeviceCode()
                ApplicationManager.getApplication().invokeLater {
                    val dlg = DeviceAuthFlowDialog(project, deviceCode)
                    dlg.show()
                    // Force un refresh globale e, se presente, anche del widget
                    if (dlg.authenticated) {
                        Service.getInstance().refreshAsync()
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
                        "Failed to start the sign-in flow:\n${ex.message}",
                        "GitHub Sign-in Error",
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
class SignOutAction(private val project: Project) : AnAction("Sign out") {

    override fun actionPerformed(e: AnActionEvent) {
        val auth = AuthService.getInstance()
        val username = auth.getSavedUsername()
        val msg = if (username != null) "Sign out of \"$username\"?" else "Sign out?"

        if (JOptionPane.showConfirmDialog(
                null, msg, "GitHub Sign-out", JOptionPane.YES_NO_OPTION
            ) == JOptionPane.YES_OPTION
        ) {
            auth.clearAuthentication()
            JOptionPane.showMessageDialog(
                null,
                "Successfully signed out.",
                "GitHub Sign-out",
                JOptionPane.INFORMATION_MESSAGE
            )
            // Force a refresh of the status bar to show "not signed in" state
            Service.getInstance().refreshAsync()
        }
    }
}

