# Photo Uploader - Android App

A Material Design 3 Android application for securely uploading photos to Amazon S3 with device-specific encryption and intelligent duplicate detection.

## Features

### Photo Upload
- **Manual Upload** - Select and upload individual photos
- **Date Range Backup** - Automatically backup all photos from a selected date range
- **Duplicate Detection** - Skips already uploaded photos based on EXIF timestamp
- **WiFi Awareness** - Prompts confirmation when uploading over cellular data
- **Upload Management** - Cancel ongoing uploads, retry failed uploads
- **Progress Tracking** - Real-time upload progress with photo count

### Security & Privacy
- **Secure Authentication** - AWS Cognito Identity Pools (NO access keys stored)
- **Device GUID** - Unique device identifier for secure folder isolation
- **Encrypted Paths** - Photos uploaded to `email_guid/YYYY-MM/timestamp.jpg`
- **Account Management** - Separate folders per Google account

### User Experience
- **Material Design 3** - Modern, clean interface
- **Settings Page** - View device storage stats and account info
- **Toggle Modes** - Switch between manual and date range upload
- **Photo Preview** - Preview selected photos before upload
- **Smart Organization** - Photos organized by month and timestamp

## Prerequisites

Before building and deploying this app, you need:

1. **Development Environment**
   - Android Studio Arctic Fox or later
   - JDK 17+
   - Android SDK (API 24-34)
   - ADB (Android Debug Bridge) for device installation

2. **AWS Resources**
   - AWS Account
   - S3 Bucket (private recommended)
   - Cognito Identity Pool (unauthenticated access enabled)

## AWS Setup

### 1. Create S3 Bucket

1. Log in to AWS Console → Navigate to **S3**
2. Create a new bucket (e.g., `photos-sinky`)
3. **Permissions**: Keep bucket private (not public)
4. Note your bucket name and region (e.g., `us-east-1`)

### 2. Create Cognito Identity Pool

1. Go to **AWS Cognito** → **Identity Pools** → **Create identity pool**
2. Enter pool name (e.g., `PhotoUploaderIdentityPool`)
3. Check **"Enable access to unauthenticated identities"**
4. Click **Create Pool** → **Allow** to create IAM roles
5. Copy your **Identity Pool ID** (format: `us-east-1:xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`)

### 3. Configure IAM Role Permissions

The Cognito IAM role needs permissions to upload and check for duplicate files:

1. Go to **IAM** → **Roles**
2. Find role `Cognito_YourPoolNameUnauth_Role`
3. Click **Add permissions** → **Create inline policy**
4. Switch to **JSON** tab and paste:

```json
{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Effect": "Allow",
            "Action": [
                "s3:PutObject",
                "s3:PutObjectAcl",
                "s3:GetObject"
            ],
            "Resource": "arn:aws:s3:::photos-sinky/*"
        }
    ]
}
```

5. Replace `photos-sinky` with your bucket name
6. Click **Review policy**, name it `S3PhotoUploadPolicy`, and **Create policy**

**Note:** `s3:GetObject` is required for duplicate detection to check if files already exist.

## App Configuration

### Configure AWS Credentials

Edit `app/src/main/res/values/strings.xml`:

```xml
<resources>
    <string name="app_name">Photo Uploader</string>

    <!-- AWS Configuration -->
    <string name="aws_bucket_name">photos-sinky</string>
    <string name="aws_region">us-east-1</string>
    <string name="aws_identity_pool_id">us-east-1:xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx</string>

    <!-- UI Strings -->
    <string name="select_image_first">Please select an image first</string>
</resources>
```

Replace:
- `photos-sinky` with your S3 bucket name
- `us-east-1` with your bucket's region
- `us-east-1:xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx` with your Cognito Identity Pool ID

## Developer Installation Guide

### Building the APK

1. **Open Project in Android Studio**
   ```bash
   cd /path/to/sinky
   # Open project in Android Studio
   ```

2. **Sync Gradle**
   - Android Studio will automatically sync Gradle dependencies
   - Wait for sync to complete

3. **Build Debug APK**
   ```bash
   ./gradlew assembleDebug
   ```

   The APK will be generated at:
   ```
   app/build/outputs/apk/debug/app-debug.apk
   ```

### Installing on Android Device via ADB

#### Prerequisites
- Android device with USB debugging enabled
- ADB installed on your development machine

#### Enable USB Debugging on Device

**Samsung Galaxy (One UI):**
1. Open **Settings** → **About phone**
2. Tap **Software information**
3. Tap **Build number** 7 times (Developer mode activated)
4. Go back to **Settings** → **Developer options**
5. Enable **USB debugging**

**Stock Android:**
1. Open **Settings** → **About phone**
2. Tap **Build number** 7 times
3. Go back to **Settings** → **System** → **Developer options**
4. Enable **USB debugging**

#### Connect Device

1. Connect device via USB cable
2. Allow USB debugging when prompted on device
3. Verify connection:
   ```bash
   adb devices
   ```

   You should see:
   ```
   List of devices attached
   DEVICEID    device
   ```

#### Install APK

**First Installation:**
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

**Update Existing Installation:**
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The `-r` flag reinstalls the app keeping existing data.

#### Common ADB Issues

**Device Not Found:**
```bash
adb kill-server
adb start-server
adb devices
```

**Multiple Devices:**
```bash
adb devices
adb -s DEVICE_ID install -r app-debug.apk
```

**Installation Failed:**
- Uninstall existing app from device
- Check device has sufficient storage
- Ensure USB debugging is enabled
- Try different USB cable/port

### Building from Command Line

```bash
# Clean build
./gradlew clean

# Build debug APK
./gradlew assembleDebug

# Build and install
./gradlew installDebug

# Run on connected device
./gradlew installDebug
adb shell am start -n com.example.s3photouploader/.MainActivity
```

## Project Architecture

### File Organization in S3

Photos are uploaded with the following structure:

```
s3://photos-sinky/
├── user@example.com_a1b2c3d4-e5f6-7890-abcd-ef1234567890/
│   ├── 2024-12/
│   │   ├── 2024-12-01_10-30-45.jpg
│   │   ├── 2024-12-01_14-20-15.jpg
│   │   └── 2024-12-02_09-15-30.jpg
│   ├── 2024-11/
│   │   └── 2024-11-28_18-45-00.jpg
```

- **First Level**: `email_deviceGUID` (unique per device)
- **Second Level**: `YYYY-MM` (month folder)
- **File Name**: `YYYY-MM-DD_HH-MM-SS.jpg` (EXIF timestamp or current time)

### Project Structure

```
app/src/main/
├── java/com/example/s3photouploader/
│   ├── MainActivity.kt          # Main screen: upload/backup logic
│   ├── SettingsActivity.kt      # Settings: storage stats, account info
│   ├── CognitoS3Uploader.kt     # S3 upload with Cognito auth & duplicate detection
│   └── AccountHelper.kt         # User email & device GUID management
├── res/
│   ├── layout/
│   │   ├── activity_main.xml        # Main screen layout
│   │   └── activity_settings.xml    # Settings screen layout
│   ├── menu/
│   │   └── main_menu.xml            # App menu (Settings, Change Account)
│   ├── values/
│   │   ├── strings.xml              # AWS configuration, app strings
│   │   ├── colors.xml               # Material blue theme
│   │   └── themes.xml               # Material Design 3 theme
│   └── xml/
│       ├── backup_rules.xml
│       └── data_extraction_rules.xml
└── AndroidManifest.xml
```

### Key Components

**MainActivity.kt**
- Toggle between manual and date range modes
- Manual photo selection (multiple photos)
- Date range query and backup
- WiFi/cellular data check
- Upload progress tracking
- Duplicate handling
- Cancel/retry functionality

**CognitoS3Uploader.kt**
- Cognito credential provider
- EXIF timestamp extraction
- S3 duplicate detection (`s3:GetObject`)
- Upload with progress tracking
- Cancellation support
- Timeout handling (2 minutes per photo)

**AccountHelper.kt**
- Google account selection
- Device GUID generation (one-time, persistent)
- Secure upload prefix: `email_guid`
- SharedPreferences storage

**SettingsActivity.kt**
- Device photo count and storage usage
- Account email display
- Material Design 3 cards

## Dependencies

```gradle
dependencies {
    // AndroidX
    implementation 'androidx.core:core-ktx:1.12.0'
    implementation 'androidx.appcompat:appcompat:1.6.1'
    implementation 'androidx.lifecycle:lifecycle-runtime-ktx:2.6.2'

    // Material Design 3
    implementation 'com.google.android.material:material:1.11.0'

    // AWS SDK
    implementation 'com.amazonaws:aws-android-sdk-s3:2.73.0'
    implementation 'com.amazonaws:aws-android-sdk-core:2.73.0'
    implementation 'com.amazonaws:aws-android-sdk-cognitoidentityprovider:2.73.0'
    implementation 'com.amazonaws:aws-android-sdk-mobile-client:2.73.0'

    // EXIF metadata
    implementation 'androidx.exifinterface:exifinterface:1.3.6'

    // Kotlin Coroutines
    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3'
}
```

## Permissions

Required permissions in `AndroidManifest.xml`:

```xml
<!-- Photos -->
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32" />
<uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />

<!-- Network -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

## Using the App

### First Launch

1. **Account Setup**
   - App prompts to select Google account
   - Device GUID is automatically generated
   - Photos will be uploaded to `email_guid/` folder

### Manual Upload

1. Tap **"Manual Select"** mode
2. Tap **"Select Photos"**
3. Choose one or multiple photos
4. Tap **"Upload Photos"**
5. Confirm cellular data usage if not on WiFi
6. Watch upload progress

### Date Range Backup

1. Tap **"By Date Range"** mode
2. Tap **"Start Date"** → select date
3. Tap **"End Date"** → select date
4. App automatically finds photos in range
5. Button shows: **"Start Backup (X photos)"**
6. Tap button to begin upload
7. Confirm cellular data usage if not on WiFi

### Settings

1. Menu (⋮) → **Settings**
2. View:
   - Total photos on device
   - Storage used by photos
   - Account email

### Change Account

1. Menu (⋮) → **Change Account**
2. Select different Google account
3. New uploads go to `newemail_guid/` folder

## Features Explained

### Duplicate Detection

- Checks if file exists in S3 before uploading
- Based on EXIF timestamp: `YYYY-MM-DD_HH-MM-SS.jpg`
- Skips upload if file exists (requires `s3:GetObject` permission)
- Shows count: "Uploaded X, skipped Y (already uploaded)"

### WiFi Awareness

- Checks network type before upload
- If on cellular: shows confirmation dialog
- User can choose to continue or cancel
- Helps prevent unexpected data usage

### Upload Management

- **Cancel**: Stop ongoing uploads anytime
- **Retry**: Retry only failed uploads
- **Timeout**: 2-minute timeout per photo
- **Progress**: Real-time status for each photo

### Security Features

- **No AWS Keys**: Uses Cognito temporary credentials
- **Device GUID**: Unique folder per device prevents collisions
- **Account Isolation**: Each email gets separate folder
- **GUID Hidden**: GUID never shown to user for security

## Troubleshooting

### Uploads Failing

1. Check AWS configuration in `strings.xml`
2. Verify Cognito Identity Pool ID format
3. Ensure IAM role has `s3:PutObject` and `s3:GetObject`
4. Check internet connection
5. Review logcat for detailed errors:
   ```bash
   adb logcat | grep -E "(CognitoS3Uploader|MainActivity)"
   ```

### Duplicate Detection Not Working

1. Ensure IAM role includes `s3:GetObject` permission
2. Check logs for 403 (permission denied) errors
3. Verify files are named with correct timestamp format
4. Check logcat for "File already exists" or "File doesn't exist" messages

### ADB Not Detecting Device

1. Enable USB debugging in Developer Options
2. Allow USB debugging prompt on device
3. Try different USB cable/port
4. Restart ADB server:
   ```bash
   adb kill-server
   adb start-server
   ```

### Build Errors

1. Ensure JDK 17 is installed and set in `gradle.properties`:
   ```properties
   org.gradle.java.home=/path/to/jdk-17
   ```

2. Clean and rebuild:
   ```bash
   ./gradlew clean
   ./gradlew assembleDebug
   ```

3. Check Android Studio Project Structure → SDK Location → Gradle JDK

### App Crashes

1. Check logcat for crash reports:
   ```bash
   adb logcat -c  # clear log
   # reproduce crash
   adb logcat *:E  # show errors only
   ```

2. Common issues:
   - Missing AWS configuration in `strings.xml`
   - Invalid Cognito Identity Pool ID format
   - Missing permissions in AndroidManifest
   - IAM role lacks required S3 permissions

## Development Notes

### Java Version

This project requires Java 17+. Configure in `gradle.properties`:

```properties
org.gradle.java.home=/opt/homebrew/Cellar/openjdk/19.0.1/libexec/openjdk.jdk/Contents/Home
```

Adjust path based on your Java installation.

### Build Configuration

```gradle
android {
    compileSdk 34

    defaultConfig {
        minSdk 24
        targetSdk 34
    }

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }
}
```

### Color Theme

The app uses a clean blue Material Design theme:

```xml
<color name="primary_blue">#2196F3</color>
<color name="primary_blue_dark">#1976D2</color>
```

## Security Considerations

✅ **Good Practices:**
- Cognito Identity Pools (no AWS keys)
- Temporary, scoped credentials
- Device-specific folder isolation with GUID
- Account-based organization

⚠️ **Production Considerations:**
- Add user authentication (not just unauthenticated Cognito)
- Implement rate limiting
- Add upload quotas
- Monitor S3 bucket for abuse
- Consider encrypting files at rest
- Add server-side validation
- Implement audit logging

## License

This project is open source and available for educational purposes.

## Support

For issues and questions:
- [AWS S3 Documentation](https://docs.aws.amazon.com/s3/)
- [AWS Cognito Documentation](https://docs.aws.amazon.com/cognito/)
- [AWS SDK for Android](https://docs.aws.amazon.com/sdk-for-android/)
