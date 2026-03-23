package com.github.intellij.plugins.github_copilot_quota_monitor.settings

import com.github.intellij.plugins.github_copilot_quota_monitor.services.GitHubAuthService
import com.github.intellij.plugins.github_copilot_quota_monitor.ui.GitHubDeviceFlowDialog
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import javax.swing.*

/**
 * IntelliJ Settings panel registered under Settings → Tools → GitHub Copilot Quota Monitor.
 *
 * Allows the user to sign in via GitHub OAuth Device Flow and sign out.
 * No "Apply" semantics are needed: sign-in and sign-out take effect immediately.
 */
class CopilotQuotaConfigurable : Configurable {

    private val auth get() = GitHubAuthService.getInstance()

    private var panel: JPanel? = null
    private lateinit var statusLabel: JBLabel

    // ── Configurable ──────────────────────────────────────────────────────────

    override fun getDisplayName(): String = "GitHub Copilot Quota Monitor"

    override fun createComponent(): JComponent {
        val root = JPanel()
        root.layout = BoxLayout(root, BoxLayout.Y_AXIS)
        root.border = JBUI.Borders.empty(16, 16, 16, 16)

        // Status
        statusLabel = JBLabel()
        refreshStatus()
        root.add(statusLabel)
        root.add(Box.createVerticalStrut(JBUI.scale(10)))

        // Buttons
        val signInBtn  = JButton("Sign in with GitHub").apply { addActionListener { signIn() } }
        val signOutBtn = JButton("Sign out").apply { addActionListener { signOut() } }
        val btnRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            alignmentX = JPanel.LEFT_ALIGNMENT
            add(signInBtn)
            add(Box.createHorizontalStrut(JBUI.scale(8)))
            add(signOutBtn)
        }
        root.add(btnRow)
        root.add(Box.createVerticalStrut(JBUI.scale(16)))

        // Info
        root.add(
            JBLabel(
                "<html><small>" +
                "Authentication uses the GitHub OAuth Device Flow (RFC 8628).<br/>" +
                "Your token is stored securely in the IDE credential store (PasswordSafe).<br/>" +
                "No password or secret is stored in plain text." +
                "</small></html>"
            )
        )

        return root.also { panel = it }
    }

    override fun isModified(): Boolean = false
    override fun apply() { /* no-op: actions are immediate */ }
    override fun reset() { if (::statusLabel.isInitialized) refreshStatus() }

    // ── Actions ───────────────────────────────────────────────────────────────

    private fun signIn() {
        // Start device-code request in background, then open the dialog on EDT
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val deviceCode = auth.requestDeviceCode()
                ApplicationManager.getApplication().invokeLater {
                    val dlg = GitHubDeviceFlowDialog(null, deviceCode)
                    dlg.show()
                    if (dlg.authenticated) refreshStatus()
                }
            } catch (e: Exception) {
                ApplicationManager.getApplication().invokeLater {
                    JOptionPane.showMessageDialog(
                        panel,
                        "Failed to start the sign-in flow:\n${e.message}",
                        "GitHub Sign-in Error",
                        JOptionPane.ERROR_MESSAGE
                    )
                }
            }
        }
    }

    private fun signOut() {
        auth.clearAuthentication()
        refreshStatus()
    }

    private fun refreshStatus() {
        if (!::statusLabel.isInitialized) return
        statusLabel.text = if (auth.isAuthenticated()) {
            val user = auth.getSavedUsername()
            if (user != null) "<html>&#10003;&nbsp;Signed in as <b>$user</b></html>"
            else "<html>&#10003;&nbsp;Signed in</html>"
        } else {
            "<html>&#10007;&nbsp;Not signed in</html>"
        }
    }
}

