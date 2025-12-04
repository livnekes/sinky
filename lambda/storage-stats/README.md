# Storage Stats Lambda Function

AWS Lambda function that counts objects and calculates total storage size for a given S3 prefix.

## Features

- Count total number of objects in a prefix
- Calculate total storage size in bytes
- Pagination support for large result sets (handles 1000+ objects)
- CORS enabled for web applications
- Handles both string and object event bodies

## Request Format

**POST** to Lambda function URL or API Gateway endpoint:

```json
{
  "prefix": "user@example.com_a1b2c3d4-e5f6-7890-abcd-ef1234567890/"
}
```

### Parameters

- `prefix` (required): S3 prefix to count objects and calculate size

## Response Format

**Success (200):**

```json
{
  "objectCount": 125,
  "totalSize": 52428800
}
```

- `objectCount`: Number of objects found
- `totalSize`: Total size in bytes

**Error (400):**

```json
{
  "error": "prefix is required"
}
```

**Error (500):**

```json
{
  "error": "Internal server error",
  "message": "Error details"
}
```

## Use Cases

1. **Display cloud storage usage** - Show users how many photos they've uploaded and total storage used
2. **Monitor storage quotas** - Check if users are approaching storage limits
3. **Usage analytics** - Track storage growth over time
4. **Billing calculations** - Calculate costs based on storage usage

## Deployment

### Prerequisites

- AWS CLI configured
- Node.js 20+ installed
- IAM permissions to create Lambda functions
- S3 bucket name: `photos-sinky` (or update in code)

### Step 1: Install Dependencies

```bash
cd lambda/storage-stats
npm install
```

### Step 2: Create Deployment Package

```bash
npm run deploy
# This creates function.zip with code and dependencies
```

### Step 3: Create IAM Role for Lambda

Create an IAM role with the following trust policy:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "Service": "lambda.amazonaws.com"
      },
      "Action": "sts:AssumeRole"
    }
  ]
}
```

Attach these policies to the role:
1. `AWSLambdaBasicExecutionRole` (for CloudWatch logs)
2. Custom policy for S3 access:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "s3:ListBucket"
      ],
      "Resource": "arn:aws:s3:::photos-sinky"
    }
  ]
}
```

Replace `photos-sinky` with your bucket name.

### Step 4: Create Lambda Function

```bash
aws lambda create-function \
  --function-name storage-stats \
  --runtime nodejs20.x \
  --role arn:aws:iam::YOUR_ACCOUNT_ID:role/lambda-s3-role \
  --handler index.handler \
  --zip-file fileb://function.zip \
  --timeout 30 \
  --memory-size 256
```

Replace:
- `YOUR_ACCOUNT_ID` with your AWS account ID
- `lambda-s3-role` with your IAM role name

**Note:** Bucket name and region are hardcoded in the function. If you need to change them, edit `index.js`:

```javascript
const s3Client = new S3Client({ region: "eu-central-1" });
const BUCKET_NAME = "photos-sinky";
```

### Step 5: Create Lambda Function URL (Optional)

For direct HTTPS access without API Gateway:

```bash
aws lambda create-function-url-config \
  --function-name storage-stats \
  --auth-type NONE \
  --cors AllowOrigins="*",AllowMethods="POST,OPTIONS",AllowHeaders="content-type"
```

This returns a Function URL like:
```
https://abc123xyz.lambda-url.eu-central-1.on.aws/
```

**Note:** `NONE` auth means the function is publicly accessible. For production, use `AWS_IAM` and implement authentication.

### Step 6: Test the Function

```bash
curl -X POST https://YOUR_FUNCTION_URL \
  -H "Content-Type: application/json" \
  -d '{
    "prefix": "user@example.com_a1b2c3d4/"
  }'
```

**Example Response:**

```json
{
  "objectCount": 125,
  "totalSize": 52428800
}
```

## Updating the Function

After making code changes:

```bash
cd lambda/storage-stats
npm run deploy
aws lambda update-function-code \
  --function-name storage-stats \
  --zip-file fileb://function.zip
```

## Configuration

### Hardcoded Values

Currently hardcoded in `index.js`:

```javascript
const s3Client = new S3Client({ region: "eu-central-1" });
const BUCKET_NAME = "photos-sinky";
```

### To Use Environment Variables (Alternative)

1. Update `index.js`:

```javascript
const s3Client = new S3Client({ region: process.env.AWS_REGION || "eu-central-1" });
const BUCKET_NAME = process.env.BUCKET_NAME || "photos-sinky";
```

2. Set environment variables:

```bash
aws lambda update-function-configuration \
  --function-name storage-stats \
  --environment Variables={BUCKET_NAME=photos-sinky,AWS_REGION=eu-central-1}
```

## Performance Considerations

- **Pagination**: Automatically handles large result sets with `MaxKeys: 1000` per request
- **Timeout**: Set to 30 seconds (can be increased for very large prefixes)
- **Memory**: 256 MB is sufficient for most use cases
- **Cold Start**: ~500ms for first invocation, ~50ms for warm invocations

## Example Integration with Android App

You can call this Lambda from your Android app to display cloud storage stats in the Settings page:

```kotlin
// Make HTTP POST request to Lambda URL
val json = JSONObject().apply {
    put("prefix", "user@example.com_$deviceGuid/")
}

val request = Request.Builder()
    .url("https://YOUR_FUNCTION_URL")
    .post(json.toString().toRequestBody("application/json".toMediaType()))
    .build()

client.newCall(request).execute().use { response ->
    val result = JSONObject(response.body?.string())
    val objectCount = result.getInt("objectCount")
    val totalSize = result.getLong("totalSize")

    // Display: "125 photos, 50 MB in cloud"
}
```

## Security Considerations

### Production Recommendations

1. **Authentication**: Change `auth-type` from `NONE` to `AWS_IAM` and implement Cognito authentication

2. **Input Validation**: Add prefix validation to ensure users can only query their own folders:

```javascript
// Add validation
const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+/;
if (!emailRegex.test(prefix)) {
    return {
        statusCode: 400,
        body: JSON.stringify({ error: 'Invalid prefix format' })
    };
}
```

3. **Rate Limiting**: Add API Gateway with throttling to prevent abuse

4. **CloudWatch Alarms**: Monitor invocation count and errors

5. **Least Privilege**: IAM role only needs `s3:ListBucket` permission (not `s3:GetObject`)

## Troubleshooting

### Function Times Out

If you have very large prefixes (10,000+ objects), increase timeout:

```bash
aws lambda update-function-configuration \
  --function-name storage-stats \
  --timeout 60
```

### "Access Denied" errors

Solution: Verify IAM role has `s3:ListBucket` permission for your bucket.

### CORS errors in browser

Solution: Ensure Lambda Function URL has correct CORS configuration or add CORS headers in API Gateway.

### View Logs

```bash
aws logs tail /aws/lambda/storage-stats --follow
```

## Cost Considerations

- Lambda invocations: $0.20 per 1M requests
- Lambda duration: $0.0000166667 per GB-second
- S3 LIST API calls: $0.005 per 1,000 requests

For 10,000 monthly requests checking 100 photos each:
- Lambda cost: ~$0.002
- S3 LIST cost: ~$0.05
- **Total: ~$0.052/month**

## License

Open source - Educational purposes
