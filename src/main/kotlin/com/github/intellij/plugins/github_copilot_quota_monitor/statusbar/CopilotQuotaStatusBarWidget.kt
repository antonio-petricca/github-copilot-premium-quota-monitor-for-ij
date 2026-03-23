package com.github.intellij.plugins.github_copilot_quota_monitor.statusbar

import com.github.intellij.plugins.github_copilot_quota_monitor.services.CopilotQuotaService
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.util.Consumer
import java.awt.event.MouseEvent
import javax.swing.Timer

/**
 * Status bar widget that shows the remaining GitHub Copilot premium quota.
 *
 * - Refreshes automatically every 5 minutes.
 * - Refreshes on click.
 * - Delegates all data fetching to [CopilotQuotaService].
 */
class CopilotQuotaStatusBarWidget(
    @Suppress("UNUSED_PARAMETER") project: Project
) : StatusBarWidget, StatusBarWidget.TextPresentation {

    companion object {
        const val WIDGET_ID = "GitHubCopilotQuotaWidget"
    }

    private var statusBar: StatusBar? = null

    /** Timer that triggers a background refresh every 5 minutes. */
    private val refreshTimer = Timer(5 * 60 * 1_000) { _ -> refresh() }.apply {
        isRepeats = true
        isCoalesce = true
    }

    // ── StatusBarWidget ───────────────────────────────────────────────────────

    override fun ID(): String = WIDGET_ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        refresh()
        refreshTimer.start()
    }

    override fun dispose() {
        refreshTimer.stop()
        statusBar = null
    }

    // ── TextPresentation ──────────────────────────────────────────────────────

    override fun getAlignment(): Float = 0f

    override fun getText(): String = when (val r = quotaService().cachedResult) {
        is CopilotQuotaService.QuotaResult.Loading    -> "⊙ Copilot"
        is CopilotQuotaService.QuotaResult.Available  -> "⊙ ${r.quota.remaining}/${r.quota.total}"
        is CopilotQuotaService.QuotaResult.Unlimited  -> "⊙ Copilot ∞"
        is CopilotQuotaService.QuotaResult.NoAccount  -> "⊙ Copilot ⚠"
        is CopilotQuotaService.QuotaResult.Error      -> "⊙ Copilot ✗"
    }

    override fun getTooltipText(): String = when (val r = quotaService().cachedResult) {
        is CopilotQuotaService.QuotaResult.Loading ->
            "GitHub Copilot Premium Quota — loading…"

        is CopilotQuotaService.QuotaResult.Available -> {
            val q = r.quota
            "GitHub Copilot — Premium quota\n" +
            "  Remaining : ${q.remaining} / ${q.total}\n" +
            "  Used      : ${q.used}  (${String.format("%.1f", q.percentUsed)} %)\n" +
            "  Click to refresh"
        }

        is CopilotQuotaService.QuotaResult.Unlimited ->
            "GitHub Copilot — Premium quota: unlimited for your plan"

        is CopilotQuotaService.QuotaResult.NoAccount ->
            "GitHub Copilot — ⚠ Not signed in.\n" +
            "  Open Settings → Tools → GitHub Copilot Quota Monitor to sign in."

        is CopilotQuotaService.QuotaResult.Error ->
            "GitHub Copilot — ✗ Error: ${r.message}"
    }

    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer { refresh() }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun quotaService(): CopilotQuotaService = service()

    private fun refresh() {
        quotaService().refreshAsync { statusBar?.updateWidget(ID()) }
    }
}

