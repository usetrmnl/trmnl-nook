# Turn Your Nook Glowlight 4 (and possibly other models) into a TRMNL Display Client

This repository contains an Android application to turn your Nook e-reader into a TRMNL display client.

<kdb>
  <div style="display: flex; justify-content: space-between; width: 800px;">
    <img src="https://github.com/usetrmnl/trmnl-nook/blob/main/images/trmnl-nook6.png?raw=true" width="900px">
    <img src="https://github.com/usetrmnl/trmnl-nook/blob/main/images/trmnl-nook4.png?raw=true" height="250px">
    <img src="https://github.com/usetrmnl/trmnl-nook/blob/main/images/trmnl-nook5.png?raw=true" height="250px">
    <img src="https://github.com/usetrmnl/trmnl-nook/blob/main/images/trmnl-nook3.png?raw=true" height="250px">
  </div>
</kdb>

## Acknowledgements
- TRMNL team for the API
- Nook development community
    - Renate from XDA forums and MobileReads for figuring out the developer options password
    - downeaster59 from MobileReads for the instructions on sideloading apps onto Nook
    - Exameden and Renate from XDA forums for discovering the power_enhance_enable setting to put the device into deep sleep

## Reported Working On
- Nook Glowlight 4

## Prerequisites
- ADB (Android Debug Bridge)
- TRMNL API key (physical device, BYOD license, or BYOS)

## Installation Steps

### Step 1: Install ADB on Your Computer

Download and install Android Platform Tools depending on your OS:
https://developer.android.com/tools/releases/platform-tools

### Step 2: Prepare Your Nook
1. Tap upper right corner for Quick Settings
2. Tap "See All Settings"
3. Go to page 2 (lower right arrow)
4. Tap "About"
5. Tap Nook icon 5 times rapidly
6. Enter the password: `NOOK-BNRV1100`
7. Tap "Android Development Settings"
8. Enable USB Debugging

### Step 3: Test ADB Connection
1. Connect Nook to computer via USB
2. Open Terminal/Command Prompt/PowerShell
3. Run:
```bash
adb devices
```
4. Verify device is listed

### Step 4: Install F-Droid
F-Droid is an open-source app store and is recommended to install as there is no other app store on Nook and it is very difficult/impossible to get Google Play Store on it. We will use F-Droid to download a launcher.
1. Download the latest F-Droid APK from [F-Droid.org](https://f-droid.org)
2. Navigate to APK directory (replace with your path to the APK directory):
```bash
cd c:\Users\User\Downloads\
```
3. Install via ADB:
```bash
adb install F-Droid.apk
```

### Step 5: Install an Android Launcher
1. Open F-Droid. If it didn't open when installing, use ADB to open it:
```bash
adb shell monkey -p org.fdroid.fdroid -c android.intent.category.LAUNCHER 1
```
2. Search for and install a launcher (KISS Launcher is recommended for its simplicity and low performance impact)
3. Reboot your Nook
4. When prompted to select a launcher:
    - Choose your newly installed launcher
    - Select "Always" to make it default
    - Note: You can still access the stock Nook launcher through your new launcher. Sometimes the original device homescreen will not launch using this icon, but you can reboot the device or use a shortcut maker app to create a shortcut for EpdHomeActivity (original device homescreen).

Why a custom launcher is necessary:
- Stock Nook launcher has no app drawer
- Essential for accessing TRMNL client app

### Step 6: Install trmnl-nook Client
1. Download and install the trmnl-nook APK (under the releases section)
2. Make sure to grant write system settings permission to the trmnl-nook app. This is necessary to put the device into deep sleep and to modify the default maximum screen timeout of 1 hour.
    1. Navigate to the system settings application
    2. Apps and Notifications > Advanced > Special app access > Modify system settings > Enable for trmnl-nook

## Configuration

### Basic Setup
Edit the application settings by clicking on the "Battery Level/Last Updated" text to configure:
```json
{
  "BASE_URL": "https://trmnl.app",
  "MAC_ADDRESS": "your-mac-address",
  "API_KEY": "your-api-key",
  "SHOW_DEBUG_INFO": false,
  "SCREEN_TIMEOUT": "30 days"
}
```

### Screen Timeout Configuration
**Important**: Set the screen timeout to a long duration for unattended operation.

**How to configure**: In the trmnl-nook app settings, use the "Screen Timeout" dropdown to select your preferred duration. This setting prevents the display from turning off during long-term operation.

### Server Configuration
If using your own server, make sure Permit Auto-Join is on.
The device should show up and it will generate a new API key. (For some reason, it does not use the API key originally generated)
Enter this API key in trmnl-nook settings.
Also, on the server modify your device resolution to 1448 x 1072 (Nook screen resolution).

## How It Works

### Deep Sleep/Wake Management
To put the device into deep sleep after updating the display, we use the power_enhance_enable setting. Waking the device and reliably keeping the device awake during updates was tricky due to the aggressive nature of the power_enhance_enable setting. The device does not appear to have any method to wake without user interaction, even setting alarms does not work to wake the device from deep sleep. When checking the logs during the deep sleep states, it did appear there was some activity that started around the time when alarms should have gone off. Mainly, battery level changes, screen timeout countdown, and there appeared to be some phantom headphone connection events as well. Since the battery level changes were far between in deep sleep and the screen timeout is customizable, we utilize the phantom headphone events to help enhance the waking of the device after the alarm puts the device into the half-way wake state. To reliably wake and keep the device awake during updates we:

1. **Set an alarm before sleep** - Provides consistent timing for updates
2. **Capture phantom headphone events** - Use them to immediately set a wakelock
3. **Use file logging** - Maintains storage subsystem activity to keep the device awake during updates

### Program Operation Flow

```
┌─────────────────────────────────────────────────────────────────┐
│                        INITIAL STARTUP                         │
├─────────────────────────────────────────────────────────────────┤
│ 1. MainActivity launches → Check permissions                   │
│ 2. Request WRITE_SETTINGS permission → Enable deep sleep       │
│ 3. Load configuration → BASE_URL, API_KEY, MAC_ADDRESS         │
│ 4. Register HeadphoneReceiver → Listen for phantom events      │
│ 5. Trigger initial update → DisplayUpdateWorker starts         │
│ 6. Schedule first alarm → Refresh rate grabbed from server     │
└─────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────┐
│                      SCHEDULED UPDATE CYCLE                    │
├─────────────────────────────────────────────────────────────────┤
│ 1. AlarmManager fires → Device enters partial wake state       │
│ 2. HeadphoneReceiver triggered → Call PowerManagerNook.wake()  │
│ 3. Enqueue DisplayUpdateWorker → Start update process          │
│ 4. WorkManager launches worker → Begin system activity         │
└─────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────┐
│                    DISPLAYUPDATEWORKER PROCESS                 │
├─────────────────────────────────────────────────────────────────┤
│ 1. Start activity logging → Keep system components awake       │
│ 2. Enable WiFi → PowerManagerNook.ensureWifiOn()              │
│    ├─ Attempt 1 → Wait 2 seconds if failed                    │
│    ├─ Attempt 2 → Wait 4 seconds if failed                    │
│    └─ Attempt 3 → Return failure if still failed              │
│ 3. Register broadcast receivers → Listen for completion        │
│ 4. Trigger MainActivity update → Send ACTION_DISPLAY_UPDATE    │
└─────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────┐
│                      MAINACTIVITY UPDATE                       │
├─────────────────────────────────────────────────────────────────┤
│ 1. Receive broadcast → TrmnlDisplay recomposition triggered    │
│ 2. Setup device (if needed) → POST to /api/setup              │
│ 3. Fetch display data → GET /api/display with headers:         │
│    ├─ ID: MAC_ADDRESS                                          │
│    ├─ Access-Token: API_KEY                                    │
│    ├─ battery-level: current battery %                         │
│    ├─ png-width: 1448, png-height: 1072                       │
│    └─ User-Agent: trmnl-display/1.5.11                        │
│ 4. Parse JSON response → Extract image_url, refresh_rate       │
│ 5. Download image → HTTP GET image_url                         │
│ 6. Update UI → Image composable with new bitmap                │
│ 7. Send network completion → ACTION_NETWORK_COMPLETE           │
└─────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────┐
│                    DISPLAYUPDATEWORKER COMPLETION              │
├─────────────────────────────────────────────────────────────────┤
│ 1. Wait for network completion → Timeout after 30 seconds      │
│ 2. Wait for screen completion → Timeout after 15 seconds       │
│ 3. Schedule next alarm → Single alarm in {refresh_rate} secs   │
│ 4. Disable WiFi → PowerManagerNook.goToSleep()                │
│ 5. Stop activity logging → Allow system to enter deep sleep    │
│ 6. Return SUCCESS → WorkManager marks job complete             │
└─────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────┐
│                         DEEP SLEEP                             │
├─────────────────────────────────────────────────────────────────┤
│ Device enters custom Nook deep sleep mode:                     │
│ • Screen off, WiFi disabled, minimal power usage               │
│ • AlarmManager maintains next wake schedule                    │
│ • Wait {refresh_rate} seconds (typically 900s = 15 minutes)    │
└─────────────────────────────────────────────────────────────────┘
```

### Error Handling & Recovery

```
┌─────────────────────────────────────────────────────────────────┐
│                          ERROR SCENARIOS                       │
├─────────────────────────────────────────────────────────────────┤
│ WiFi Enable Failure:                                           │
│ └─ Retry 3 times → Return RETRY → WorkManager reschedules      │
│                                                                 │
│ Network Request Timeout:                                       │
│ └─ 30 second timeout → Use fallback refresh rate → Continue    │
│                                                                 │
│ Image Download Failure:                                        │
│ └─ Retry 3 times → Log error → Send completion anyway          │
│                                                                 │
│ Screen Refresh Timeout:                                        │
│ └─ 15 second timeout → Log warning → Proceed to sleep          │
└─────────────────────────────────────────────────────────────────┘
```

### Key Components

**UpdateReceiver**: Responds to scheduled alarms, provides wake amplification  
**DisplayUpdateWorker**: Main update logic, maintains system activity, handles completion  
**HeadphoneReceiver**: Processes phantom headphone events to ensure device immediately acquires wakelock  
**PowerManagerNook**: Nook-specific wake/sleep management and WiFi control  
**MainActivity/TrmnlDisplay**: UI updates, API communication, image display

## Troubleshooting

### Issues
- **Power management**:
    - Verify WRITE_SETTINGS permission

- **Display issues**:
    - When switching to another app and then back to trmnl-nook, the status bar will display. Enter the settings screen and save to fix this.

Force quitting and restarting the app usually fixes most issues.

### Debug Mode
Enable debug mode in settings to see:
- Network requests
- Update cycle timing
- Error messages

## Version History
- 1.0.0: Initial release


