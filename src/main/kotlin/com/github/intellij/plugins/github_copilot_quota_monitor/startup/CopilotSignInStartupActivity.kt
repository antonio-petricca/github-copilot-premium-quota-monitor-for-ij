package com.github.intellij.plugins.github_copilot_quota_monitor.startup

import com.github.intellij.plugins.github_copilot_quota_monitor.services.GitHubAuthService
import com.github.intellij.plugins.github_copilot_quota_monitor.ui.GitHubDeviceFlowDialog
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import javax.swing.JOptionPane

/**
 * Startup activity that shows the sign-in dialog on the first IDE run
 * if the user is not yet authenticated.
 *
 * Registered via plugin.xml extension point: <projectActivity>
 */
class CopilotSignInStartupActivity : ProjectActivity {

    companion object {
        private val LOG = Logger.getInstance(CopilotSignInStartupActivity::class.java)
    }

    override suspend fun execute(project: Project) {
        val auth = GitHubAuthService.getInstance()
        
        // Only show signin dialog if not already authenticated
        if (!auth.isAuthenticated()) {
            ApplicationManager.getApplication().invokeLater {
                showSignInPrompt(project)
            }
        }
    }

    private fun showSignInPrompt(project: Project) {
        // Start device-code request in background
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val deviceCode = GitHubAuthService.getInstance().requestDeviceCode()
                ApplicationManager.getApplication().invokeLater {
                    val dlg = GitHubDeviceFlowDialog(project, deviceCode)
                    dlg.show()
                }
            } catch (e: Exception) {
                LOG.warn("Failed to initiate sign-in flow", e)
                ApplicationManager.getApplication().invokeLater {
                    JOptionPane.showMessageDialog(
                        null,
                        "Failed to start the sign-in flow:\n${e.message}",
                        "GitHub Copilot Authentication",
                        JOptionPane.INFORMATION_MESSAGE
                    )
                }
            }
        }
    }
}

