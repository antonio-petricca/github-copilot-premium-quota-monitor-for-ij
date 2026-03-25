package com.github.intellij.plugins.github_copilot_quota_monitor.ui

import com.github.intellij.plugins.github_copilot_quota_monitor.i18n.Messages
import com.github.intellij.plugins.github_copilot_quota_monitor.services.AuthService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Desktop
import java.awt.Dimension
import java.awt.Font
import java.awt.datatransfer.StringSelection
import java.net.URI
import javax.swing.*

/**
 * Modal dialog that guides the user through the GitHub OAuth Device Flow.
 *
 * - Displays the device code and verification URL for the user to enter on GitHub.
 * - Opens the browser automatically.
 * - Polls GitHub in a background daemon thread and closes itself on success.
 *
 * Check [authenticated] after [show] returns to determine whether sign-in succeeded.
 */
class DeviceAuthFlowDialog(
    project: Project?,
    private val response: AuthService.DeviceCodeResponse,
) : DialogWrapper(project) {

    companion object {
        private val LOG = Logger.getInstance(DeviceAuthFlowDialog::class.java)
    }

    private val authService = AuthService.getInstance()

    /** `true` when the device flow completed successfully and the token was saved. */
    var authenticated: Boolean = false
        private set

    private var statusLabel: JBLabel? = null

    @Volatile
    private var pollingThread: Thread? = null

    init {
        title = Messages.get("deviceauth_dialog_title")
        setCancelButtonText(Messages.get("deviceauth_button_cancel"))
        setOKButtonText(Messages.get("deviceauth_button_ok"))
        init()
        window?.isAlwaysOnTop = false
        startPolling()
    }

    // ── UI ────────────────────────────────────────────────────────────────────

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout(0, 16)).apply {
            border = JBUI.Borders.emptyTop(24)
            preferredSize = Dimension(580, 280)
        }

        val headerLabel = JBLabel(Messages.get("deviceauth_dialog_header")).apply {
            font = font.deriveFont(Font.BOLD, 14f)
        }
        root.add(headerLabel, BorderLayout.NORTH)

        val contentPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.emptyTop(16)
        }

        // ── Step 1: enter the code ────────────────────────────────────────────
        contentPanel.add(leftAligned(JBLabel(Messages.get("deviceauth_step2"))))
        contentPanel.add(Box.createVerticalStrut(8))

        val codeLabel = JBLabel(response.userCode).apply {
            font = Font(Font.MONOSPACED, Font.BOLD, 24)
            horizontalAlignment = SwingConstants.LEFT
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(12)
        }
        val copyBtn = JButton(Messages.get("deviceauth_copy")).apply {
            addActionListener {
                CopyPasteManager.getInstance().setContents(StringSelection(response.userCode))
            }
        }
        val codePanel = JPanel(BorderLayout(12, 0)).apply {
            border = JBUI.Borders.empty(8)
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, codeLabel.preferredSize.height + 24)
            add(codeLabel, BorderLayout.CENTER)
            add(copyBtn,   BorderLayout.EAST)
        }
        contentPanel.add(codePanel)
        contentPanel.add(Box.createVerticalStrut(16))

        // ── Step 2: open the browser ──────────────────────────────────────────
        contentPanel.add(leftAligned(JBLabel(Messages.get("deviceauth_step1"))))
        contentPanel.add(Box.createVerticalStrut(8))

        val urlField = JTextField(response.verificationUri).apply {
            isEditable = false
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }
        val openBrowserBtn = JButton(Messages.get("deviceauth_open_url")).apply {
            addActionListener { openBrowser() }
        }
        val urlPanel = JPanel(BorderLayout(8, 0)).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, urlField.preferredSize.height + 8)
            add(urlField,       BorderLayout.CENTER)
            add(openBrowserBtn, BorderLayout.EAST)
        }
        contentPanel.add(urlPanel)
        contentPanel.add(Box.createVerticalStrut(16))

        // ── Status ────────────────────────────────────────────────────────────
        statusLabel = JBLabel(Messages.get("deviceauth_waiting_auth")).apply {
            font = font.deriveFont(Font.PLAIN, 10f)
            horizontalAlignment = SwingConstants.CENTER
        }
        contentPanel.add(statusLabel)
        contentPanel.add(Box.createVerticalGlue())

        root.add(contentPanel, BorderLayout.CENTER)
        return root
    }

    // ── Polling ───────────────────────────────────────────────────────────────

    private fun startPolling() {
        val intervalMs = response.interval.coerceAtLeast(5) * 1_000L
        val expiresAt  = System.currentTimeMillis() + response.expiresIn * 1_000L

        pollingThread = Thread {
            while (!Thread.interrupted() && System.currentTimeMillis() < expiresAt) {
                try {
                    Thread.sleep(intervalMs)
                } catch (_: InterruptedException) {
                    return@Thread
                }

                try {
                    when (val r = authService.pollForToken(response.deviceCode)) {
                        is AuthService.PollResult.Success -> {
                            authService.saveAuthentication(r.token)
                            authenticated = true
                            ApplicationManager.getApplication().invokeLater { close(OK_EXIT_CODE) }
                            return@Thread
                        }
                        is AuthService.PollResult.Pending -> { /* keep polling */ }
                        is AuthService.PollResult.Expired -> {
                            updateStatus(Messages.get("deviceauth_code_expired"))
                            return@Thread
                        }
                        is AuthService.PollResult.Error -> {
                            updateStatus(Messages.format("deviceauth_error_with_message", r.message))
                            return@Thread
                        }
                    }
                } catch (e: Exception) {
                    LOG.warn("Polling error", e)
                    updateStatus(Messages.format("deviceauth_network_error_prefix", e.message))
                    return@Thread
                }
            }
            updateStatus(Messages.get("deviceauth_code_expired"))
        }.also {
            it.isDaemon = true
            it.start()
        }
    }

    private fun updateStatus(text: String) {
        ApplicationManager.getApplication().invokeLater { statusLabel?.text = text }
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    override fun doCancelAction() {
        pollingThread?.interrupt()
        super.doCancelAction()
    }

    private fun openBrowser() {
        try {
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI(response.verificationUri))
        } catch (e: Exception) {
            LOG.warn("Failed to open browser", e)
        }
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private fun <T : JLabel> leftAligned(label: T): T = label.apply {
        horizontalAlignment = SwingConstants.LEFT
        alignmentX = Component.LEFT_ALIGNMENT
        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
    }
}
