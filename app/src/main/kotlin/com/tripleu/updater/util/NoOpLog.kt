package com.tripleu.updater.util

import java.io.PrintWriter
import java.io.StringWriter

/**
 * Lightweight stand-in for `android.util.Log` that drops all log output.
 * Keeping the API surface avoids touching every call site while ensuring
 * production builds stay quiet even when verbose logging slips in.
 */
object NoOpLog {
    const val VERBOSE: Int = android.util.Log.VERBOSE
    const val DEBUG: Int = android.util.Log.DEBUG
    const val INFO: Int = android.util.Log.INFO
    const val WARN: Int = android.util.Log.WARN
    const val ERROR: Int = android.util.Log.ERROR
    const val ASSERT: Int = android.util.Log.ASSERT

    fun v(tag: String?, msg: String?): Int = 0
    fun v(tag: String?, msg: String?, tr: Throwable?): Int = 0
    fun d(tag: String?, msg: String?): Int = 0
    fun d(tag: String?, msg: String?, tr: Throwable?): Int = 0
    fun i(tag: String?, msg: String?): Int = 0
    fun i(tag: String?, msg: String?, tr: Throwable?): Int = 0
    fun w(tag: String?, msg: String?): Int = 0
    fun w(tag: String?, tr: Throwable?): Int = 0
    fun w(tag: String?, msg: String?, tr: Throwable?): Int = 0
    fun e(tag: String?, msg: String?): Int = 0
    fun e(tag: String?, msg: String?, tr: Throwable?): Int = 0
    fun println(priority: Int, tag: String?, msg: String?): Int = 0
    fun isLoggable(tag: String?, level: Int): Boolean = false

    fun getStackTraceString(tr: Throwable?): String {
        if (tr == null) return ""
        val writer = StringWriter()
        tr.printStackTrace(PrintWriter(writer))
        return writer.toString()
    }
}

