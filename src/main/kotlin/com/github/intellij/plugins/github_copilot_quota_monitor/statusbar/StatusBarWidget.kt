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
            is PluginService.QuotaResult.Loading   -> "⊙ Copilot"
            is PluginService.QuotaResult.Available -> "⊙ ${result.quota.remaining}/${result.quota.total}"
            is PluginService.QuotaResult.Unlimited -> "⊙ Copilot ∞"
            is PluginService.QuotaResult.NoAccount -> "⊙ Copilot — Accedi"
            is PluginService.QuotaResult.Error     -> "⊙ Copilot ✗"
        }
        label.toolTipText = when (result) {
            is PluginService.QuotaResult.Loading ->
                "Quota Copilot Premium — caricamento…"

            is PluginService.QuotaResult.Available -> {
                val q = result.quota
                "<html>Copilot Premium<br>" +
                "Disponibile: <b>${q.remaining} / ${q.total}</b><br>" +
                "Utilizzato: ${q.used} (${String.format("%.1f", q.percentUsed)}%)<br>" +
                "<i>Clicca per opzioni</i></html>"
            }

            is PluginService.QuotaResult.Unlimited ->
                "Copilot Premium: quota illimitata"

            is PluginService.QuotaResult.NoAccount ->
                "<html>Copilot — ⚠ Non autenticato.<br>" +
                "<i>Clicca per accedere.</i></html>"

            is PluginService.QuotaResult.Error ->
                "Copilot — ✗ Errore: ${result.message}"
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
        val authAction: AnAction = if (auth.isAuthenticated()) SignOutAction() else SignInAction(project)
        return arrayOf(RefreshAction(), authAction)
    }
}

/**
 * Action: force an immediate quota refresh.
 */
class RefreshAction : AnAction("Aggiorna") {

    override fun actionPerformed(e: AnActionEvent) {
        PluginService.getInstance().refreshQuota()
    }
}

/**
 * Action: sign in via GitHub OAuth Device Flow.
 */
class SignInAction(private val project: Project) : AnAction("Accedi con GitHub") {

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
                        "Impossibile avviare l'autenticazione:\n${ex.message}",
                        "Errore accesso GitHub",
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
class SignOutAction : AnAction("Disconnetti") {

    override fun actionPerformed(e: AnActionEvent) {
        val auth = AuthService.getInstance()
        val username = auth.getSavedUsername()
        val msg = if (username != null) "Disconnettere \"$username\"?" else "Disconnettere l'account?"

        if (JOptionPane.showConfirmDialog(
                null, msg, "Disconnessione GitHub", JOptionPane.YES_NO_OPTION
            ) == JOptionPane.YES_OPTION
        ) {
            auth.clearAuthentication()
            JOptionPane.showMessageDialog(
                null,
                "Disconnessione completata.",
                "Disconnessione GitHub",
                JOptionPane.INFORMATION_MESSAGE
            )
            PluginService.getInstance().refreshQuota()
        }
    }
}
