package com.tripleu.updater.util.play

import android.content.Context
import com.tripleu.updater.util.NoOpLog as Log
import java.util.Properties
import java.util.concurrent.atomic.AtomicLong

/**
 * Central place to prime and reuse the Play/Aurora device spoof profile.
 *
 * The Aurora GPlay api expects a fairly complete device description during auth. We build it once
 * at startup so the library has consistent data even if the first network call triggers before the
 * telephony stack is completely ready.
 */
object PlayDeviceSpoofManager {

    private const val TAG = "PlayDeviceSpoof"
    private val lastRefreshAt = AtomicLong(0)

    @Volatile
    private var cachedProperties: Properties? = null
    @Volatile
    private var lastAuthErrorCode: Int? = null
    @Volatile
    private var lastAuthSuccessful: Boolean = false

    /**
     * Precompute the spoof profile. Safe to call multiple times; the most recent snapshot wins.
     */
    fun initialize(context: Context) {
        refresh(context, reason = "app_start")
    }

    /**
     * Return the cached properties or lazily compute them if the cache is empty.
     */
    fun properties(context: Context): Properties {
        val current = cachedProperties
        if (current != null) return current
        return refresh(context, reason = "lazy_warmup")
    }

    /**
     * Force a new snapshot of the spoof profile.
     */
    fun refresh(context: Context, reason: String = "manual"): Properties {
        val props = NativeDeviceInfoProvider(context.applicationContext).getNativeDeviceProperties()
        cachedProperties = props
        lastRefreshAt.set(System.currentTimeMillis())
        Log.i(
            TAG,
            "Spoof profile primed (reason=$reason, profile=${props.getProperty("Spoof.Profile")}, model=${props.getProperty("Build.MODEL")}, sdk=${props.getProperty("Build.VERSION.SDK_INT")})"
        )
        return props
    }

    fun lastRefreshedAt(): Long = lastRefreshAt.get()

    fun markAuthSuccess() {
        lastAuthSuccessful = true
        lastAuthErrorCode = null
    }

    fun markAuthFailure(code: Int) {
        lastAuthSuccessful = false
        lastAuthErrorCode = code
    }

    fun lastAuthErrorCode(): Int? = lastAuthErrorCode

    fun wasLastAuthSuccessful(): Boolean = lastAuthSuccessful
}
