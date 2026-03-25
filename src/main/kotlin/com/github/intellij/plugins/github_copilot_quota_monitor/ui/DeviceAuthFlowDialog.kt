package com.github.intellij.plugins.github_copilot_quota_monitor.ui

import com.github.intellij.plugins.github_copilot_quota_monitor.services.AuthService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.github.intellij.plugins.github_copilot_quota_monitor.i18n.Messages
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Desktop
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.datatransfer.StringSelection
import java.net.URI
import javax.swing.*

/**
 * Modal dialog that guides the user through the GitHub OAuth Device Flow.
 *
 * - Shows the device code and verification URL for the user to enter on GitHub.
 * - Opens the browser automatically.
 * - Polls GitHub in a background daemon thread and closes itself when auth succeeds.
 * - The caller checks [authenticated] after [show] returns.
 */
class DeviceAuthFlowDialog(
    project: Project?,
    private val response: AuthService.DeviceCodeResponse
) : DialogWrapper(project) {

    companion object {
        private val LOG = Logger.getInstance(DeviceAuthFlowDialog::class.java)
    }

    private val authService = AuthService.getInstance()

    /** True when the device flow completed successfully and the token was saved. */
    var authenticated: Boolean = false
        private set

    private var statusLabel: JBLabel? = null
    private var pollingThread: Thread? = null

    init {
        title = Messages.get("deviceauth_dialog_title")
        setCancelButtonText(Messages.get("deviceauth_button_cancel"))
        setOKButtonText(Messages.get("deviceauth_button_ok"))
        init()
        // Do not force the dialog to be top-most; allow the IDE to manage window stacking.
        // Setting isAlwaysOnTop = false ensures the dialog won't cover other IDE windows.
        window?.isAlwaysOnTop = false
        // Start polling for authentication in background
        startPolling()
    }

    override fun getInitialLocation(): java.awt.Point? {
        // Use default initial location behaviour (allow DialogWrapper/IDE to decide).
        // Positioning is enforced after showing the dialog to ensure centering on screen.
        return super.getInitialLocation()
    }

    override fun show() {
        // Use default DialogWrapper/IDE positioning behaviour.
        super.show()
    }

    // Positioning helper removed; dialog is centered on screen after show().

    // ── UI ────────────────────────────────────────────────────────────────────

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout(0, 16))
        root.border = JBUI.Borders.emptyTop(24)
        root.preferredSize = Dimension(580, 280)

        // Title section
        val headerLabel = JBLabel(Messages.get("deviceauth_dialog_header"))
        headerLabel.font = headerLabel.font.deriveFont(Font.BOLD, 14f)
        root.add(headerLabel, BorderLayout.NORTH)

        // Content with steps
        val contentPanel = JPanel()
        contentPanel.layout = BoxLayout(contentPanel, BoxLayout.Y_AXIS)
        contentPanel.border = JBUI.Borders.emptyTop(16)

        // Step 1: Code (show code first, use step2 text for numbering since messages define step2 as "Enter this code")
        val step2Label = JBLabel(Messages.get("deviceauth_step2"))
        // Ensure the deviceauth_step2 label is left-aligned within the BoxLayout
        step2Label.horizontalAlignment = SwingConstants.LEFT
        step2Label.alignmentX = Component.LEFT_ALIGNMENT
        step2Label.maximumSize = Dimension(Integer.MAX_VALUE, step2Label.preferredSize.height)
        contentPanel.add(step2Label)
        contentPanel.add(Box.createVerticalStrut(8))
        val codeLabel = JBLabel(response.userCode)
        codeLabel.font = Font(Font.MONOSPACED, Font.BOLD, 24)
        // Left-align the code text to line up with the step labels
        codeLabel.horizontalAlignment = SwingConstants.LEFT
        codeLabel.alignmentX = Component.LEFT_ALIGNMENT
        codeLabel.border = JBUI.Borders.empty(12)
        val copyBtn = JButton(Messages.get("deviceauth_copy"))
        copyBtn.addActionListener {
            CopyPasteManager.getInstance().setContents(StringSelection(response.userCode))
        }
        val codePanel = JPanel(BorderLayout(12, 0))
        codePanel.border = JBUI.Borders.empty(8)
        // Allow the code panel to expand horizontally and align to the left in the BoxLayout
        codePanel.alignmentX = Component.LEFT_ALIGNMENT
        codePanel.maximumSize = Dimension(Integer.MAX_VALUE, codeLabel.preferredSize.height + 24)
        codePanel.add(codeLabel, BorderLayout.CENTER)
        codePanel.add(copyBtn, BorderLayout.EAST)
        contentPanel.add(codePanel)
        contentPanel.add(Box.createVerticalStrut(16))

        // Step 2: URL (show URL second, use step1 text for numbering which reads "Open in browser")
        val step1Label = JBLabel(Messages.get("deviceauth_step1"))
        // Ensure the deviceauth_step1 label is left-aligned within the BoxLayout
        step1Label.horizontalAlignment = SwingConstants.LEFT
        step1Label.alignmentX = Component.LEFT_ALIGNMENT
        step1Label.maximumSize = Dimension(Integer.MAX_VALUE, step1Label.preferredSize.height)
        contentPanel.add(step1Label)
        contentPanel.add(Box.createVerticalStrut(8))
        val urlField = JTextField(response.verificationUri)
        urlField.isEditable = false
        // Make the URL field expand horizontally so it aligns with labels
        urlField.maximumSize = Dimension(Integer.MAX_VALUE, urlField.preferredSize.height)
        val openBrowserBtn = JButton(Messages.get("deviceauth_open_url"))
        openBrowserBtn.addActionListener { openBrowser() }
        val urlPanel = JPanel(BorderLayout(8, 0))
        // Align and allow the URL panel to expand to the available width
        urlPanel.alignmentX = Component.LEFT_ALIGNMENT
        urlPanel.maximumSize = Dimension(Integer.MAX_VALUE, urlField.preferredSize.height + 8)
        urlPanel.add(urlField, BorderLayout.CENTER)
        urlPanel.add(openBrowserBtn, BorderLayout.EAST)
        contentPanel.add(urlPanel)
        contentPanel.add(Box.createVerticalStrut(16))

        // Status label
        statusLabel = JBLabel(Messages.get("deviceauth_waiting_auth"))
        statusLabel!!.font = statusLabel!!.font.deriveFont(Font.PLAIN, 10f)
        statusLabel!!.horizontalAlignment = SwingConstants.CENTER
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
                            // AuthService will publish an auth-state event; PluginService
                            // subscribes to it and will refresh the quota, so we don't
                            // need to call PluginService directly here.
                            authenticated = true
                            ApplicationManager.getApplication().invokeLater {
                                close(OK_EXIT_CODE)
                            }
                            return@Thread
                        }
                        is AuthService.PollResult.Pending  -> { /* keep polling */ }
                        is AuthService.PollResult.Expired  -> {
                            updateStatus(Messages.get("deviceauth_code_expired"))
                            return@Thread
                        }
                        is AuthService.PollResult.Error    -> {
                            updateStatus(Messages.format("deviceauth_error_with_message", r.message))
                            return@Thread
                        }
                    }
                } catch (e: Exception) {
                    LOG.warn("[CopilotQuotaMonitor] Polling error", e)
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
        ApplicationManager.getApplication().invokeLater {
            statusLabel?.text = text
        }
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
            LOG.warn("[CopilotQuotaMonitor] Failed to open browser", e)
        }
    }
}

