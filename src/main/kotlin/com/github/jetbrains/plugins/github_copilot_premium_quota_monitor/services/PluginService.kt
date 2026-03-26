package com.github.jetbrains.plugins.github_copilot_premium_quota_monitor.services

import com.github.jetbrains.plugins.github_copilot_premium_quota_monitor.i18n.Messages
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Application service responsible for fetching and caching GitHub Copilot
 * premium quota data.  Authentication is delegated to [AuthService].
 */
@Service(Service.Level.APP)
class PluginService {

    companion object {
        private val LOG = Logger.getInstance(PluginService::class.java)
        private const val COPILOT_USER_API_URL = "https://api.github.com/copilot_internal/user"

        @JvmStatic
        fun getInstance(): PluginService =
            ApplicationManager.getApplication().getService(PluginService::class.java)
    }

    init {
        // Refresh quota immediately whenever the user signs in or signs out.
        try {
            ApplicationManager.getApplication().messageBus.connect()
                .subscribe(AuthStateNotifier.AUTH_TOPIC, object : AuthStateListener {
                    override fun authStateChanged() {
                        try { refreshQuota() } catch (_: Exception) { /* best-effort */ }
                    }
                })
        } catch (e: Exception) {
            LOG.warn("Failed to subscribe to auth state changes", e)
        }
    }

    // ── Domain types ──────────────────────────────────────────────────────────

    // percentRemaining, optional renewalDate
    // and optional numeric quotaRemaining (the raw remaining units, e.g. interactions).
    data class QuotaInfo(
        val percentRemaining: Double,
        val renewalDate: String? = null,
        val quotaRemaining: Double? = null,
    ) {
        constructor(used: Int, remaining: Int, total: Int, renewalDate: String? = null) : this(
            if (total > 0) remaining.toDouble() / total * 100.0 else 0.0,
            renewalDate,
            remaining.toDouble()
        )
    }

    sealed class QuotaResult {
        data object Loading                         : QuotaResult()
        data class  Available(val quota: QuotaInfo) : QuotaResult()
        data object Unlimited                       : QuotaResult()
        data class  NoAccount(val message: String)  : QuotaResult()
        data class  Error(val message: String)      : QuotaResult()
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private val cachedResultRef = AtomicReference<QuotaResult>(QuotaResult.Loading)
    private val lastFetchTime   = AtomicLong(0L)
    private val isFetching      = AtomicBoolean(false)

    val cachedResult: QuotaResult
        get() = cachedResultRef.get()

    // ── Public API ────────────────────────────────────────────────────────────

    fun refreshQuota(onComplete: (QuotaResult) -> Unit = {}) {
        if (!isFetching.compareAndSet(false, true)) return

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val result = fetchQuota()
                cachedResultRef.set(result)
                lastFetchTime.set(System.currentTimeMillis())

                try {
                    ApplicationManager.getApplication().messageBus
                        .syncPublisher(QuotaNotifier.QUOTA_TOPIC)
                        .quotaUpdated(result)
                } catch (_: Exception) { /* best-effort */ }

                ApplicationManager.getApplication().invokeLater { onComplete(result) }
            } finally {
                isFetching.set(false)
            }
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun fetchQuota(): QuotaResult {
        val token = AuthService.getInstance().getToken()
            ?: return QuotaResult.NoAccount(Messages.get("general_not_signed_in"))

        return try {
            val conn = (URI.create(COPILOT_USER_API_URL).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization",         "token $token")
                setRequestProperty("Accept",                "application/json")
                setRequestProperty("User-Agent",            "github-copilot-quota-monitor-ij/1.0")
                setRequestProperty("Copilot-Integration-Id", "JetBrainsIDE")
                connectTimeout = 10_000
                readTimeout    = 10_000
            }

            when (val responseCode = conn.responseCode) {
                HttpURLConnection.HTTP_OK -> parseQuota(conn.inputStream.bufferedReader().readText())

                HttpURLConnection.HTTP_UNAUTHORIZED,
                HttpURLConnection.HTTP_FORBIDDEN -> {
                    AuthService.getInstance().clearAuthentication()
                    QuotaResult.NoAccount(Messages.format("general_token_invalid", responseCode))
                }

                else -> QuotaResult.Error(Messages.format("general_api_http", responseCode))
            }
        } catch (e: Exception) {
            LOG.warn("Failed to fetch Copilot quota", e)
            QuotaResult.Error(e.message ?: Messages.get("general_network_error"))
        }
    }

    private fun parseQuota(json: String): QuotaResult {
        return try {
            val root = JsonParser.parseString(json).asJsonObject

            // Read optional top-level quota_reset_date
            val quotaReset = root["quota_reset_date"]?.asString

            // Modern API format: quota_snapshots.premium_interactions.percent_remaining
            root.getAsJsonObject("quota_snapshots")
                ?.getAsJsonObject("premium_interactions")
                ?.let { premium ->
                    val pct = premium["percent_remaining"]?.asDouble
                    if (pct != null) {
                        val unlimited = premium["unlimited"]?.asBoolean ?: false
                        val quotaRem = premium["quota_remaining"]?.asDouble
                        return if (unlimited)
                            QuotaResult.Unlimited
                        else
                            QuotaResult.Available(QuotaInfo(pct, quotaReset, quotaRem))
                    }
                }

            // Legacy format: limited_user_quotas
            root.getAsJsonObject("limited_user_quotas")?.let { limitedQuotas ->
                val interactions = limitedQuotas.getAsJsonObject("premium_interactions")
                    ?: limitedQuotas.getAsJsonObject("completions")
                interactions?.let {
                    val limit = it["limit"]?.asInt ?: it["monthly_maximum"]?.asInt
                    if (limit != null) {
                        val used      = it["used"]?.asInt      ?: 0
                        val remaining = it["remaining"]?.asInt ?: (limit - used)
                        return QuotaResult.Available(QuotaInfo(used, remaining, limit, quotaReset))
                    }
                }
            }

            // Older nested format: quota / premium_interactions / premium_requests
            (root.getAsJsonObject("quota")
                ?: root.getAsJsonObject("premium_interactions")
                ?: root.getAsJsonObject("premium_requests"))
                ?.let { quotaObj ->
                    val total = quotaObj["monthly_maximum"]?.asInt
                        ?: quotaObj["maximum"]?.asInt
                        ?: quotaObj["total"]?.asInt
                        ?: quotaObj["limit"]?.asInt
                    if (total != null) {
                        val used      = quotaObj["used"]?.asInt      ?: 0
                        val remaining = quotaObj["remaining"]?.asInt ?: (total - used)
                        return QuotaResult.Available(QuotaInfo(used, remaining, total, quotaReset))
                    }
                }

            // Flat field format
            val maxFlat = root["premium_requests_maximum"]?.asInt
                ?: root["monthly_maximum_premium_requests"]?.asInt
                ?: root["premium_requests_monthly_limit"]?.asInt
            if (maxFlat != null) {
                val used = root["premium_requests_used"]?.asInt ?: 0
                return QuotaResult.Available(QuotaInfo(used, maxFlat - used, maxFlat, quotaReset))
            }

            QuotaResult.Unlimited
        } catch (e: Exception) {
            LOG.warn("Failed to parse quota response", e)
            QuotaResult.Error(Messages.format("general_parse_failed", e.message))
        }
    }
}
