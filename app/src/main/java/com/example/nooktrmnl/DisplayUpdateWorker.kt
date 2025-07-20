package com.example.nooktrmnl

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class DisplayUpdateWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val KEY_REFRESH_RATE = "refresh_rate"
        const val ACTION_NETWORK_COMPLETE = "com.example.nooktrmnl.ACTION_NETWORK_COMPLETE"
        const val ACTION_SCREEN_REFRESH_COMPLETE = "com.example.nooktrmnl.ACTION_SCREEN_REFRESH_COMPLETE"
        private const val NETWORK_TIMEOUT_MS = 30000L
        private const val SCREEN_REFRESH_TIMEOUT_MS = 15000L
        private const val LOG_FILE_SIZE_LIMIT = 1024 * 1024
    }

    private var networkComplete = false
    private var screenRefreshComplete = false
    private var actualRefreshRate = 60

    private fun logActivity(message: String) {
        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
            val logEntry = "$timestamp - DisplayUpdateWorker: $message\n"

            val logFile = File(applicationContext.getExternalFilesDir(null), "update_activity.log")

            if (logFile.exists() && logFile.length() > LOG_FILE_SIZE_LIMIT) {
                val content = logFile.readText()
                val lines = content.lines()
                val keepLines = lines.takeLast(lines.size / 2)
                logFile.writeText(keepLines.joinToString("\n") + "\n")
            }

            logFile.appendText(logEntry)
            Log.d("DisplayUpdateWorker", message)
        } catch (e: Exception) {
            Log.e("DisplayUpdateWorker", "Failed to write activity log", e)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            logActivity("Starting update work")

            val powerManager = PowerManagerNook(applicationContext)

            logActivity("Ensuring WiFi connection")
            var wifiAttempts = 0
            var wifiSuccess = false

            while (!wifiSuccess && wifiAttempts < 3) {
                wifiSuccess = powerManager.ensureWifiOn()
                if (!wifiSuccess) {
                    val backoffDelay = (wifiAttempts + 1) * 2000L
                    logActivity("WiFi enable attempt ${wifiAttempts + 1} failed, waiting ${backoffDelay}ms")
                    delay(backoffDelay)
                    wifiAttempts++
                }
                logActivity("WiFi attempt $wifiAttempts status: $wifiSuccess")
            }

            if (!wifiSuccess) {
                logActivity("Failed to enable WiFi after 3 attempts")
                powerManager.goToSleep()
                return@withContext Result.retry()
            }

            logActivity("WiFi connection established successfully")

            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    when (intent?.action) {
                        ACTION_NETWORK_COMPLETE -> {
                            logActivity("Network completion notification received")
                            val newRefreshRate = intent.getIntExtra("refresh_rate", 60)
                            val source = intent.getStringExtra("from_source") ?: "unknown"
                            logActivity("Received refresh rate: ${newRefreshRate}s from source: $source")

                            if (newRefreshRate > 0) {
                                actualRefreshRate = newRefreshRate
                                logActivity("Updated refresh rate to ${actualRefreshRate}s")
                            }
                            networkComplete = true
                        }
                        ACTION_SCREEN_REFRESH_COMPLETE -> {
                            logActivity("Screen refresh completion notification received")
                            screenRefreshComplete = true
                        }
                    }
                }
            }

            val filter = IntentFilter().apply {
                addAction(ACTION_NETWORK_COMPLETE)
                addAction(ACTION_SCREEN_REFRESH_COMPLETE)
            }
            LocalBroadcastManager.getInstance(applicationContext).registerReceiver(receiver, filter)

            try {
                val refreshRate = inputData.getInt(KEY_REFRESH_RATE, 60)

                val intent = Intent(MainActivity.ACTION_DISPLAY_UPDATE).apply {
                    putExtra("trigger_update", true)
                }
                LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(intent)

                logActivity("Waiting for network requests to complete...")
                val networkSuccess = withTimeoutOrNull(NETWORK_TIMEOUT_MS) {
                    while (!networkComplete) {
                        delay(500)
                        if (System.currentTimeMillis() % 2000 < 500) {
                            logActivity("Still waiting for network completion...")
                        }
                    }
                    true
                } ?: false

                if (networkSuccess) {
                    logActivity("Network requests completed successfully")

                    logActivity("Waiting for screen refresh to complete...")
                    val screenSuccess = withTimeoutOrNull(SCREEN_REFRESH_TIMEOUT_MS) {
                        while (!screenRefreshComplete) {
                            delay(500)
                            if (System.currentTimeMillis() % 1000 < 500) {
                                logActivity("Still waiting for screen refresh...")
                            }
                        }
                        true
                    } ?: false

                    if (screenSuccess) {
                        logActivity("Screen refresh completed successfully")
                    } else {
                        logActivity("Screen refresh timed out after ${SCREEN_REFRESH_TIMEOUT_MS}ms - proceeding anyway")
                    }
                } else {
                    logActivity("Network requests timed out after ${NETWORK_TIMEOUT_MS}ms")
                    actualRefreshRate = refreshRate
                    logActivity("Using fallback refresh rate: ${actualRefreshRate}s")
                }

                logActivity("Scheduling next update")
                scheduleNextUpdate(applicationContext, actualRefreshRate)

            } finally {
                LocalBroadcastManager.getInstance(applicationContext).unregisterReceiver(receiver)
            }

            logActivity("Going back to sleep")
            powerManager.goToSleep()

            logActivity("Update work completed successfully")
            Result.success()
        } catch (e: Exception) {
            logActivity("Error during update: ${e.message}")
            try {
                val powerManager = PowerManagerNook(applicationContext)
                powerManager.goToSleep()
            } catch (sleepError: Exception) {
                logActivity("Failed to go to sleep after error: ${sleepError.message}")
            }
            Result.retry()
        }
    }

    @SuppressLint("ScheduleExactAlarm")
    private fun scheduleNextUpdate(context: Context, refreshRate: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val updateIntent = Intent(context, UpdateReceiver::class.java).apply {
            action = MainActivity.ACTION_UPDATE
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            MainActivity.UPDATE_REQUEST_CODE,
            updateIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val nextUpdateTime = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(refreshRate.toLong())

        alarmManager.setAlarmClock(
            AlarmManager.AlarmClockInfo(nextUpdateTime, pendingIntent),
            pendingIntent
        )

        logActivity("Next update scheduled for: ${Date(nextUpdateTime)} (${refreshRate}s from now)")
    }
}