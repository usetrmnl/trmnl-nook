# Turn Your Nook Glowlight 4 (and possibly other models) into a TRMNL Display Client

This repository contains an Android application to turn your Nook e-reader into a TRMNL display client.

## Acknowledgements
- TRMNL team for the API
- Nook development community
    - Renate from XDA forums and MobileReads for figuring out the developer options password.
    - downeaster59 from MobileReads for the instructions on sideloading apps onto Nook.
    - Exameden and Renate from XDA forums for discovering the power_enhance_enable setting to put the device into deepsleep.

## Reported Working On
- Nook Glowlight 4

## Prerequisites
- ADB (Android Debug Bridge)
- TRMNL API key (physical device, BYOD license, or BYOS)

## Installation Steps

### Step 1: Install ADB on Your Computer

#### Windows:
Download and install Minimal ADB and Fastboot

#### Mac:
1. Open Terminal
2. Install Homebrew:
```bash
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/master/install.sh)"
```
3. Install Android Platform Tools:
```bash
brew install android-platform-tools
```

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
2. Open Terminal/Command Prompt
3. Run:
```bash
adb devices
```
4. Verify device is listed

### Step 4: Install F-Droid
1. Download the latest F-Droid APK from [F-Droid.org](https://f-droid.org)
2. Install via ADB (first navigate to APK directory):
```bash
adb install F-Droid.apk
```

### Step 5: Install an Android Launcher
1. Open F-Droid. If you didn't open when installing, use ADB to open it.
2. Search for and install a launcher (KISS Launcher is recomended for its simplicity and low performance impact)
3. Reboot your Nook
4. When prompted to select a launcher:
    - Choose your newly installed launcher
    - Select "Always" to make it default
    - Note: You can still access the stock Nook launcher through your new launcher. Sometimes the original device homescreen will not launch using this icon, but you can reboot the device or use a shortcut maker app to create a shortcut for EpdHomeActivity (original device homescreen).

Why a custom launcher is necessary:
- Stock Nook launcher has no app drawer
- Essential for accessing TRMNL client app

### Step 6: Install trmnl-nook Client
1. Download and install the trmnl-nook apk.
2. Make sure to grant write system settings permission to the trmnl-nook app. This is necessary to put the device into deepsleep.
    1. Navigate to the system settings application.
    2. Apps and Notifications > Advanced > Special app access > Modify system settings > Enable for trmnl-nook

## Configuration

### Basic Setup
Edit the application settings by clicking on the "Battery Level/Last Updated" text to configure:
```json
{
  "BASE_URL": "https://trmnl.app",
  "MAC_ADDRESS": "your-mac-address",
  "API_KEY": "your-api-key",
  "SHOW_DEBUG_INFO": false
}
```
If using your own server, make sure Permit Auto-Join is on.
The device should show up and it will generate a new API key. (For some reason, it does not use the API key originally generated)
Enter this API key in trmnl-nook settings.
Also, on the server modify the resolution to 1448 x 1072 (nook screen resolution).


## How It Works
1. Initial Wake:
    - AlarmManager triggers UpdateReceiver
    - Nook generates phantom headphone event (not sure why this happens, but we can use this event to trigger our application reliably. other methods don't appear to trigger the updates reliably)
    - HeadphoneReceiver catches event and initiates update

2. Power Management:
    - PowerManagerNook wakes device
    - Acquires wake lock
    - Enables and validates WiFi
    - Manages power enhancement states

3. Content Update (DisplayUpdateWorker):
    - Fetches display data from TRMNL server
    - Downloads new image
    - Updates UI through MainActivity
    - Schedules next update
    - Triggers sleep cycle

4. Sleep Cycle:
    - Releases wake lock
    - Disables WiFi
    - Sets power enhancement for sleep
    - Schedules next wake alarm


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


