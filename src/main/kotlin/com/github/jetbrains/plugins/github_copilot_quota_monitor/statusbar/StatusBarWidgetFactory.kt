package com.github.jetbrains.plugins.github_copilot_quota_monitor.statusbar

import com.github.jetbrains.plugins.github_copilot_quota_monitor.i18n.Messages
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory

/**
 * Factory that registers [CopilotQuotaStatusBarWidget] with IntelliJ's
 * status bar widget system.
 */
class StatusBarWidgetFactory : StatusBarWidgetFactory {

    override fun getId(): String = CopilotQuotaStatusBarWidget.WIDGET_ID

    override fun getDisplayName(): String = Messages.get("statusbarfactory_display_name")

    override fun isAvailable(project: Project): Boolean = true

    override fun createWidget(project: Project): StatusBarWidget =
        CopilotQuotaStatusBarWidget(project)

    override fun disposeWidget(widget: StatusBarWidget) = Disposer.dispose(widget)

    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}

