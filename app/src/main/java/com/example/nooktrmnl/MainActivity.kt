package com.example.nooktrmnl

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private var headphoneReceiver: HeadphoneReceiver? = null
    private var updateReceiver: BroadcastReceiver? = null

    companion object {
        var SHOW_DEBUG_INFO = false
        var BASE_URL = "https://trmnl.app"
        var MAC_ADDRESS = "your-mac-address"
        var API_KEY = "your-api-key"
        var USER_AGENT = "trmnl-display/1.5.11"
        var refreshRate = 60

        const val ACTION_UPDATE = "com.example.nooktrmnl.ACTION_UPDATE"
        const val UPDATE_REQUEST_CODE = 123
        const val ACTION_DISPLAY_UPDATE = "com.example.nooktrmnl.ACTION_DISPLAY_UPDATE"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (intent?.action == ACTION_UPDATE) {
            Log.d("MainActivity", "Launched from update alarm")
        }

        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController?.let { controller ->
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }

        headphoneReceiver = HeadphoneReceiver()
        val headsetFilter = IntentFilter(Intent.ACTION_HEADSET_PLUG)
        registerReceiver(headphoneReceiver, headsetFilter)

        updateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == ACTION_DISPLAY_UPDATE) {
                    Log.d("MainActivity", "Display update broadcast received - triggering recomposition")
                }
            }
        }
        val updateFilter = IntentFilter(ACTION_DISPLAY_UPDATE)
        LocalBroadcastManager.getInstance(this).registerReceiver(updateReceiver!!, updateFilter)

        Log.d("MainActivity", "onCreate: Checking and requesting WRITE_SETTINGS permission")
        checkAndRequestWriteSettingsPermission()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            headphoneReceiver?.let {
                unregisterReceiver(it)
                headphoneReceiver = null
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error unregistering headphone receiver", e)
        }

        try {
            updateReceiver?.let {
                LocalBroadcastManager.getInstance(this).unregisterReceiver(it)
                updateReceiver = null
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error unregistering update receiver", e)
        }
    }

    private fun checkAndRequestWriteSettingsPermission() {
        Log.d("MainActivity", "checkAndRequestWriteSettingsPermission: Checking WRITE_SETTINGS permission")
        if (!Settings.System.canWrite(this)) {
            Log.d("MainActivity", "WRITE_SETTINGS permission not granted. Requesting permission.")
            AlertDialog.Builder(this)
                .setTitle("Permission Required")
                .setMessage("The app needs permission to modify system settings for power management. Please grant this permission on the next screen.")
                .setPositiveButton("OK") { _, _ ->
                    Log.d("MainActivity", "User agreed to request WRITE_SETTINGS permission.")
                    Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(this)
                    }
                }
                .setNegativeButton("Cancel") { dialog, _ ->
                    dialog.dismiss()
                    Log.w("MainActivity", "User declined to request WRITE_SETTINGS permission.")
                }
                .show()
        } else {
            Log.d("MainActivity", "WRITE_SETTINGS permission already granted.")
            loadSettings()

            setContent {
                TrmnlDisplay()
            }

            lifecycleScope.launch {
                val powerManager = PowerManagerNook(this@MainActivity)
                if (powerManager.ensureWifiOn()) {
                    Log.d("MainActivity", "WiFi ensured on first boot")
                    triggerInitialUpdate()
                } else {
                    Log.w("MainActivity", "Failed to ensure WiFi on first boot")
                }
            }
        }
    }

    private fun triggerInitialUpdate() {
        val workRequest = OneTimeWorkRequestBuilder<DisplayUpdateWorker>()
            .addTag("com.example.nooktrmnl.DisplayUpdateWorker")
            .build()

        WorkManager.getInstance(this).enqueueUniqueWork(
            "displayUpdate",
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
    }

    private fun loadSettings() {
        val sharedPreferences = getSharedPreferences("AppSettings", Context.MODE_PRIVATE)
        BASE_URL = sharedPreferences.getString("BASE_URL", BASE_URL) ?: BASE_URL
        MAC_ADDRESS = sharedPreferences.getString("MAC_ADDRESS", MAC_ADDRESS) ?: MAC_ADDRESS
        API_KEY = sharedPreferences.getString("API_KEY", API_KEY) ?: API_KEY
        USER_AGENT = sharedPreferences.getString("USER_AGENT", USER_AGENT) ?: USER_AGENT
        SHOW_DEBUG_INFO = sharedPreferences.getBoolean("SHOW_DEBUG_INFO", SHOW_DEBUG_INFO)
    }
}

@Composable
fun TrmnlDisplay() {
    val context = LocalContext.current
    var imageState: Bitmap? by remember { mutableStateOf(null) }
    var refreshRate by remember { mutableStateOf(60) }
    var debugInfo by remember { mutableStateOf("Initializing...") }
    var lastUpdated by remember { mutableStateOf("") }
    var batteryLevel by remember { mutableStateOf(100) }
    var updateTrigger by remember { mutableStateOf(0) }

    val screenDimensions = remember {
        val prefs = context.getSharedPreferences("AppSettings", Context.MODE_PRIVATE)
        val width = prefs.getInt("screen_width", 0)
        val height = prefs.getInt("screen_height", 0)

        if (width > 0 && height > 0) {
            ScreenDimensions(width, height)
        } else {
            val dims = getScreenDimensions(context)
            prefs.edit()
                .putInt("screen_width", dims.width)
                .putInt("screen_height", dims.height)
                .apply()
            dims
        }
    }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == MainActivity.ACTION_DISPLAY_UPDATE) {
                    Log.d("TrmnlDisplay", "Update broadcast received")
                    updateTrigger++
                }
            }
        }
        val filter = IntentFilter(MainActivity.ACTION_DISPLAY_UPDATE)
        LocalBroadcastManager.getInstance(context).registerReceiver(receiver, filter)

        onDispose {
            LocalBroadcastManager.getInstance(context).unregisterReceiver(receiver)
        }
    }

    LaunchedEffect(updateTrigger) {
        if (updateTrigger == 0) {
            Log.d("TrmnlDisplay", "Skipping initial network request - DisplayUpdateWorker will handle it")
            return@LaunchedEffect
        }

        val powerManager = PowerManagerNook(context)

        if (!powerManager.ensureWifiOn()) {
            debugInfo = "Failed to enable WiFi"
            Log.e("TrmnlDisplay", "Failed to enable WiFi")
            return@LaunchedEffect
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        val needsSetup = !isDeviceSetup(context)
        if (needsSetup) {
            try {
                debugInfo = "Setting up device..."
                setupDevice(client)
                markDeviceSetup(context)
                debugInfo = "Device setup successful"
            } catch (e: Exception) {
                Log.e("TrmnlDisplay", "Setup failed", e)
                debugInfo = "Setup failed: ${e.message}"
                return@LaunchedEffect
            }
        }

        var attempts = 0
        val maxAttempts = 3

        while (attempts < maxAttempts) {
            try {
                debugInfo = "Updating..."
                batteryLevel = getBatteryLevel(context)

                val displayData = fetchDisplayData(
                    client = client,
                    batteryLevel = batteryLevel,
                    pngWidth = screenDimensions.width,
                    pngHeight = screenDimensions.height
                )

                val imageUrl = displayData.getString("image_url").replace("\\u0026", "&")
                refreshRate = displayData.optInt("refresh_rate", 60)
                val filename = displayData.optString("filename", "display.png")

                Log.d("TrmnlDisplay", "API Response JSON: $displayData")
                Log.d("TrmnlDisplay", "Parsed refresh rate: $refreshRate seconds")

                MainActivity.refreshRate = refreshRate

                imageState = loadImageOptimized(context, imageUrl, client)

                debugInfo = """
                    URL: $imageUrl
                    File: $filename
                    Refresh: ${refreshRate}s
                    Screen: ${screenDimensions.width}x${screenDimensions.height}
                    Battery: ${batteryLevel}%
                """.trimIndent()

                lastUpdated = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                Log.d("TrmnlDisplay", "Update completed successfully")

                val completionIntent = Intent(DisplayUpdateWorker.ACTION_NETWORK_COMPLETE).apply {
                    putExtra("refresh_rate", refreshRate)
                    putExtra("from_source", "api_response")
                }
                Log.d("TrmnlDisplay", "Sending completion notification with refresh_rate: $refreshRate")
                LocalBroadcastManager.getInstance(context).sendBroadcast(completionIntent)

                break

            } catch (e: Exception) {
                attempts++
                if (attempts >= maxAttempts) {
                    Log.e("TrmnlDisplay", "Error fetching display after $maxAttempts attempts", e)
                    debugInfo = "Error: ${e.localizedMessage ?: e.message ?: "Unknown error"}"

                    val completionIntent = Intent(DisplayUpdateWorker.ACTION_NETWORK_COMPLETE).apply {
                        putExtra("refresh_rate", MainActivity.refreshRate)
                        putExtra("from_source", "error_fallback")
                    }
                    Log.e("TrmnlDisplay", "Sending error completion notification with refresh_rate: ${MainActivity.refreshRate}")
                    LocalBroadcastManager.getInstance(context).sendBroadcast(completionIntent)
                    break
                } else {
                    Log.e("TrmnlDisplay", "Update attempt $attempts failed", e)
                    delay(1000)
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        if (updateTrigger == 0) {
            Log.d("TrmnlDisplay", "First launch - triggering initial update")
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
    ) {
        imageState?.let { bitmap ->
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "TRMNL Display",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .align(Alignment.Center)
            )
        }

        if (MainActivity.SHOW_DEBUG_INFO) {
            Text(
                text = debugInfo,
                color = Color.Black,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp)
                    .background(Color.LightGray.copy(alpha = 0.5f))
                    .padding(4.dp)
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp)
                .background(Color.LightGray.copy(alpha = 0.5f))
                .padding(4.dp)
                .clickable {
                    context.startActivity(Intent(context, SettingsActivity::class.java))
                }
        ) {
            Text(
                text = "Battery: $batteryLevel%",
                color = Color.Black,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Updated: $lastUpdated",
                color = Color.Black,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

suspend fun loadImageOptimized(
    context: Context,
    imageUrl: String,
    client: OkHttpClient
): Bitmap? = withContext(Dispatchers.IO) {
    try {
        val request = Request.Builder()
            .url(imageUrl)
            .addHeader("Accept", "image/*")
            .build()

        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                response.body?.byteStream()?.use { inputStream ->
                    val bufferedStream = inputStream.buffered(8192)

                    val options = BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.RGB_565
                    }

                    BitmapFactory.decodeStream(bufferedStream, null, options)
                }
            } else {
                Log.e("ImageLoader", "Failed to load image: HTTP ${response.code}")
                null
            }
        }
    } catch (e: Exception) {
        Log.e("ImageLoader", "Failed to load image", e)
        null
    }
}

fun isDeviceSetup(context: Context): Boolean {
    val prefs = context.getSharedPreferences("AppSettings", Context.MODE_PRIVATE)
    val lastSetup = prefs.getLong("last_setup_time", 0)
    val daysSinceSetup = (System.currentTimeMillis() - lastSetup) / (1000 * 60 * 60 * 24)
    return daysSinceSetup < 7
}

fun markDeviceSetup(context: Context) {
    val prefs = context.getSharedPreferences("AppSettings", Context.MODE_PRIVATE)
    prefs.edit().putLong("last_setup_time", System.currentTimeMillis()).apply()
}

suspend fun setupDevice(client: OkHttpClient) = withContext(Dispatchers.IO) {
    val url = "${MainActivity.BASE_URL}/api/setup/"
    Log.d("TrmnlDisplay", "Setting up device at URL: $url")

    val request = Request.Builder()
        .url(url)
        .addHeader("ID", MainActivity.MAC_ADDRESS)
        .addHeader("Accept", "application/json")
        .addHeader("Content-Type", "application/json")
        .build()

    client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
            val errorBody = response.body?.string()
            Log.e("TrmnlDisplay", "Setup error response: $errorBody")
            throw IOException("Setup failed with code ${response.code}. Error: $errorBody")
        }

        Log.d("TrmnlDisplay", "Device setup successful")
    }
}

suspend fun fetchDisplayData(
    client: OkHttpClient,
    batteryLevel: Int,
    pngWidth: Int,
    pngHeight: Int
): JSONObject = withContext(Dispatchers.IO) {
    val url = "${MainActivity.BASE_URL}/api/display"
    Log.d("TrmnlDisplay", "Requesting URL: $url")

    val request = Request.Builder()
        .url(url)
        .addHeader("ID", MainActivity.MAC_ADDRESS)
        .addHeader("Access-Token", MainActivity.API_KEY)
        .addHeader("Accept", "application/json")
        .addHeader("Content-Type", "application/json")
        .addHeader("battery-level", batteryLevel.toString())
        .addHeader("png-width", pngWidth.toString())
        .addHeader("png-height", pngHeight.toString())
        .addHeader("rssi", "0")
        .addHeader("User-Agent", MainActivity.USER_AGENT)
        .build()

    try {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string()
                Log.e("TrmnlDisplay", "Error response: $errorBody")
                throw IOException("Unexpected code ${response.code}. Error: $errorBody")
            }

            val responseBody = response.body?.string()
            if (responseBody.isNullOrEmpty()) {
                throw IOException("Empty response body")
            }

            try {
                return@withContext JSONObject(responseBody)
            } catch (e: Exception) {
                Log.e("TrmnlDisplay", "Invalid JSON: $responseBody")
                throw IOException("Invalid JSON response: ${e.message}")
            }
        }
    } catch (e: Exception) {
        Log.e("TrmnlDisplay", "Network error", e)
        throw e
    }
}

data class ScreenDimensions(val width: Int, val height: Int)

fun getScreenDimensions(context: Context): ScreenDimensions {
    val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    val displayMetrics = DisplayMetrics()

    @Suppress("DEPRECATION")
    windowManager.defaultDisplay.getMetrics(displayMetrics)

    return ScreenDimensions(
        width = displayMetrics.heightPixels,
        height = displayMetrics.widthPixels
    )
}

fun getBatteryLevel(context: Context): Int {
    val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

    return try {
        batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    } catch (e: Exception) {
        Log.e("BatteryLevel", "Could not retrieve battery level", e)
        100
    }
}