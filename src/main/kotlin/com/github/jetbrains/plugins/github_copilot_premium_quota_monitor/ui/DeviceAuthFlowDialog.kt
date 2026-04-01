package com.github.jetbrains.plugins.github_copilot_premium_quota_monitor.ui

import com.github.jetbrains.plugins.github_copilot_premium_quota_monitor.i18n.Messages
import com.github.jetbrains.plugins.github_copilot_premium_quota_monitor.services.AuthService
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Desktop
import java.awt.Dimension
import java.awt.Font
import java.awt.datatransfer.StringSelection
import java.net.URI
import javax.swing.*
import javax.swing.border.CompoundBorder

/**
 * Modal dialog that guides the user through the GitHub OAuth Device Flow.
 *
 * Layout:
 *
 * - Polls GitHub in a background daemon thread; closes itself on success.
 * - Check [authenticated] after [show] returns.
 */
class DeviceAuthFlowDialog(
    project: Project?,
    private val response: AuthService.DeviceCodeResponse,
) : DialogWrapper(project) {

    companion object {
        private val LOG = Logger.getInstance(DeviceAuthFlowDialog::class.java)
        private const val COPY_FEEDBACK_DELAY_MS = 2_000
        /** Theme-aware background for code / URL cards. */
        private val CARD_BG = JBColor(Color(0xF3F4F6), Color(0x2B2D30))
    }

    private val authService = AuthService.getInstance()

    /** True when the device flow completed successfully and the token was saved. */
    var authenticated: Boolean = false
        private set

    // Mutable UI state
    private var statusLabel: JBLabel? = null
    private var countdownLabel: JBLabel? = null
    private var secondsRemaining = response.expiresIn

    @Volatile
    private var pollingThread: Thread? = null

    /**
     * Swing timer that decrements the countdown every second on the EDT.
     * Started after [init] to ensure [countdownLabel] has been created.
     */
    private val countdownTimer = Timer(1_000) {
        secondsRemaining = (secondsRemaining - 1).coerceAtLeast(0)
        countdownLabel?.text = formatCountdown(secondsRemaining)
    }.apply { isRepeats = true }

    // Lifecycle
    init {
        title = Messages.get("deviceauth_dialog_title")
        setCancelButtonText(Messages.get("deviceauth_button_cancel"))
        setOKButtonText(Messages.get("deviceauth_button_ok"))
        init() // builds the UI
        window?.isAlwaysOnTop = false
        startPolling()
        countdownTimer.start()
    }

    // UI construction
    override fun createCenterPanel(): JComponent =
        JPanel(BorderLayout(0, JBUI.scale(16))).apply {
            border = JBUI.Borders.empty(24, 24, 8, 24)
            preferredSize = Dimension(JBUI.scale(560), JBUI.scale(330))
            add(buildHeader(), BorderLayout.NORTH)
            add(buildSteps(),  BorderLayout.CENTER)
        }

    /** Header: bold title + subtitle + horizontal divider. */
    private fun buildHeader(): JComponent =
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(JBLabel(Messages.get("deviceauth_dialog_header")).apply {
                font = font.deriveFont(Font.BOLD, 15.0f)
                alignmentX = Component.LEFT_ALIGNMENT
            })
            add(Box.createVerticalStrut(JBUI.scale(4)))
            add(JBLabel(Messages.get("deviceauth_dialog_subtitle")).apply {
                font = font.deriveFont(Font.PLAIN, 12.0f)
                foreground = UIUtil.getContextHelpForeground()
                alignmentX = Component.LEFT_ALIGNMENT
            })
            add(Box.createVerticalStrut(JBUI.scale(16)))
            add(JSeparator().apply {
                alignmentX  = Component.LEFT_ALIGNMENT
                maximumSize = Dimension(Int.MAX_VALUE, 1)
            })
        }

    /** Body: two numbered steps + status/countdown row. */
    private fun buildSteps(): JComponent =
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(Box.createVerticalStrut(JBUI.scale(16)))
            // Step 1: copy the one-time code
            add(stepHeading(1, Messages.get("deviceauth_step2")))
            add(Box.createVerticalStrut(JBUI.scale(8)))
            add(buildCodeCard())
            add(Box.createVerticalStrut(JBUI.scale(16)))
            // Step 2: open browser and paste the code
            add(stepHeading(2, Messages.get("deviceauth_step1")))
            add(Box.createVerticalStrut(JBUI.scale(8)))
            add(buildUrlCard())
            add(Box.createVerticalStrut(JBUI.scale(14)))
            // Status row
            add(buildStatusRow())
        }

    /**
     * Numbered step heading, e.g. "1. Copy and enter this one-time code..."
     * Uses HTML so the step number can be bold without affecting the rest.
     */
    private fun stepHeading(number: Int, text: String): JBLabel =
        JBLabel("<html><b>$number.</b>&nbsp; $text</html>").apply {
            font = font.deriveFont(Font.PLAIN, 12.0f)
            alignmentX  = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }

    /**
     * Card that shows the device code in a large monospaced font
     * with a "Copy Code" button on the right.
     * Provides brief visual feedback ("✓ Copied!") after copying.
     */
    private fun buildCodeCard(): JPanel {
        val codeLabel = JBLabel(response.userCode).apply {
            font = Font(Font.MONOSPACED, Font.BOLD, JBUI.scale(22))
            horizontalAlignment = SwingConstants.LEFT
            isOpaque = false
        }
        val copyButton = JButton(Messages.get("deviceauth_copy")).apply {
            icon         = AllIcons.Actions.Copy
            isFocusable  = false
            addActionListener { handleCopy(this) }
        }
        return card(JBUI.scale(12), JBUI.scale(16)).apply {
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(56))
            add(codeLabel,  BorderLayout.CENTER)
            add(copyButton, BorderLayout.EAST)
        }
    }

    /**
     * Card that shows the verification URL with an "Open in Browser" button.
     */
    private fun buildUrlCard(): JPanel {
        val urlLabel = JBLabel(response.verificationUri).apply {
            font       = font.deriveFont(Font.PLAIN, 12.0f)
            isOpaque   = false
        }
        val openButton = JButton(Messages.get("deviceauth_open_url")).apply {
            icon        = AllIcons.Ide.External_link_arrow
            isFocusable = false
            addActionListener { openBrowser() }
        }
        // Use same vertical padding and maximum height as the code card so the
        // "Open in Browser" button has the same visual height as "Copy Code".
        return card(JBUI.scale(12), JBUI.scale(16)).apply {
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(56))
            add(urlLabel,   BorderLayout.CENTER)
            add(openButton, BorderLayout.EAST)
        }
    }

    /** Status text on the left, countdown on the right. */
    private fun buildStatusRow(): JPanel {
        statusLabel = JBLabel(Messages.get("deviceauth_waiting_auth")).apply {
            icon       = AllIcons.Process.ProgressResume
            font       = font.deriveFont(Font.PLAIN, 11.0f)
            foreground = UIUtil.getContextHelpForeground()
        }
        countdownLabel = JBLabel(formatCountdown(secondsRemaining)).apply {
            font       = font.deriveFont(Font.PLAIN, 11.0f)
            foreground = UIUtil.getContextHelpForeground()
        }
        return JPanel(BorderLayout()).apply {
            isOpaque    = false
            alignmentX  = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(20))
            add(statusLabel!!,   BorderLayout.WEST)
            add(countdownLabel!!, BorderLayout.EAST)
        }
    }

    /**
     * Creates a themed card panel (rounded border + inset padding).
     * [vPad] and [hPad] are already scaled values.
     */
    private fun card(vPad: Int, hPad: Int): JPanel =
        JPanel(BorderLayout(JBUI.scale(12), 0)).apply {
            background  = CARD_BG
            border      = CompoundBorder(
                JBUI.Borders.customLine(JBColor.border(), 1),
                JBUI.Borders.empty(vPad, hPad),
            )
            alignmentX  = Component.LEFT_ALIGNMENT
        }

    // Copy feedback
    private fun handleCopy(button: JButton) {
        CopyPasteManager.getInstance().setContents(StringSelection(response.userCode))
        button.text = Messages.get("deviceauth_copy_done")
        button.icon = AllIcons.Actions.Commit
        Timer(COPY_FEEDBACK_DELAY_MS) {
            button.text = Messages.get("deviceauth_copy")
            button.icon = AllIcons.Actions.Copy
        }.apply {
            isRepeats = false
            start()
        }
    }

    // Polling
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
                                countdownTimer.stop()
                                close(OK_EXIT_CODE)
                            }
                            return@Thread
                        }
                        is AuthService.PollResult.Pending -> { /* keep polling */ }
                        is AuthService.PollResult.Expired -> {
                            updateStatus(Messages.get("deviceauth_code_expired"), error = true)
                            return@Thread
                        }
                        is AuthService.PollResult.Error -> {
                            updateStatus(Messages.format("deviceauth_error_with_message", r.message), error = true)
                            return@Thread
                        }
                    }
                } catch (e: Exception) {
                    LOG.warn("Polling error", e)
                    updateStatus(Messages.format("deviceauth_network_error_prefix", e.message), error = true)
                    return@Thread
                }
            }
            updateStatus(Messages.get("deviceauth_code_expired"), error = true)
        }.apply {
            isDaemon = true
            start()
        }
    }

    private fun updateStatus(text: String, error: Boolean) {
        ApplicationManager.getApplication().invokeLater {
            countdownTimer.stop()
            statusLabel?.apply {
                this.text      = text
                this.icon      = if (error) AllIcons.General.Error else AllIcons.Process.ProgressResume
                this.foreground = if (error) JBColor.RED else UIUtil.getContextHelpForeground()
            }
            countdownLabel?.text = ""
        }
    }

    // Actions
    override fun doCancelAction() {
        pollingThread?.interrupt()
        countdownTimer.stop()
        super.doCancelAction()
    }

    private fun openBrowser() {
        try {
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI(response.verificationUri))
        } catch (e: Exception) {
            LOG.warn("Failed to open browser", e)
        }
    }

    // Utilities
    private fun formatCountdown(seconds: Int): String {
        val m = seconds / 60
        val s = seconds % 60
        return "$m:${s.toString().padStart(2, '0')}"
    }
}
