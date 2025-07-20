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

class UpdateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == MainActivity.ACTION_UPDATE) {
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.Default).launch {
                try {
                    Log.d("UpdateReceiver", "Alarm triggered - ensuring update happens")

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

                    Log.d("UpdateReceiver", "DisplayUpdateWorker enqueued successfully")
                } catch (e: Exception) {
                    Log.e("UpdateReceiver", "Error in UpdateReceiver", e)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}