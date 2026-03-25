package com.github.intellij.plugins.github_copilot_quota_monitor.startup

import com.github.intellij.plugins.github_copilot_quota_monitor.i18n.Messages
import com.github.intellij.plugins.github_copilot_quota_monitor.services.AuthService
import com.github.intellij.plugins.github_copilot_quota_monitor.ui.DeviceAuthFlowDialog
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.ui.Messages as UiMessages

/**
 * Startup activity that shows the sign-in dialog on the first IDE run
 * if the user has not yet authenticated.
 *
 * Registered via the `<projectActivity>` extension point in plugin.xml.
 */
class SignInStartupActivity : ProjectActivity {

    companion object {
        private val LOG = Logger.getInstance(SignInStartupActivity::class.java)
    }

    override suspend fun execute(project: Project) {
        if (!AuthService.getInstance().isAuthenticated()) {
            ApplicationManager.getApplication().invokeLater {
                startSignInFlow(project)
            }
        }
    }

    private fun startSignInFlow(project: Project) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val deviceCode = AuthService.getInstance().requestDeviceCode()
                ApplicationManager.getApplication().invokeLater {
                    DeviceAuthFlowDialog(project, deviceCode).show()
                }
            } catch (e: Exception) {
                LOG.warn("Failed to initiate sign-in flow", e)
                ApplicationManager.getApplication().invokeLater {
                    UiMessages.showInfoMessage(
                        project,
                        Messages.format("startup_startup_fail_msg", e.message),
                        Messages.get("startup_startup_fail_title"),
                    )
                }
            }
        }
    }
}
