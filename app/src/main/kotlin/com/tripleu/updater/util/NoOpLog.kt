package com.tripleu.updater.util

import android.util.Log
import com.tripleu.updater.BuildConfig

/**
 * Thin wrapper around [Log] that allows us to keep the existing logging surface while
 * staying silent by default. When `ENABLE_PLAY_DEBUG_LOGS` (or a debug build) is enabled
 * we mirror calls to the platform logger so release diagnostics can still be captured.
 */
object NoOpLog {
    const val VERBOSE: Int = Log.VERBOSE
    const val DEBUG: Int = Log.DEBUG
    const val INFO: Int = Log.INFO
    const val WARN: Int = Log.WARN
    const val ERROR: Int = Log.ERROR
    const val ASSERT: Int = Log.ASSERT

    private const val DEFAULT_TAG = "UUUpdater"
    private val shouldLog: Boolean = BuildConfig.DEBUG || BuildConfig.ENABLE_PLAY_DEBUG_LOGS

    fun v(tag: String?, msg: String?): Int = log(VERBOSE, tag, msg, null)
    fun v(tag: String?, msg: String?, tr: Throwable?): Int = log(VERBOSE, tag, msg, tr)
    fun d(tag: String?, msg: String?): Int = log(DEBUG, tag, msg, null)
    fun d(tag: String?, msg: String?, tr: Throwable?): Int = log(DEBUG, tag, msg, tr)
    fun i(tag: String?, msg: String?): Int = log(INFO, tag, msg, null)
    fun i(tag: String?, msg: String?, tr: Throwable?): Int = log(INFO, tag, msg, tr)
    fun w(tag: String?, msg: String?): Int = log(WARN, tag, msg, null)
    fun w(tag: String?, tr: Throwable?): Int = log(WARN, tag, tr?.message, tr)
    fun w(tag: String?, msg: String?, tr: Throwable?): Int = log(WARN, tag, msg, tr)
    fun e(tag: String?, msg: String?): Int = log(ERROR, tag, msg, null)
    fun e(tag: String?, msg: String?, tr: Throwable?): Int = log(ERROR, tag, msg, tr)
    fun println(priority: Int, tag: String?, msg: String?): Int = log(priority, tag, msg, null)

    fun isLoggable(tag: String?, level: Int): Boolean {
        if (!shouldLog) return false
        return Log.isLoggable(tag ?: DEFAULT_TAG, level)
    }

    fun getStackTraceString(tr: Throwable?): String = Log.getStackTraceString(tr)

    private fun log(priority: Int, tag: String?, message: String?, throwable: Throwable?): Int {
        if (!shouldLog) return 0
        val safeTag = tag?.takeUnless { it.isBlank() } ?: DEFAULT_TAG
        val safeMessage = buildString {
            append(message.orEmpty())
            if (throwable != null) {
                if (isNotEmpty()) append('\n')
                append(Log.getStackTraceString(throwable))
            }
        }
        return Log.println(priority, safeTag, safeMessage)
    }
}
