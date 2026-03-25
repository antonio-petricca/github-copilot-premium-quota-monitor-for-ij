package com.github.intellij.plugins.github_copilot_quota_monitor.ui

import com.github.intellij.plugins.github_copilot_quota_monitor.services.GitHubAuthService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
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
 * - Shows the [verificationUri] and [userCode] for the user to enter on GitHub.
 * - Opens the browser automatically.
 * - Polls GitHub in a background daemon thread and closes itself when auth succeeds.
 * - The caller checks [authenticated] after [show] returns.
 */
class GitHubDeviceFlowDialog(
    project: Project?,
    private val response: GitHubAuthService.DeviceCodeResponse
) : DialogWrapper(project) {

    companion object {
        private val LOG = Logger.getInstance(GitHubDeviceFlowDialog::class.java)
    }

    private val authService = GitHubAuthService.getInstance()

    /** True when the device flow completed successfully and the token was saved. */
    var authenticated: Boolean = false
        private set

    private lateinit var statusLabel: JBLabel
    private var pollingThread: Thread? = null

    init {
        title = "Sign in to GitHub — Copilot Quota Monitor"
        setCancelButtonText("Cancel")
        setOKButtonText("Done")
        init()
        // Bring dialog to foreground
        window?.isAlwaysOnTop = true
        openBrowser()
        startPolling()
    }

    // ── UI ────────────────────────────────────────────────────────────────────

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout(0, JBUI.scale(12)))
        root.border = JBUI.Borders.empty(16)
        root.preferredSize = Dimension(JBUI.scale(520), JBUI.scale(220))

        // ── Header ──────────────────────────────────────────────────────────
        root.add(
            JBLabel(
                "<html>Open the URL below in your browser, enter the code, and sign in with " +
                "your GitHub account.<br/>This dialog will close automatically when " +
                "authentication succeeds.</html>"
            ),
            BorderLayout.NORTH
        )

        // ── Centre: URL row + code row + status ──────────────────────────────
        val centre = JPanel()
        centre.layout = BoxLayout(centre, BoxLayout.Y_AXIS)

        // URL row
        val urlField = JTextField(response.verificationUri).apply { isEditable = false }
        val openBtn  = JButton("Open in Browser").apply {
            addActionListener { openBrowser() }
        }
        val urlRow = JPanel(BorderLayout(JBUI.scale(4), 0))
        urlRow.add(urlField, BorderLayout.CENTER)
        urlRow.add(openBtn,  BorderLayout.EAST)

        // Code row
        val codeLabel = JBLabel(response.userCode).apply {
            font = Font(Font.MONOSPACED, Font.BOLD, JBUI.scaleFontSize(22f))
        }
        val copyBtn = JButton("Copy Code").apply {
            addActionListener {
                CopyPasteManager.getInstance().setContents(StringSelection(response.userCode))
            }
        }
        val codeRow = JPanel(BorderLayout(JBUI.scale(8), 0))
        codeRow.add(codeLabel, BorderLayout.CENTER)
        codeRow.add(copyBtn,   BorderLayout.EAST)

        statusLabel = JBLabel("Waiting for authorization…")

        centre.add(urlRow)
        centre.add(Box.createVerticalStrut(JBUI.scale(8)))
        centre.add(codeRow)
        centre.add(Box.createVerticalStrut(JBUI.scale(8)))
        centre.add(statusLabel)

        root.add(centre, BorderLayout.CENTER)
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
                        is GitHubAuthService.PollResult.Success -> {
                            authService.saveAuthentication(r.token)
                            authenticated = true
                            ApplicationManager.getApplication().invokeLater {
                                close(OK_EXIT_CODE)
                            }
                            return@Thread
                        }
                        is GitHubAuthService.PollResult.Pending  -> { /* keep polling */ }
                        is GitHubAuthService.PollResult.Expired  -> {
                            updateStatus("Code expired. Please restart the sign-in flow.")
                            return@Thread
                        }
                        is GitHubAuthService.PollResult.Error    -> {
                            updateStatus("Error: ${r.message}")
                            return@Thread
                        }
                    }
                } catch (e: Exception) {
                    LOG.warn("Polling error", e)
                    updateStatus("Network error: ${e.message}")
                    return@Thread
                }
            }
            updateStatus("Code expired. Please restart the sign-in flow.")
        }.also {
            it.isDaemon = true
            it.start()
        }
    }

    private fun updateStatus(text: String) {
        ApplicationManager.getApplication().invokeLater {
            if (::statusLabel.isInitialized) statusLabel.text = text
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
            LOG.warn("Could not open browser automatically", e)
        }
    }
}

