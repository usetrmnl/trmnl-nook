package com.example.nooktrmnl

import android.content.ContentResolver
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.delay

class PowerManagerNook(private val context: Context) {

    private val contentResolver: ContentResolver = context.contentResolver
    private val handler = Handler(Looper.getMainLooper())
    private val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        private const val POWER_ENHANCE_ENABLE = "power_enhance_enable"
        private const val LOG_TAG = "NookTerminalPower"
        private const val POWER_STATE_DELAY = 200L
        private const val SLEEP_CHECK_DELAY = 50L
        private const val NETWORK_CHECK_INTERVAL = 500L
        private const val MAX_NETWORK_ATTEMPTS = 30
        private const val NETWORK_STABILIZE_DELAY = 1000L
    }

    suspend fun ensureWifiOn(): Boolean {
        if (isNetworkConnected()) {
            Log.d(LOG_TAG, "Already connected to network")
            return true
        }

        if (!wifiManager.isWifiEnabled) {
            Log.d(LOG_TAG, "WiFi is off, enabling")
            wifiManager.isWifiEnabled = true
        }

        return waitForNetwork()
    }

    private suspend fun waitForNetwork(): Boolean {
        var attempts = 0

        while (attempts < MAX_NETWORK_ATTEMPTS) {
            if (isNetworkConnected()) {
                Log.d(LOG_TAG, "Network connected after ${attempts * NETWORK_CHECK_INTERVAL}ms")
                delay(NETWORK_STABILIZE_DELAY)
                return true
            }
            delay(NETWORK_CHECK_INTERVAL)
            attempts++
            Log.d(LOG_TAG, "Waiting for network... Attempt $attempts")
        }

        Log.e(LOG_TAG, "Failed to establish network connection")
        return false
    }

    private fun isNetworkConnected(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            capabilities?.let {
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            } ?: false
        } else {
            @Suppress("DEPRECATION")
            connectivityManager.activeNetworkInfo?.isConnected == true
        }
    }

    suspend fun wakeForUpdate() {
        try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

            wakeLock = powerManager.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or
                        PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        PowerManager.ON_AFTER_RELEASE,
                "NookTerminal::FullWakeLock"
            ).apply {
                acquire(10 * 60 * 1000L) // 10 minute timeout
            }

            val before = getPowerEnhanceState()
            setPowerEnhanceState(0)
            val after = getPowerEnhanceState()

            Log.d(LOG_TAG, "Wake - power_enhance_enable changed from $before to $after")

            delay(POWER_STATE_DELAY * 2)

            val actualPowerState = getPowerEnhanceState()
            Log.d(LOG_TAG, "Power enhance state after delay: $actualPowerState")

        } catch (e: Exception) {
            Log.e(LOG_TAG, "Error waking device", e)
        }
    }

    suspend fun goToSleep() {
        try {
            try {
                if (wifiManager.isWifiEnabled) {
                    Log.d(LOG_TAG, "Disabling WiFi for sleep")
                    wifiManager.isWifiEnabled = false
                }
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Error disabling WiFi", e)
            }

            try {
                wakeLock?.let {
                    if (it.isHeld) {
                        it.release()
                        Log.d(LOG_TAG, "Wake lock released")
                    }
                }
                wakeLock = null
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Error releasing wake lock", e)
            }

            setPowerEnhanceState(0)
            delay(POWER_STATE_DELAY)
            setPowerEnhanceState(1)

            Log.d(LOG_TAG, "Sleep command sent - checking state")

            delay(SLEEP_CHECK_DELAY)
            val state = getPowerEnhanceState()
            Log.d(LOG_TAG, "Sleep state after delay: $state")

        } catch (e: Exception) {
            Log.e(LOG_TAG, "Error putting device to sleep", e)
        }
    }

    private fun getPowerEnhanceState(): Int {
        return Settings.System.getInt(contentResolver, POWER_ENHANCE_ENABLE, 0)
    }

    private fun setPowerEnhanceState(state: Int) {
        Settings.System.putInt(contentResolver, POWER_ENHANCE_ENABLE, state)
    }
}