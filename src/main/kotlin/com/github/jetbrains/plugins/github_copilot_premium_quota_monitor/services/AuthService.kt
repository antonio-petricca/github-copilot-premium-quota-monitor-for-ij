package com.github.jetbrains.plugins.github_copilot_premium_quota_monitor.services

import com.github.jetbrains.plugins.github_copilot_premium_quota_monitor.i18n.Messages
import com.google.gson.JsonParser
import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicReference

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
         * Scopes requested during the device flow.
         * `read:user` is sufficient to authenticate and call `copilot_internal/user`.
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
        // Load cached credentials in a background thread to avoid blocking
        // read actions or the UI thread at startup.
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val token    = PasswordSafe.instance.getPassword(TOKEN_ATTRS)
                val username = PasswordSafe.instance.getPassword(USERNAME_ATTRS)
                cachedToken.set(token)
                cachedUsername.set(username)
                LOG.info("Cached credentials loaded: token=${if (token != null) "present" else "absent"}, user=$username")
            } catch (e: Exception) {
                LOG.warn("Failed to load cached credentials", e)
            }
        }
    }

    // ── Domain types ──────────────────────────────────────────────────────────

    data class DeviceCodeResponse(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val interval: Int,
        val expiresIn: Int,
    )

    sealed class PollResult {
        data class  Success(val token: String)  : PollResult()
        data object Pending                      : PollResult()
        data object Expired                      : PollResult()
        data class  Error(val message: String)  : PollResult()
    }

    // ── Token store ───────────────────────────────────────────────────────────

    // In-memory cached values used by UI code that runs inside read actions and
    // therefore must not perform blocking I/O. These are kept up-to-date whenever
    // tokens are saved or cleared, and are pre-loaded in the background at init.
    private val cachedToken    = AtomicReference<String?>(null)
    private val cachedUsername = AtomicReference<String?>(null)

    /**
     * Returns the stored OAuth token, reading it directly from PasswordSafe.
     * Must be called off the EDT (PasswordSafe access can be slow / blocking).
     */
    fun getToken(): String? {
        val token = PasswordSafe.instance.getPassword(TOKEN_ATTRS)
        cachedToken.set(token) // keep in-memory cache in sync
        return token
    }

    /**
     * Blocking authentication check that reads directly from PasswordSafe.
     * Must be called off the EDT.
     */
    fun isAuthenticated(): Boolean = getToken() != null

    /**
     * Non-blocking, cached authentication check safe to call from the EDT
     * or inside read actions.
     */
    fun isAuthenticatedCached(): Boolean = cachedToken.get() != null

    /** Cached, non-blocking username accessor safe to call from the EDT. */
    fun getSavedUsername(): String? = cachedUsername.get()

    /**
     * Persists [token] and fetches + caches the associated GitHub username.
     * Must be called off the EDT.
     */
    fun saveAuthentication(token: String) {
        LOG.info("Saving OAuth token and fetching GitHub username")

        cachedToken.set(token) // update in-memory cache before persisting
        PasswordSafe.instance.setPassword(TOKEN_ATTRS, token)

        val username = fetchUsername(token)
        LOG.info("GitHub username fetched: $username")

        cachedUsername.set(username)
        PasswordSafe.instance.setPassword(USERNAME_ATTRS, username)

        notifyAuthStateChanged()
    }

    /** Removes the stored token and username from both the cache and PasswordSafe. */
    fun clearAuthentication() {
        LOG.info("Clearing stored authentication credentials")

        cachedToken.set(null)
        cachedUsername.set(null)

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                PasswordSafe.instance.setPassword(TOKEN_ATTRS, null)
                PasswordSafe.instance.setPassword(USERNAME_ATTRS, null)
            } catch (e: Exception) {
                LOG.warn("Failed to clear credentials from PasswordSafe", e)
            }
        }

        notifyAuthStateChanged()
    }

    // ── Device Flow ───────────────────────────────────────────────────────────

    /**
     * Step 1 — Request a device code from GitHub.
     * Must be called off the EDT.
     *
     * @throws Exception on network or API error.
     */
    @Throws(Exception::class)
    fun requestDeviceCode(): DeviceCodeResponse {
        LOG.info("Requesting device code from GitHub")

        val conn = post(DEVICE_CODE_URL, "client_id=${enc(CLIENT_ID)}&scope=${enc(SCOPE)}")
        val code = conn.responseCode
        if (code != HttpURLConnection.HTTP_OK) {
            LOG.error("Device code request failed with HTTP $code")

            throw RuntimeException(Messages.format("auth_device_code_http_error", code))
        }

        val json = JsonParser.parseString(conn.inputStream.bufferedReader().readText()).asJsonObject
        return DeviceCodeResponse(
            deviceCode      = json["device_code"]?.asString      ?: error(Messages.get("auth_missing_device_code")),
            userCode        = json["user_code"]?.asString        ?: error(Messages.get("auth_missing_user_code")),
            verificationUri = json["verification_uri"]?.asString ?: "https://github.com/login/device",
            interval        = json["interval"]?.asInt            ?: 5,
            expiresIn       = json["expires_in"]?.asInt          ?: 900,
        ).also {
            LOG.debug("Device code obtained, expires in ${it.expiresIn} seconds")
        }
    }

    /**
     * Step 2 — Poll once for the access token.
     * The caller is responsible for waiting [DeviceCodeResponse.interval] seconds between calls.
     * Must be called off the EDT.
     */
    fun pollForToken(deviceCode: String): PollResult {
        return try {
            LOG.debug("Polling GitHub for access token")

            val body = "client_id=${enc(CLIENT_ID)}" +
                       "&device_code=${enc(deviceCode)}" +
                       "&grant_type=${enc("urn:ietf:params:oauth:grant-type:device_code")}"
            val conn = post(ACCESS_TOKEN_URL, body)
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                LOG.debug("Poll returned HTTP ${conn.responseCode}")

                return PollResult.Error(Messages.format("auth_poll_http_error", conn.responseCode))
            }

            val json  = JsonParser.parseString(conn.inputStream.bufferedReader().readText()).asJsonObject
            val token = json["access_token"]?.asString
            if (!token.isNullOrBlank()) {
                LOG.info("Access token successfully obtained")

                return PollResult.Success(token)
            }

            when (json["error"]?.asString) {
                "authorization_pending", "slow_down" -> {
                    LOG.debug("Device flow still pending")

                    PollResult.Pending
                }
                "expired_token" -> {
                    LOG.warn("Device code expired")

                    PollResult.Expired
                }
                else -> {
                    LOG.warn("Device flow error: ${json["error"]?.asString}")

                    PollResult.Error(json["error_description"]?.asString ?: Messages.get("auth_unknown_error"))
                }
            }
        } catch (e: Exception) {
            LOG.warn("Device-flow poll error", e)
            PollResult.Error(e.message ?: Messages.get("general_network_error"))
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun fetchUsername(token: String): String? = try {
        val conn = (URI.create(USER_API_URL).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept",        "application/vnd.github+json")
            setRequestProperty("User-Agent",    "github-copilot-quota-monitor-ij/1.0")
            connectTimeout = 10_000
            readTimeout    = 10_000
        }
        if (conn.responseCode == HttpURLConnection.HTTP_OK)
            JsonParser.parseString(conn.inputStream.bufferedReader().readText())
                .asJsonObject["login"]?.asString
        else null
    } catch (e: Exception) {
        LOG.warn("Failed to fetch GitHub username", e)
        null
    }

    /** Opens a POST connection and writes [body] as URL-encoded form data. */
    private fun post(url: String, body: String): HttpURLConnection {
        val conn = (URI.create(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput      = true
            setRequestProperty("Accept",       "application/json")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connectTimeout = 10_000
            readTimeout    = 10_000
        }
        OutputStreamWriter(conn.outputStream).use { it.write(body) }
        return conn
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun notifyAuthStateChanged() {
        try {
            ApplicationManager.getApplication().messageBus
                .syncPublisher(AuthStateNotifier.AUTH_TOPIC)
                .authStateChanged()
        } catch (_: Exception) {
            // best-effort — auth state has already been persisted
        }
    }
}
