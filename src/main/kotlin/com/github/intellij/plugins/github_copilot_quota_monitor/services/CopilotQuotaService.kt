package com.github.intellij.plugins.github_copilot_quota_monitor.services

import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.runBlocking
import org.jetbrains.plugins.github.authentication.accounts.GHAccountManager
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Application-level service responsible for fetching and caching
 * the GitHub Copilot premium quota from the GitHub API.
 *
 * Authentication is fully delegated to the official GitHub Copilot plugin
 * via IntelliJ's GitHub account manager (org.jetbrains.plugins.github).
 */
@Service(Service.Level.APP)
class CopilotQuotaService {

    companion object {
        private val LOG = Logger.getInstance(CopilotQuotaService::class.java)

        /** Cache duration: 5 minutes */
        private const val CACHE_DURATION_MS = 5 * 60 * 1000L

        /**
         * GitHub Copilot internal API endpoint that returns user plan/quota data.
         * This is the same endpoint used by the official Copilot plugin.
         */
        private const val COPILOT_USER_API_URL = "https://api.github.com/copilot_internal/user"

        @JvmStatic
        fun getInstance(): CopilotQuotaService = service()
    }

    // ── Domain model ──────────────────────────────────────────────────────────

    data class QuotaInfo(
        val used: Int,
        val remaining: Int,
        val total: Int
    ) {
        val percentUsed: Double
            get() = if (total > 0) used.toDouble() / total * 100.0 else 0.0
    }

    sealed class QuotaResult {
        /** Initial state while the first fetch is in progress. */
        object Loading : QuotaResult()

        /** Premium quota data is available. */
        data class Available(val quota: QuotaInfo) : QuotaResult()

        /** The user's plan has no premium quota limit (unlimited). */
        object Unlimited : QuotaResult()

        /** No GitHub account found or token is invalid. */
        data class NoAccount(val message: String) : QuotaResult()

        /** An unexpected error occurred. */
        data class Error(val message: String) : QuotaResult()
    }

    // ── State ────────────────────────────────────────────���────────────────────

    private val cachedResultRef = AtomicReference<QuotaResult>(QuotaResult.Loading)
    private val lastFetchTime = AtomicLong(0L)
    private val isFetching = AtomicReference(false)

    val cachedResult: QuotaResult get() = cachedResultRef.get()

    fun shouldRefresh(): Boolean =
        System.currentTimeMillis() - lastFetchTime.get() > CACHE_DURATION_MS

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Triggers an asynchronous quota refresh on a background thread.
     * Duplicate calls while a fetch is already in progress are ignored.
     * [onComplete] is invoked on the EDT with the new result.
     */
    fun refreshAsync(onComplete: (QuotaResult) -> Unit = {}) {
        if (!isFetching.compareAndSet(false, true)) return

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val result = fetchQuota()
                cachedResultRef.set(result)
                lastFetchTime.set(System.currentTimeMillis())
                ApplicationManager.getApplication().invokeLater { onComplete(result) }
            } finally {
                isFetching.set(false)
            }
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun fetchQuota(): QuotaResult {
        val token = resolveGitHubToken()
            ?: return QuotaResult.NoAccount(
                "No GitHub account found. Please sign in via the GitHub Copilot plugin."
            )

        return try {
            val conn = (URL(COPILOT_USER_API_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "token $token")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "github-copilot-quota-monitor-ij/1.0")
                connectTimeout = 10_000
                readTimeout = 10_000
            }

            when (val code = conn.responseCode) {
                200 -> parseQuota(conn.inputStream.bufferedReader().readText())
                401, 403 -> QuotaResult.NoAccount("GitHub token is invalid or lacks Copilot access (HTTP $code).")
                else -> QuotaResult.Error("GitHub API returned HTTP $code.")
            }
        } catch (e: Exception) {
            LOG.warn("Failed to fetch GitHub Copilot quota", e)
            QuotaResult.Error(e.message ?: "Network error")
        }
    }

    /**
     * Retrieves the GitHub OAuth token from IntelliJ's GitHub account manager,
     * which is populated when the user authenticates through the GitHub Copilot
     * plugin or any other GitHub integration in the IDE.
     */
    private fun resolveGitHubToken(): String? {
        return try {
            val accountManager = service<GHAccountManager>()
            val account = accountManager.accountsState.value.firstOrNull() ?: return null
            runBlocking { accountManager.findCredentials(account) }
        } catch (e: Exception) {
            LOG.warn("Failed to obtain GitHub token from account manager", e)
            null
        }
    }

    /**
     * Parses the JSON response from the Copilot user API.
     * Supports several field-name variants that GitHub may use across API versions.
     */
    private fun parseQuota(json: String): QuotaResult {
        return try {
            val root = JsonParser.parseString(json).asJsonObject

            // Nested quota objects (v1 or v2 formats)
            val quotaObj = root.getAsJsonObject("quota")
                ?: root.getAsJsonObject("premium_interactions")
                ?: root.getAsJsonObject("premium_requests")

            if (quotaObj != null) {
                val total = quotaObj.get("monthly_maximum")?.asInt
                    ?: quotaObj.get("maximum")?.asInt
                    ?: quotaObj.get("total")?.asInt
                    ?: quotaObj.get("limit")?.asInt

                if (total != null) {
                    val used = quotaObj.get("used")?.asInt ?: 0
                    val remaining = quotaObj.get("remaining")?.asInt ?: (total - used)
                    return QuotaResult.Available(QuotaInfo(used = used, remaining = remaining, total = total))
                }
            }

            // Flat fields (alternative format)
            val maxFlat = root.get("premium_requests_maximum")?.asInt
                ?: root.get("monthly_maximum_premium_requests")?.asInt
                ?: root.get("premium_requests_monthly_limit")?.asInt
            if (maxFlat != null) {
                val used = root.get("premium_requests_used")?.asInt ?: 0
                return QuotaResult.Available(QuotaInfo(used = used, remaining = maxFlat - used, total = maxFlat))
            }

            // No quota fields found → user is probably on an unlimited plan
            QuotaResult.Unlimited
        } catch (e: Exception) {
            LOG.warn("Failed to parse Copilot quota response", e)
            QuotaResult.Error("Failed to parse quota data: ${e.message}")
        }
    }
}

