package com.github.intellij.plugins.github_copilot_quota_monitor.services

import com.google.gson.JsonParser
import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.application.ApplicationManager
import com.intellij.util.messages.MessageBus
import com.github.intellij.plugins.github_copilot_quota_monitor.services.AuthStateNotifier
import java.util.concurrent.atomic.AtomicReference
import java.util.*
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/**
 * Application-level service that manages GitHub authentication using the
 * OAuth 2.0 Device Authorization Grant (RFC 8628).
 *
 * Tokens are stored securely in IntelliJ's PasswordSafe (OS keychain /
 * KDE Wallet / encrypted file depending on the platform).
 *
 * No dependency on the GitHub Copilot plugin or on org.jetbrains.plugins.github
 * is required: authentication is handled entirely by this service.
 */
@Service(Service.Level.APP)
class AuthService {

    companion object {
        private val LOG = Logger.getInstance(AuthService::class.java)
        /**
         * OAuth App client ID used for the Device Flow.
         * This is the publicly documented client ID for GitHub Copilot IDE integrations.
         * Device Flow does NOT require a client secret on the client side (RFC 8628 §7).
         */
        const val CLIENT_ID = "Iv1.b507a08c87ecfe98"

        private const val DEVICE_CODE_URL  = "https://github.com/login/device/code"
        private const val ACCESS_TOKEN_URL = "https://github.com/login/oauth/access_token"
        private const val USER_API_URL     = "https://api.github.com/user"

        /**
         * Scopes requested during device flow.
         * read:user is sufficient to authenticate and call copilot_internal/user.
         */
        private const val SCOPE = "read:user"

        private val TOKEN_ATTRS = CredentialAttributes(
            generateServiceName("GitHubCopilotQuotaMonitor", "oauth_token")
        )
        private val USERNAME_ATTRS = CredentialAttributes(
            generateServiceName("GitHubCopilotQuotaMonitor", "username")
        )

        @JvmStatic
        fun getInstance(): AuthService = service()
    }

    init {
        // Load cached credentials in background to avoid blocking read actions/UI.
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val token = PasswordSafe.instance.getPassword(TOKEN_ATTRS)
                cachedToken.set(token)
                val username = PasswordSafe.instance.getPassword(USERNAME_ATTRS)
                cachedUsername.set(username)
                LOG.info("[CopilotQuotaMonitor] Cached credentials loaded: token=${if (token != null) "present" else "none"}, user=${username}")
            } catch (e: Exception) {
                LOG.warn("[CopilotQuotaMonitor] Failed to load cached credentials", e)
            }
        }
    }

    // ── Domain types ──────────────────────────────────────────────────────────

    data class DeviceCodeResponse(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val interval: Int,
        val expiresIn: Int
    )

    sealed class PollResult {
        data class Success(val token: String) : PollResult()
        object Pending : PollResult()
        object Expired : PollResult()
        data class Error(val message: String) : PollResult()
    }

    // ── Token store ───────────────────────────────────────────────────────────

    // In-memory cached values used by UI code that runs inside read actions and
    // therefore must not perform blocking operations (PasswordSafe access can be
    // slow / platform-dependent). These are kept up-to-date when tokens are
    // saved/cleared and are initially loaded in background in the init block.
    private val cachedToken = AtomicReference<String?>(null)
    private val cachedUsername = AtomicReference<String?>(null)

    fun getToken(): String? {
        val token = PasswordSafe.instance.getPassword(TOKEN_ATTRS)
        LOG.info("[CopilotQuotaMonitor] token present=${token != null}")
        // Keep cache in sync for callers that must not perform blocking IO
        cachedToken.set(token)
        return token
    }

    /** Non-blocking check for use by UI code that runs under read actions. */
    fun isAuthenticated(): Boolean = getToken() != null

    /** Cached, non-blocking username accessor for use from UI/read actions. */
    fun isAuthenticatedCached(): Boolean = cachedToken.get() != null

    fun getSavedUsername(): String? = cachedUsername.get()

    /**
     * Persists [token] and fetches + caches the associated GitHub username.
     * Must be called off the EDT.
     */
    fun saveAuthentication(token: String) {
        LOG.info("[CopilotQuotaMonitor] Saving token")
        // Update in-memory cache immediately so UI code can observe auth state
        cachedToken.set(token)
        PasswordSafe.instance.setPassword(TOKEN_ATTRS, token)
        val username = fetchUsername(token)
        LOG.info("[CopilotQuotaMonitor] Fetched username=$username")
        cachedUsername.set(username)
        PasswordSafe.instance.setPassword(USERNAME_ATTRS, username)
        // Notify listeners that auth state changed (signed in)
        try {
            ApplicationManager.getApplication().messageBus.syncPublisher(AuthStateNotifier.AUTH_TOPIC).authStateChanged()
        } catch (_: Exception) {
            // Ignore notification failures; auth state has been persisted regardless
        }
    }

    /** Removes the stored token and username. */
    fun clearAuthentication() {
        // Clear in-memory cache first
        cachedToken.set(null)
        cachedUsername.set(null)
        // Perform actual PasswordSafe writes off the EDT to avoid slow blocking
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                PasswordSafe.instance.setPassword(TOKEN_ATTRS, null)
                PasswordSafe.instance.setPassword(USERNAME_ATTRS, null)
            } catch (e: Exception) {
                LOG.warn("[CopilotQuotaMonitor] Failed to clear credentials from PasswordSafe", e)
            }
        }
        // Notify listeners that auth state changed (signed out)
        try {
            ApplicationManager.getApplication().messageBus.syncPublisher(AuthStateNotifier.AUTH_TOPIC).authStateChanged()
        } catch (_: Exception) {
            // best-effort
        }
    }

    // ── Device Flow ───────────────────────────────────────────────────────────

    /**
     * Step 1 — Request a device code from GitHub.
     * Must be called off the EDT.
     * @throws Exception on network or API error.
     */
    @Throws(Exception::class)
    fun requestDeviceCode(): DeviceCodeResponse {
        val conn = post(DEVICE_CODE_URL, "client_id=${enc(CLIENT_ID)}&scope=${enc(SCOPE)}")
        val code = conn.responseCode
        if (code != 200) throw RuntimeException(com.github.intellij.plugins.github_copilot_quota_monitor.i18n.Messages.format("auth_device_code_http_error", code))

        val json = JsonParser.parseString(conn.inputStream.bufferedReader().readText()).asJsonObject
        return DeviceCodeResponse(
            deviceCode    = json["device_code"]?.asString    ?: error(com.github.intellij.plugins.github_copilot_quota_monitor.i18n.Messages.get("missing_device_code")),
            userCode      = json["user_code"]?.asString      ?: error(com.github.intellij.plugins.github_copilot_quota_monitor.i18n.Messages.get("missing_user_code")),
            verificationUri = json["verification_uri"]?.asString ?: "https://github.com/login/device",
            interval      = json["interval"]?.asInt  ?: 5,
            expiresIn     = json["expires_in"]?.asInt ?: 900
        )
    }

    /**
     * Step 2 — Poll once for the access token.
     * The caller is responsible for waiting [DeviceCodeResponse.interval] seconds between calls.
     * Must be called off the EDT.
     */
    fun pollForToken(deviceCode: String): PollResult {
        return try {
            val body = "client_id=${enc(CLIENT_ID)}" +
                       "&device_code=${enc(deviceCode)}" +
                       "&grant_type=${enc("urn:ietf:params:oauth:grant-type:device_code")}"
            val conn = post(ACCESS_TOKEN_URL, body)
            if (conn.responseCode != 200) return PollResult.Error(com.github.intellij.plugins.github_copilot_quota_monitor.i18n.Messages.format("auth_poll_http_error", conn.responseCode))

            val json = JsonParser.parseString(conn.inputStream.bufferedReader().readText()).asJsonObject
            val token = json["access_token"]?.asString
            if (!token.isNullOrBlank()) return PollResult.Success(token)

            when (json["error"]?.asString) {
                "authorization_pending", "slow_down" -> PollResult.Pending
                "expired_token"                       -> PollResult.Expired
                else -> PollResult.Error(json["error_description"]?.asString ?: com.github.intellij.plugins.github_copilot_quota_monitor.i18n.Messages.get("unknown_error"))
            }
        } catch (e: Exception) {
            LOG.warn("[CopilotQuotaMonitor] Device-flow poll error", e)
            PollResult.Error(e.message ?: com.github.intellij.plugins.github_copilot_quota_monitor.i18n.Messages.get("network_error"))
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun fetchUsername(token: String): String? = try {
        val conn = (URI.create(USER_API_URL).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept",        "application/vnd.github+json")
            setRequestProperty("User-Agent",    "github-copilot-quota-monitor-ij/1.0")
            connectTimeout = 10_000
            readTimeout    = 10_000
        }
        if (conn.responseCode == 200)
            JsonParser.parseString(conn.inputStream.bufferedReader().readText())
                .asJsonObject["login"]?.asString
        else null
    } catch (e: Exception) {
        LOG.warn("[CopilotQuotaMonitor] Failed to fetch GitHub username", e)
        null
    }

    /** Opens a POST connection and writes [body] (URL-encoded form data). */
    private fun post(url: String, body: String): HttpURLConnection {
        val conn = (URI.create(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Accept",       "application/json")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connectTimeout = 10_000
            readTimeout    = 10_000
        }
        OutputStreamWriter(conn.outputStream).use { it.write(body) }
        return conn
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}




