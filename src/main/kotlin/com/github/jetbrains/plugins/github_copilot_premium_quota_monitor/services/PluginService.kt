package com.github.jetbrains.plugins.github_copilot_premium_quota_monitor.services

import com.github.jetbrains.plugins.github_copilot_premium_quota_monitor.PluginInfo
import com.github.jetbrains.plugins.github_copilot_premium_quota_monitor.i18n.Messages
import com.google.gson.JsonObject
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
 * premium quota data. Authentication is delegated to [AuthService].
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
        val quotaTotal: Double? = null,
    ) {
        /**
         * Convenience constructor that computes [percentRemaining] from raw [remaining] / [total] counts.
         * The [used] count is not stored — only [remaining] and [total] matter for display.
         */
        constructor(remaining: Int, total: Int, renewalDate: String? = null) : this(
            percentRemaining = if (total > 0) (remaining.toDouble() / total * 100.0).coerceAtLeast(0.0) else 0.0,
            renewalDate      = renewalDate,
            quotaRemaining   = remaining.coerceAtLeast(0).toDouble(),
            quotaTotal       = total.toDouble(),
        )
    }

    sealed class QuotaResult {
        data object Loading                         : QuotaResult()
        data class  Available(val quota: QuotaInfo) : QuotaResult()
        data object Unlimited                       : QuotaResult()
        data class  NoAccount(val message: String)  : QuotaResult()
        data class  Error(val message: String)      : QuotaResult()
    }

    // ── State ────────────────────────────────────────────────────────────────

    private val cachedResultRef = AtomicReference<QuotaResult>(QuotaResult.Loading)
    private val lastFetchTime   = AtomicLong(0L)
    private val isFetching      = AtomicBoolean(false)

    val cachedResult: QuotaResult
        get() = cachedResultRef.get()

    // ── Public API ────────────────────────────────────────────────────────────

    fun refreshQuota(onComplete: (QuotaResult) -> Unit = {}) {
        if (!isFetching.compareAndSet(false, true)) {
            LOG.debug("Quota refresh already in progress, skipping")

            return
        }

        LOG.info("Starting quota refresh")

        try {
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    val result = fetchQuota()
                    cachedResultRef.set(result)
                    lastFetchTime.set(System.currentTimeMillis())

                    LOG.info("Quota refresh completed: ${result::class.simpleName}")

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
        } catch (e: Exception) {
            // executeOnPooledThread itself threw (e.g. RejectedExecutionException during shutdown).
            // Reset the flag so future calls are not permanently blocked.
            isFetching.set(false)
            LOG.warn("Failed to schedule quota refresh", e)
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private fun fetchQuota(): QuotaResult {
        val token = AuthService.getInstance().getToken()
            ?: return QuotaResult.NoAccount(Messages.get("general_not_signed_in"))

        return try {
            LOG.debug("Fetching quota from GitHub API")

            val conn = (URI.create(COPILOT_USER_API_URL).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization",         "token $token")
                setRequestProperty("Accept",                "application/json")
                setRequestProperty("User-Agent",            PluginInfo.userAgent)
                setRequestProperty("Copilot-Integration-Id", "JetBrainsIDE")
                connectTimeout = 10_000
                readTimeout    = 10_000
            }

            try {
                when (val responseCode = conn.responseCode) {
                    HttpURLConnection.HTTP_OK -> {
                        LOG.debug("GitHub API returned 200 OK")

                        parseQuota(conn.inputStream.bufferedReader().use { it.readText() })
                    }

                    HttpURLConnection.HTTP_UNAUTHORIZED,
                    HttpURLConnection.HTTP_FORBIDDEN -> {
                        LOG.warn("Quota fetch returned $responseCode - clearing authentication")

                        AuthService.getInstance().clearAuthentication()
                        QuotaResult.NoAccount(Messages.format("general_token_invalid", responseCode))
                    }

                    else -> {
                        LOG.warn("Quota fetch returned unexpected HTTP $responseCode")

                        QuotaResult.Error(Messages.format("general_api_http", responseCode))
                    }
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            LOG.warn("Failed to fetch Copilot quota", e)
            QuotaResult.Error(e.message ?: Messages.get("general_network_error"))
        }
    }

    private fun parseQuota(json: String): QuotaResult {
        return try {
            val root       = JsonParser.parseString(json).asJsonObject
            val quotaReset = root["quota_reset_date"]?.asString

            parseModernFormat(root, quotaReset)
                ?: parseLimitedUserQuotasFormat(root, quotaReset)
                ?: parseNestedQuotaFormat(root, quotaReset)
                ?: parseFlatQuotaFormat(root, quotaReset)
                ?: QuotaResult.Unlimited
        } catch (e: Exception) {
            LOG.warn("Failed to parse quota response", e)
            QuotaResult.Error(Messages.format("general_parse_failed", e.message))
        }
    }

    /** Modern API: `quota_snapshots.premium_interactions.percent_remaining`. */
    private fun parseModernFormat(root: JsonObject, quotaReset: String?): QuotaResult? {
        val premium = root.getAsJsonObject("quota_snapshots")
            ?.getAsJsonObject("premium_interactions") ?: return null
        val pctRaw = premium["percent_remaining"]?.asDouble ?: return null

        if (premium["unlimited"]?.asBoolean == true) return QuotaResult.Unlimited

        val pct       = pctRaw.coerceAtLeast(0.0)
        val quotaRem  = (premium["quota_remaining"]?.asDouble ?: 0.0).coerceAtLeast(0.0)
        val quotaTotal = premium["entitlement"]?.asDouble ?: premium["quota_total"]?.asDouble
        return QuotaResult.Available(QuotaInfo(pct, quotaReset, quotaRem, quotaTotal))
    }

    /** Legacy API: `limited_user_quotas.premium_interactions` or `.completions`. */
    private fun parseLimitedUserQuotasFormat(root: JsonObject, quotaReset: String?): QuotaResult? {
        val interactions = root.getAsJsonObject("limited_user_quotas")?.let { quotas ->
            quotas.getAsJsonObject("premium_interactions")
                ?: quotas.getAsJsonObject("completions")
        } ?: return null

        val limit     = interactions["limit"]?.asInt ?: interactions["monthly_maximum"]?.asInt ?: return null
        val used      = interactions["used"]?.asInt ?: 0
        val remaining = (interactions["remaining"]?.asInt ?: (limit - used)).coerceAtLeast(0)
        return QuotaResult.Available(QuotaInfo(remaining, limit, quotaReset))
    }

    /** Older nested API: top-level `quota`, `premium_interactions`, or `premium_requests` object. */
    private fun parseNestedQuotaFormat(root: JsonObject, quotaReset: String?): QuotaResult? {
        val quotaObj = root.getAsJsonObject("quota")
            ?: root.getAsJsonObject("premium_interactions")
            ?: root.getAsJsonObject("premium_requests") ?: return null

        val total = quotaObj["monthly_maximum"]?.asInt
            ?: quotaObj["maximum"]?.asInt
            ?: quotaObj["total"]?.asInt
            ?: quotaObj["limit"]?.asInt ?: return null
        val used      = quotaObj["used"]?.asInt ?: 0
        val remaining = (quotaObj["remaining"]?.asInt ?: (total - used)).coerceAtLeast(0)
        return QuotaResult.Available(QuotaInfo(remaining, total, quotaReset))
    }

    /** Flat-field API: `premium_requests_maximum` / `monthly_maximum_premium_requests` / `premium_requests_monthly_limit`. */
    private fun parseFlatQuotaFormat(root: JsonObject, quotaReset: String?): QuotaResult? {
        val max = root["premium_requests_maximum"]?.asInt
            ?: root["monthly_maximum_premium_requests"]?.asInt
            ?: root["premium_requests_monthly_limit"]?.asInt ?: return null
        val used      = root["premium_requests_used"]?.asInt ?: 0
        val remaining = (max - used).coerceAtLeast(0)
        return QuotaResult.Available(QuotaInfo(remaining, max, quotaReset))
    }
}
