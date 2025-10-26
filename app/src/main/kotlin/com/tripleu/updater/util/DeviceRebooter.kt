package com.tripleu.updater.util

import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Helper that tries to reboot the device using system privileges first, then
 * falls back to a root shell when available.
 */
class DeviceRebooter(private val context: Context) {

    suspend fun reboot(): Boolean {
        val powerResult = runCatching { requestPowerManagerReboot() }.getOrDefault(false)
        if (powerResult) {
            return true
        }
        return runRootReboot()
    }

    private fun requestPowerManagerReboot(): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                powerManager.reboot(null)
            } else {
                @Suppress("DEPRECATION")
                powerManager.reboot(null)
            }
            true
        } catch (error: SecurityException) {
            NoOpLog.e(TAG, "PowerManager reboot denied", error)
            false
        } catch (error: Throwable) {
            NoOpLog.e(TAG, "PowerManager reboot failed", error)
            false
        }
    }

    private suspend fun runRootReboot(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            Shell.cmd("reboot").exec().isSuccess
        }.onFailure {
            NoOpLog.e(TAG, "Root reboot failed", it)
        }.getOrDefault(false)
    }

    private companion object {
        private const val TAG = "DeviceRebooter"
    }
}

