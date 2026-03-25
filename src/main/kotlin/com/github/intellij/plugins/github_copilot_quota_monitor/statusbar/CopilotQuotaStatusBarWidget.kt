package com.github.intellij.plugins.github_copilot_quota_monitor.statusbar

import com.github.intellij.plugins.github_copilot_quota_monitor.services.CopilotQuotaService
import com.github.intellij.plugins.github_copilot_quota_monitor.services.GitHubAuthService
import com.github.intellij.plugins.github_copilot_quota_monitor.ui.GitHubDeviceFlowDialog
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.util.ui.JBUI
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
     * Mouse events are handled here directly — no intermediary.
     */
    private val label: JLabel = JLabel("⊙ Copilot").apply {
        border = JBUI.Borders.empty(0, 4)
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                when {
                    SwingUtilities.isLeftMouseButton(e)  -> refresh()
                    SwingUtilities.isRightMouseButton(e) -> showPopupMenu(e)
                }
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
        refresh()
        refreshTimer.start()
    }

    override fun dispose() {
        refreshTimer.stop()
        statusBar = null
    }

    // ── Label update ──────────────────────────────────────────────────────────

    private fun updateLabel(result: CopilotQuotaService.QuotaResult) {
        label.text = when (result) {
            is CopilotQuotaService.QuotaResult.Loading   -> "⊙ Copilot"
            is CopilotQuotaService.QuotaResult.Available -> "⊙ ${result.quota.remaining}/${result.quota.total}"
            is CopilotQuotaService.QuotaResult.Unlimited -> "⊙ Copilot ∞"
            is CopilotQuotaService.QuotaResult.NoAccount -> "⊙ Copilot ⚠"
            is CopilotQuotaService.QuotaResult.Error     -> "⊙ Copilot ✗"
        }
        label.toolTipText = when (result) {
            is CopilotQuotaService.QuotaResult.Loading ->
                "GitHub Copilot Premium Quota — loading…"

            is CopilotQuotaService.QuotaResult.Available -> {
                val q = result.quota
                "<html>GitHub Copilot — Premium quota<br>" +
                "Remaining: <b>${q.remaining} / ${q.total}</b><br>" +
                "Used: ${q.used} (${String.format("%.1f", q.percentUsed)} %)<br>" +
                "<i>Left-click to refresh · Right-click for options</i></html>"
            }

            is CopilotQuotaService.QuotaResult.Unlimited ->
                "GitHub Copilot — Premium quota: unlimited for your plan"

            is CopilotQuotaService.QuotaResult.NoAccount ->
                "<html>GitHub Copilot — ⚠ Not signed in.<br>" +
                "<i>Right-click to sign in.</i></html>"

            is CopilotQuotaService.QuotaResult.Error ->
                "GitHub Copilot — ✗ Error: ${result.message}"
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun showPopupMenu(event: MouseEvent) {
        val popup = JBPopupFactory.getInstance()
            .createActionGroupPopup(
                null,
                CopilotQuotaPopupGroup(project),
                DataContext { null },
                JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
                false
            )
        popup.show(event.component)
    }

    private fun quotaService(): CopilotQuotaService = service()

    private fun refresh() {
        quotaService().refreshAsync { result ->
            updateLabel(result)
        }
    }
}

// ── Popup Action Group ────────────────────────────────────────────────────────

/**
 * Context menu shown on right-click.
 * Dynamically shows "Sign in" or "Sign out" based on authentication state.
 */
class CopilotQuotaPopupGroup(private val project: Project) : ActionGroup() {

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val auth = GitHubAuthService.getInstance()
        return if (auth.isAuthenticated()) {
            arrayOf(SignOutAction(project))
        } else {
            arrayOf(SignInAction(project))
        }
    }
}

/**
 * Action: sign in via GitHub OAuth Device Flow.
 */
class SignInAction(private val project: Project) : AnAction("Sign in with GitHub") {

    override fun actionPerformed(e: AnActionEvent) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val deviceCode = GitHubAuthService.getInstance().requestDeviceCode()
                ApplicationManager.getApplication().invokeLater {
                    GitHubDeviceFlowDialog(project, deviceCode).show()
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
        val auth = GitHubAuthService.getInstance()
        val username = auth.getSavedUsername()
        val msg = if (username != null) "Sign out of \"$username\"?" else "Sign out?"

        if (JOptionPane.showConfirmDialog(
                null, msg, "GitHub Sign-out", JOptionPane.YES_NO_OPTION
            ) == JOptionPane.YES_OPTION
        ) {
            auth.clearAuthentication()
        }
    }
}

