package com.example.nooktrmnl

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object UpdateManager {

    private const val WORK_NAME = "displayUpdate"
    private const val WORK_TAG = "com.example.nooktrmnl.DisplayUpdateWorker"

    fun triggerUpdate(context: Context, triggerSource: String) {
        val pendingResult = if (context is BroadcastReceiver) {
            context.goAsync()
        } else null

        CoroutineScope(Dispatchers.Default).launch {
            try {
                Log.d("UpdateManager", "Update triggered by: $triggerSource")

                logPowerState(context)

                if (triggerSource == "headphone_primary") {
                    cancelScheduledUpdates(context)
                    Log.d("UpdateManager", "Cancelled any pending alarms")

                    val powerManager = PowerManagerNook(context)
                    powerManager.wakeForUpdate()

                    WorkManager.getInstance(context).cancelAllWorkByTag(WORK_TAG)

                    val workRequest = OneTimeWorkRequestBuilder<DisplayUpdateWorker>()
                        .setInputData(
                            Data.Builder()
                                .putInt(DisplayUpdateWorker.KEY_REFRESH_RATE, MainActivity.refreshRate)
                                .build()
                        )
                        .addTag(WORK_TAG)
                        .build()

                    WorkManager.getInstance(context).enqueueUniqueWork(
                        WORK_NAME,
                        ExistingWorkPolicy.REPLACE,
                        workRequest
                    )

                    Log.d("UpdateManager", "DisplayUpdateWorker enqueued via headphone trigger")
                } else if (triggerSource == "alarm_scheduler") {
                    Log.d("UpdateManager", "Alarm fired - but relying on headphone event for actual wake")
                }

            } catch (e: Exception) {
                Log.e("UpdateManager", "Error in update trigger ($triggerSource)", e)
            } finally {
                pendingResult?.finish()
            }
        }
    }

    private fun logPowerState(context: Context) {
        try {
            val powerState = Settings.System.getInt(
                context.contentResolver,
                "power_enhance_enable",
                -1
            )
            Log.d("UpdateManager", "Power state: $powerState")
        } catch (e: Exception) {
            Log.e("UpdateManager", "Error checking power state: ${e.message}")
        }
    }

    private fun cancelScheduledUpdates(context: Context) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                MainActivity.UPDATE_REQUEST_CODE,
                Intent(context, UpdateReceiver::class.java).apply {
                    action = MainActivity.ACTION_UPDATE
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pendingIntent)
        } catch (e: Exception) {
            Log.e("UpdateManager", "Error cancelling alarms", e)
        }
    }
}