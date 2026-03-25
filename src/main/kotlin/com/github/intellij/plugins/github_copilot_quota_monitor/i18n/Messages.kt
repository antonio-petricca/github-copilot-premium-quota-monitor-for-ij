package com.github.intellij.plugins.github_copilot_quota_monitor.i18n

import java.text.MessageFormat
import java.util.*

object Messages {
    private val BUNDLE: ResourceBundle = ResourceBundle.getBundle("messages")

    fun get(key: String): String = BUNDLE.getString(key)

    fun format(key: String, vararg args: Any?): String = MessageFormat.format(get(key), *args)
}

