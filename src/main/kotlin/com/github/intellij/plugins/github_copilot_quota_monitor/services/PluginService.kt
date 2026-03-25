package com.github.intellij.plugins.github_copilot_quota_monitor.services

import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import java.text.MessageFormat
import java.util.*
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Application service for fetching and caching GitHub Copilot premium quota data.
 * Authentication is handled via [AuthService] using OAuth Device Flow.
 */
@Service(Service.Level.APP)
class PluginService {

    companion object {
        private val LOG = Logger.getInstance(PluginService::class.java)
        private const val COPILOT_USER_API_URL = "https://api.github.com/copilot_internal/user"
        // Use centralized Messages helper

        @JvmStatic
        fun getInstance(): PluginService = ApplicationManager.getApplication().getService(PluginService::class.java)
    }

    data class QuotaInfo(val used: Int, val remaining: Int, val total: Int) {
        val percentUsed: Double
            get() = if (total > 0) used.toDouble() / total * 100.0 else 0.0
    }

    sealed class QuotaResult {
        object Loading : QuotaResult()
        data class Available(val quota: QuotaInfo) : QuotaResult()
        object Unlimited : QuotaResult()
        data class NoAccount(val message: String) : QuotaResult()
        data class Error(val message: String) : QuotaResult()
    }

    private val cachedResultRef = AtomicReference<QuotaResult>(QuotaResult.Loading)
    private val lastFetchTime = AtomicLong(0L)
    private val isFetching = AtomicBoolean(false)

    val cachedResult: QuotaResult
        get() = cachedResultRef.get()


    fun refreshQuota(onComplete: (QuotaResult) -> Unit = {}) {
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

    private fun fetchQuota(): QuotaResult {
        val token = AuthService.getInstance().getToken()
            ?: return QuotaResult.NoAccount(
                com.github.intellij.plugins.github_copilot_quota_monitor.i18n.Messages.get("not_signed_in")
            )
        return try {
            val conn = (URI.create(COPILOT_USER_API_URL).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "token $token")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "github-copilot-quota-monitor-ij/1.0")
                setRequestProperty("Copilot-Integration-Id", "JetBrainsIDE")
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            when (val code = conn.responseCode) {
                HttpURLConnection.HTTP_OK -> parseQuota(conn.inputStream.bufferedReader().readText())
                HttpURLConnection.HTTP_UNAUTHORIZED, HttpURLConnection.HTTP_FORBIDDEN -> {
                    AuthService.getInstance().clearAuthentication()
                    QuotaResult.NoAccount(
                        com.github.intellij.plugins.github_copilot_quota_monitor.i18n.Messages.format("token_invalid", code)
                    )
                }
                else -> QuotaResult.Error(com.github.intellij.plugins.github_copilot_quota_monitor.i18n.Messages.format("api_http", code))
            }
        } catch (e: Exception) {
            LOG.warn("Failed to fetch GitHub Copilot quota", e)
            QuotaResult.Error(e.message ?: com.github.intellij.plugins.github_copilot_quota_monitor.i18n.Messages.get("network_error"))
        }
    }

    private fun parseQuota(json: String): QuotaResult {
        return try {
            val root = JsonParser.parseString(json).asJsonObject
            root.getAsJsonObject("limited_user_quotas")?.let { limitedQuotas ->
                val interactions = limitedQuotas.getAsJsonObject("premium_interactions")
                    ?: limitedQuotas.getAsJsonObject("completions")
                interactions?.let {
                    val limit = it["limit"]?.asInt ?: it["monthly_maximum"]?.asInt
                    if (limit != null) {
                        val used = it["used"]?.asInt ?: 0
                        val remaining = it["remaining"]?.asInt ?: (limit - used)
                        return QuotaResult.Available(QuotaInfo(used, remaining, limit))
                    }
                }
            }
            root.getAsJsonObject("quota")
                ?: root.getAsJsonObject("premium_interactions")
                ?: root.getAsJsonObject("premium_requests")
            ?.let { quotaObj ->
                val total = quotaObj["monthly_maximum"]?.asInt
                    ?: quotaObj["maximum"]?.asInt
                    ?: quotaObj["total"]?.asInt
                    ?: quotaObj["limit"]?.asInt
                if (total != null) {
                    val used = quotaObj["used"]?.asInt ?: 0
                    val remaining = quotaObj["remaining"]?.asInt ?: (total - used)
                    return QuotaResult.Available(QuotaInfo(used, remaining, total))
                }
            }
            val maxFlat = root["premium_requests_maximum"]?.asInt
                ?: root["monthly_maximum_premium_requests"]?.asInt
                ?: root["premium_requests_monthly_limit"]?.asInt
            if (maxFlat != null) {
                val used = root["premium_requests_used"]?.asInt ?: 0
                return QuotaResult.Available(QuotaInfo(used, maxFlat - used, maxFlat))
            }
            QuotaResult.Unlimited
        } catch (e: Exception) {
            LOG.warn("Failed to parse Copilot quota response", e)
            QuotaResult.Error(com.github.intellij.plugins.github_copilot_quota_monitor.i18n.Messages.format("parse_failed", e.message))
        }
    }
}
