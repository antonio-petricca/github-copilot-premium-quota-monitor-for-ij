package com.github.intellij.plugins.github_copilot_quota_monitor.services

import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Application-level service responsible for fetching and caching
 * the GitHub Copilot premium quota from the GitHub API.
 *
 * Authentication is handled by [AuthService] via OAuth Device Flow.
 * No dependency on the GitHub Copilot plugin or org.jetbrains.plugins.github.
 */
@Service(Service.Level.APP)
class Service {

    companion object {
        private val LOG = Logger.getInstance(Service::class.java)

        /** Cache duration: 5 minutes */
        private const val CACHE_DURATION_MS = 5 * 60 * 1000L

        /**
         * GitHub Copilot internal API endpoint that returns user plan/quota data.
         */
        private const val COPILOT_USER_API_URL = "https://api.github.com/copilot_internal/user"

        @JvmStatic
        fun getInstance(): com.github.intellij.plugins.github_copilot_quota_monitor.services.Service = service()
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

        /** No GitHub account found or token is invalid/missing. */
        data class NoAccount(val message: String) : QuotaResult()

        /** An unexpected error occurred. */
        data class Error(val message: String) : QuotaResult()
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private val cachedResultRef = AtomicReference<QuotaResult>(QuotaResult.Loading)
    private val lastFetchTime   = AtomicLong(0L)
    private val isFetching      = AtomicReference(false)

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
        val token = AuthService.getInstance().getToken()
            ?: return QuotaResult.NoAccount(
                "Not signed in. Open Settings → Tools → GitHub Copilot Quota Monitor to sign in."
            )

        return try {
            val conn = (URI.create(COPILOT_USER_API_URL).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization",          "token $token")
                setRequestProperty("Accept",                 "application/json")
                setRequestProperty("User-Agent",             "github-copilot-quota-monitor-ij/1.0")
                setRequestProperty("Copilot-Integration-Id", "JetBrainsIDE")
                connectTimeout = 10_000
                readTimeout    = 10_000
            }

            when (val code = conn.responseCode) {
                200 -> parseQuota(conn.inputStream.bufferedReader().readText())
                401, 403 -> {
                    // Token is invalid or revoked — clear it so the user knows to re-authenticate
                    AuthService.getInstance().clearAuthentication()
                    QuotaResult.NoAccount(
                        "GitHub token is invalid or expired (HTTP $code). " +
                        "Open Settings → Tools → GitHub Copilot Quota Monitor to sign in again."
                    )
                }
                else -> QuotaResult.Error("GitHub API returned HTTP $code.")
            }
        } catch (e: Exception) {
            LOG.warn("Failed to fetch GitHub Copilot quota", e)
            QuotaResult.Error(e.message ?: "Network error")
        }
    }

    /**
     * Parses the JSON response from the Copilot user API.
     *
     * Handles several field-name layouts used across GitHub API versions:
     *
     * Layout A — limited_user_quotas (current):
     * ```json
     * { "limited_user_quotas": { "premium_interactions": { "used": 50, "limit": 300 } } }
     * ```
     *
     * Layout B — nested quota object:
     * ```json
     * { "quota": { "premium_requests": { "used": 50, "remaining": 250, "monthly_maximum": 300 } } }
     * ```
     *
     * Layout C — flat fields:
     * ```json
     * { "premium_requests_maximum": 300, "premium_requests_used": 50 }
     * ```
     */
    private fun parseQuota(json: String): QuotaResult {
        return try {
            val root = JsonParser.parseString(json).asJsonObject

            // ── Layout A: limited_user_quotas (current GitHub API format) ────
            val limitedQuotas = root.getAsJsonObject("limited_user_quotas")
            if (limitedQuotas != null) {
                val interactions = limitedQuotas.getAsJsonObject("premium_interactions")
                    ?: limitedQuotas.getAsJsonObject("completions")
                if (interactions != null) {
                    val limit = interactions["limit"]?.asInt ?: interactions["monthly_maximum"]?.asInt
                    if (limit != null) {
                        val used      = interactions["used"]?.asInt ?: 0
                        val remaining = interactions["remaining"]?.asInt ?: (limit - used)
                        return QuotaResult.Available(QuotaInfo(used = used, remaining = remaining, total = limit))
                    }
                }
            }

            // ── Layout B: nested quota / premium_interactions / premium_requests ──
            val quotaObj = root.getAsJsonObject("quota")
                ?: root.getAsJsonObject("premium_interactions")
                ?: root.getAsJsonObject("premium_requests")
            if (quotaObj != null) {
                val total = quotaObj["monthly_maximum"]?.asInt
                    ?: quotaObj["maximum"]?.asInt
                    ?: quotaObj["total"]?.asInt
                    ?: quotaObj["limit"]?.asInt
                if (total != null) {
                    val used      = quotaObj["used"]?.asInt ?: 0
                    val remaining = quotaObj["remaining"]?.asInt ?: (total - used)
                    return QuotaResult.Available(QuotaInfo(used = used, remaining = remaining, total = total))
                }
            }

            // ── Layout C: flat fields ────────────────────────────────────────
            val maxFlat = root["premium_requests_maximum"]?.asInt
                ?: root["monthly_maximum_premium_requests"]?.asInt
                ?: root["premium_requests_monthly_limit"]?.asInt
            if (maxFlat != null) {
                val used = root["premium_requests_used"]?.asInt ?: 0
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

