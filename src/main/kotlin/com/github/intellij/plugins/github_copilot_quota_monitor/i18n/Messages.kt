package com.github.intellij.plugins.github_copilot_quota_monitor.i18n

import com.intellij.openapi.diagnostic.Logger
import java.text.MessageFormat
import java.util.*

object Messages {
    private val LOG = Logger.getInstance(Messages::class.java)
    private val BUNDLE: ResourceBundle = ResourceBundle.getBundle("messages")

    /**
     * Returns the localised string for [key].
     * If the key is not found in the bundle, logs an error and returns `"[key]"` as fallback.
     */
    fun get(key: String): String {
        return try {
            BUNDLE.getString(key)
        } catch (e: MissingResourceException) {
            LOG.error("Missing i18n message key: '$key'", e)
            "[$key]"
        }
    }

    /**
     * Formats the localised string for [key] with the given [args].
     * If the key is missing or formatting fails, logs an error and returns `"[key]"` as fallback.
     */
    fun format(key: String, vararg args: Any?): String {
        return try {
            MessageFormat.format(get(key), *args)
        } catch (e: Exception) {
            LOG.error("Failed to format i18n message key: '$key' with args: ${args.toList()}", e)
            "[$key]"
        }
    }
}

