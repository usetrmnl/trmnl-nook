package com.example.nooktrmnl

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SettingsScreen()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    var baseUrl by remember { mutableStateOf(MainActivity.BASE_URL) }
    var macAddress by remember { mutableStateOf(MainActivity.MAC_ADDRESS) }
    var apiKey by remember { mutableStateOf(MainActivity.API_KEY) }
    var userAgent by remember { mutableStateOf(MainActivity.USER_AGENT) }
    var showDebugInfo by remember { mutableStateOf(MainActivity.SHOW_DEBUG_INFO) }

    val timeoutOptions = listOf(
        "2 minutes" to 120000,
        "15 days" to 1296000000,
        "30 days" to 2592000000L.toInt(),
        "Never" to Int.MAX_VALUE
    )

    val currentTimeout = remember {
        try {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, 60000)
        } catch (e: Exception) {
            60000
        }
    }

    val initialSelection = timeoutOptions.indexOfFirst { it.second == currentTimeout }.let { index ->
        if (index >= 0) index else 0
    }

    var selectedTimeoutIndex by remember { mutableIntStateOf(initialSelection) }
    var timeoutDropdownExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("Base URL") },
            modifier = Modifier.fillMaxWidth()
        )

        TextField(
            value = macAddress,
            onValueChange = { macAddress = it },
            label = { Text("MAC Address") },
            modifier = Modifier.fillMaxWidth()
        )

        TextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("API Key") },
            modifier = Modifier.fillMaxWidth()
        )

        TextField(
            value = userAgent,
            onValueChange = { userAgent = it },
            label = { Text("User Agent") },
            modifier = Modifier.fillMaxWidth()
        )

        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Screen Timeout",
                modifier = Modifier.padding(bottom = 4.dp)
            )
            ExposedDropdownMenuBox(
                expanded = timeoutDropdownExpanded,
                onExpandedChange = { timeoutDropdownExpanded = !timeoutDropdownExpanded },
                modifier = Modifier.fillMaxWidth()
            ) {
                TextField(
                    value = timeoutOptions[selectedTimeoutIndex].first,
                    onValueChange = { },
                    readOnly = true,
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = timeoutDropdownExpanded)
                    },
                    colors = ExposedDropdownMenuDefaults.textFieldColors(),
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = timeoutDropdownExpanded,
                    onDismissRequest = { timeoutDropdownExpanded = false }
                ) {
                    timeoutOptions.forEachIndexed { index, (label, _) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                selectedTimeoutIndex = index
                                timeoutDropdownExpanded = false
                            }
                        )
                    }
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Show Debug Info")
            Spacer(modifier = Modifier.weight(1f))
            Switch(
                checked = showDebugInfo,
                onCheckedChange = { showDebugInfo = it }
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = {
                saveSettings(
                    context = context,
                    baseUrl = baseUrl,
                    macAddress = macAddress,
                    apiKey = apiKey,
                    userAgent = userAgent,
                    showDebugInfo = showDebugInfo,
                    screenTimeoutMs = timeoutOptions[selectedTimeoutIndex].second
                )
                (context as? ComponentActivity)?.finish()
            },
            modifier = Modifier.align(Alignment.End),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = Color.White,
                contentColor = Color.Black
            ),
            border = BorderStroke(1.dp, Color.Black)
        ) {
            Text("Save")
        }
    }
}

fun saveSettings(
    context: Context,
    baseUrl: String,
    macAddress: String,
    apiKey: String,
    userAgent: String,
    showDebugInfo: Boolean,
    screenTimeoutMs: Int
) {
    val sharedPreferences: SharedPreferences = context.getSharedPreferences("AppSettings", Context.MODE_PRIVATE)
    val editor = sharedPreferences.edit()
    editor.putString("BASE_URL", baseUrl)
    editor.putString("MAC_ADDRESS", macAddress)
    editor.putString("API_KEY", apiKey)
    editor.putString("USER_AGENT", userAgent)
    editor.putBoolean("SHOW_DEBUG_INFO", showDebugInfo)
    editor.putInt("SCREEN_TIMEOUT_MS", screenTimeoutMs)
    editor.apply()

    MainActivity.BASE_URL = baseUrl
    MainActivity.MAC_ADDRESS = macAddress
    MainActivity.API_KEY = apiKey
    MainActivity.USER_AGENT = userAgent
    MainActivity.SHOW_DEBUG_INFO = showDebugInfo

    try {
        Settings.System.putInt(
            context.contentResolver,
            Settings.System.SCREEN_OFF_TIMEOUT,
            screenTimeoutMs
        )
        Log.d("SettingsActivity", "Screen timeout set to ${screenTimeoutMs}ms")
    } catch (e: Exception) {
        Log.e("SettingsActivity", "Failed to set screen timeout", e)
    }
}