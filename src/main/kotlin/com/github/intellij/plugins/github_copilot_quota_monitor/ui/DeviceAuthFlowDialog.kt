package com.github.intellij.plugins.github_copilot_quota_monitor.ui

import com.github.intellij.plugins.github_copilot_quota_monitor.services.AuthService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import java.text.MessageFormat
import java.util.*
import com.github.intellij.plugins.github_copilot_quota_monitor.i18n.Messages
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Desktop
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
        title = Messages.get("dialog_title")
        setCancelButtonText(Messages.get("button_cancel"))
        setOKButtonText(Messages.get("button_ok"))
        init()
        window?.isAlwaysOnTop = true
        // Start polling for authentication in background
        startPolling()
    }

    override fun getInitialLocation(): java.awt.Point? {
        // Return a Point in top-left corner instead of null to prevent IntelliJ from centering
        window?.graphicsConfiguration?.bounds?.let { screenBounds ->
            return java.awt.Point(screenBounds.x + 32, screenBounds.y + 32)
        }
        return super.getInitialLocation()
    }

    override fun show() {
        super.show()
        // Double-check positioning after show using a daemon thread
        Thread {
            try {
                Thread.sleep(150)
                ApplicationManager.getApplication().invokeLater {
                    positionDialogTopLeft()
                }
            } catch (_: InterruptedException) {
                // Thread was interrupted, stop
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    private fun positionDialogTopLeft() {
        window?.let { w ->
            val screenBounds = w.graphicsConfiguration?.bounds ?: return
            val x = screenBounds.x + 32
            val y = screenBounds.y + 32
            w.setLocation(x, y)
        }
    }

    // ── UI ────────────────────────────────────────────────────────────────────

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout(0, 16))
        root.border = JBUI.Borders.emptyTop(24)
        root.preferredSize = Dimension(580, 280)

        // Title section
        val headerLabel = JBLabel(Messages.get("dialog_header"))
        headerLabel.font = headerLabel.font.deriveFont(Font.BOLD, 14f)
        root.add(headerLabel, BorderLayout.NORTH)

        // Content with steps
        val contentPanel = JPanel()
        contentPanel.layout = BoxLayout(contentPanel, BoxLayout.Y_AXIS)
        contentPanel.border = JBUI.Borders.emptyTop(16)

        // Step 1: URL
        contentPanel.add(JBLabel(Messages.get("step1")))
        contentPanel.add(Box.createVerticalStrut(8))
        val urlField = JTextField(response.verificationUri)
        urlField.isEditable = false
        val openBrowserBtn = JButton(Messages.get("open_url"))
        openBrowserBtn.addActionListener { openBrowser() }
        val urlPanel = JPanel(BorderLayout(8, 0))
        urlPanel.add(urlField, BorderLayout.CENTER)
        urlPanel.add(openBrowserBtn, BorderLayout.EAST)
        contentPanel.add(urlPanel)
        contentPanel.add(Box.createVerticalStrut(16))

        // Step 2: Code
        contentPanel.add(JBLabel(Messages.get("step2")))
        contentPanel.add(Box.createVerticalStrut(8))
        val codeLabel = JBLabel(response.userCode)
        codeLabel.font = Font(Font.MONOSPACED, Font.BOLD, 24)
        codeLabel.horizontalAlignment = SwingConstants.CENTER
        codeLabel.border = JBUI.Borders.empty(12)
        val copyBtn = JButton(Messages.get("copy"))
        copyBtn.addActionListener {
            CopyPasteManager.getInstance().setContents(StringSelection(response.userCode))
        }
        val codePanel = JPanel(BorderLayout(12, 0))
        codePanel.border = JBUI.Borders.empty(8)
        codePanel.add(codeLabel, BorderLayout.CENTER)
        codePanel.add(copyBtn, BorderLayout.EAST)
        contentPanel.add(codePanel)
        contentPanel.add(Box.createVerticalStrut(16))

        // Status label
        statusLabel = JBLabel(Messages.get("waiting_auth"))
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
                            authenticated = true
                            ApplicationManager.getApplication().invokeLater {
                                close(OK_EXIT_CODE)
                            }
                            return@Thread
                        }
                        is AuthService.PollResult.Pending  -> { /* keep polling */ }
                        is AuthService.PollResult.Expired  -> {
                            updateStatus(Messages.get("code_expired"))
                            return@Thread
                        }
                        is AuthService.PollResult.Error    -> {
                            updateStatus(Messages.format("error_with_message", r.message))
                            return@Thread
                        }
                    }
                } catch (e: Exception) {
                    LOG.warn("Errore polling", e)
                    updateStatus(Messages.format("network_error_prefix", e.message))
                    return@Thread
                }
            }
            updateStatus(Messages.get("code_expired"))
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
            LOG.warn("Impossibile aprire il browser automaticamente", e)
        }
    }
}

