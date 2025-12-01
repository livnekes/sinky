# S3 Photo Uploader - Android App

A Material Design Android application that allows users to upload photos from their device to an Amazon S3 public directory.

## Features

- **Material UI Design** - Modern, clean interface using Material Design 3 components
- **Secure Authentication** - Uses AWS Cognito - NO AWS access keys required!
- **Photo Selection** - Pick photos from device gallery
- **S3 Upload** - Upload photos directly to AWS S3 public buckets
- **Real-time Progress** - Visual feedback during upload
- **Public URL Generation** - Get shareable public URLs after upload

## Prerequisites

Before using this app, you need:

1. **AWS Account** - An active AWS account
2. **S3 Bucket** - A public S3 bucket created in AWS
3. **Cognito Identity Pool** - For secure, credential-less authentication (NO AWS keys required!)

## S3 Bucket Setup

1. Log in to AWS Console
2. Navigate to S3 service
3. Create a new bucket or use an existing one
4. Configure bucket permissions:
   - Go to Permissions tab
   - Disable "Block all public access" (or configure specific permissions)
   - Add a bucket policy to allow public read access:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "PublicReadGetObject",
      "Effect": "Allow",
      "Principal": "*",
      "Action": "s3:GetObject",
      "Resource": "arn:aws:s3:::YOUR_BUCKET_NAME/*"
    }
  ]
}
```

5. Note your bucket name and region

## AWS Cognito Identity Pool Setup

This app uses AWS Cognito Identity Pools for secure authentication **without requiring AWS access keys**. This is the recommended approach for mobile apps.

### Step 1: Create Identity Pool

1. Go to **AWS Cognito** in AWS Console
2. Click **"Manage Identity Pools"** → **"Create new identity pool"**
3. Enter a pool name (e.g., "S3PhotoUploaderPool")
4. Check **"Enable access to unauthenticated identities"** (allows anonymous uploads)
5. Click **"Create Pool"**
6. On the permissions page, click **"Allow"** to create the default IAM roles
7. **Copy your Identity Pool ID** (format: `us-east-1:xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`)

### Step 2: Configure IAM Role Permissions

1. Go to **IAM** → **Roles** in AWS Console
2. Find the role created by Cognito (named like `Cognito_YourPoolNameUnauth_Role`)
3. Click on the role and then **"Add permissions"** → **"Create inline policy"**
4. Click the **JSON** tab and paste:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "s3:PutObject",
        "s3:PutObjectAcl"
      ],
      "Resource": "arn:aws:s3:::YOUR_BUCKET_NAME/*"
    }
  ]
}
```

5. Replace `YOUR_BUCKET_NAME` with your actual bucket name
6. Click **"Review policy"**, name it (e.g., "S3UploadPolicy"), and click **"Create policy"**

## Building the App

### Requirements

- Android Studio Arctic Fox or later
- JDK 17
- Android SDK with minimum API 24 (Android 7.0)
- Target API 34 (Android 14)

### Steps

1. Open Android Studio
2. Select "Open an Existing Project"
3. Navigate to this project directory
4. Wait for Gradle sync to complete
5. Connect an Android device or start an emulator
6. Click "Run" or press Shift+F10

## Using the App

1. **Launch the app**

2. **Enter AWS Configuration (No Keys Required!):**
   - **Bucket Name**: Your S3 bucket name
   - **AWS Region**: e.g., `us-east-1`, `eu-west-1`, `ap-southeast-1`
   - **Cognito Identity Pool ID**: The Identity Pool ID you copied earlier
     Format: `us-east-1:xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`

3. **Select a Photo:**
   - Tap "SELECT PHOTO" button
   - Grant permissions if requested
   - Choose a photo from your gallery
   - Preview will appear in the app

4. **Upload:**
   - Tap "UPLOAD TO S3" button
   - Wait for upload to complete
   - Public URL will be displayed when finished

## Security Notes

✅ **Security Benefits:**

- **No AWS Keys in App** - This app uses AWS Cognito Identity Pools for authentication
- **Temporary Credentials** - Cognito provides temporary, limited-scope credentials
- **No Credential Storage** - Credentials are never stored on the device
- **AWS Best Practice** - This is the recommended approach for mobile apps

⚠️ **Important Considerations:**

- Be cautious with public bucket access - ensure you understand the implications
- The unauthenticated identity pool allows anonymous uploads - consider adding authentication for production
- Monitor your S3 bucket for unexpected uploads
- Consider adding rate limiting or upload quotas in production

## Project Structure

```
app/
├── src/main/
│   ├── java/com/example/s3photouploader/
│   │   ├── MainActivity.kt          # Main activity with UI logic
│   │   ├── CognitoS3Uploader.kt    # Cognito-based S3 upload service (SECURE - no keys!)
│   │   └── S3Uploader.kt           # Legacy uploader (not recommended)
│   ├── res/
│   │   ├── layout/
│   │   │   └── activity_main.xml   # Material UI layout
│   │   ├── values/
│   │   │   ├── strings.xml
│   │   │   ├── colors.xml
│   │   │   └── themes.xml          # Material Design theme
│   │   └── xml/
│   │       ├── backup_rules.xml
│   │       └── data_extraction_rules.xml
│   └── AndroidManifest.xml
└── build.gradle
```

## Dependencies

- **AndroidX Core & AppCompat** - Android compatibility libraries
- **Material Components** - Material Design UI components
- **AWS SDK for Android** - S3 upload functionality
- **AWS Cognito SDK** - Secure credential-less authentication
- **Kotlin Coroutines** - Asynchronous operations

## Permissions

The app requires the following permissions:

- `READ_EXTERNAL_STORAGE` (API < 33) - Access device photos
- `READ_MEDIA_IMAGES` (API >= 33) - Access device photos (Android 13+)
- `INTERNET` - Upload to S3
- `ACCESS_NETWORK_STATE` - Check network connectivity

## Troubleshooting

### Upload fails
- Verify Cognito Identity Pool ID is correct (format: `region:uuid`)
- Check bucket name and region match your AWS setup
- Ensure the IAM role attached to the Identity Pool has S3 PutObject permissions
- Verify the bucket policy allows public uploads
- Check device has internet connection

### "Invalid Identity Pool ID" error
- Ensure the format is correct: `us-east-1:xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`
- The region in the Identity Pool ID should match your Identity Pool's region
- Copy-paste directly from AWS Console to avoid typos

### Can't select photos
- Grant storage permissions when prompted
- Check device has photos in gallery
- Ensure app has necessary permissions in device settings

### Build errors
- Ensure you have JDK 17 installed
- Run `./gradlew clean` and rebuild
- Check internet connection for dependency downloads

## License

This project is open source and available for educational purposes.

## Support

For issues and questions, please refer to AWS S3 documentation:
- [AWS S3 Documentation](https://docs.aws.amazon.com/s3/)
- [AWS SDK for Android](https://docs.aws.amazon.com/sdk-for-android/)
