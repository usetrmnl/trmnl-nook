package com.example.nooktrmnl

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class HeadphoneReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_HEADSET_PLUG) {
            val state = intent.getIntExtra("state", -1)
            Log.d("HeadphoneReceiver", "Headset state changed: $state")

            if (state == 1) {
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.Default).launch {
                    try {
                        Log.d("HeadphoneReceiver", "Attempting wake on headset connect")

                        val powerManager = PowerManagerNook(context)
                        powerManager.wakeForUpdate()

                        val workRequest = OneTimeWorkRequestBuilder<DisplayUpdateWorker>()
                            .setInputData(
                                Data.Builder()
                                    .putInt(DisplayUpdateWorker.KEY_REFRESH_RATE, MainActivity.refreshRate)
                                    .build()
                            )
                            .addTag("com.example.nooktrmnl.DisplayUpdateWorker")
                            .build()

                        WorkManager.getInstance(context).enqueueUniqueWork(
                            "displayUpdate",
                            ExistingWorkPolicy.REPLACE,
                            workRequest
                        )

                        Log.d("HeadphoneReceiver", "DisplayUpdateWorker enqueued successfully")
                        Log.d("HeadphoneReceiver", "Headphone-triggered update completed - alarms left intact")

                    } catch (e: Exception) {
                        Log.e("HeadphoneReceiver", "Error in HeadphoneReceiver", e)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }
}