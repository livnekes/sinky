# Download Files Lambda Function

AWS Lambda function that lists and generates pre-signed download URLs for files stored in S3, filtered by user email and optional prefix.

## Features

- List files by user email (matches folder prefix: `email_guid/`)
- Optional prefix filtering (e.g., specific month like `2024-12`)
- Generate pre-signed URLs valid for 1 hour
- CORS enabled for web applications
- Returns file metadata (size, last modified)

## Request Format

**POST** to Lambda function URL or API Gateway endpoint:

```json
{
  "user_email": "user@example.com",
  "prefix": "user@example.com_a1b2c3d4-e5f6-7890-abcd-ef1234567890/2024-12"
}
```

### Parameters

- `user_email` (required): Email address to filter files
- `prefix` (optional): Additional S3 prefix to narrow results (e.g., specific folder path)

## Response Format

**Success (200):**

```json
{
  "message": "Found 5 files",
  "files": [
    {
      "key": "user@example.com_a1b2c3d4/2024-12/2024-12-01_10-30-45.jpg",
      "size": 2048576,
      "lastModified": "2024-12-01T10:30:45.000Z",
      "downloadUrl": "https://bucket.s3.amazonaws.com/key?X-Amz-Algorithm=..."
    }
  ],
  "urlExpiresIn": "3600 seconds"
}
```

**No Files (200):**

```json
{
  "message": "No files found",
  "files": []
}
```

**Error (400):**

```json
{
  "error": "user_email is required"
}
```

**Error (500):**

```json
{
  "error": "Internal server error",
  "message": "Error details"
}
```

## Deployment

### Prerequisites

- AWS CLI configured
- Node.js 20+ installed
- IAM permissions to create Lambda functions
- S3 bucket name (same bucket used by the Android app)

### Step 1: Install Dependencies

```bash
cd lambda/download-files
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
        "s3:GetObject",
        "s3:ListBucket"
      ],
      "Resource": [
        "arn:aws:s3:::photos-sinky",
        "arn:aws:s3:::photos-sinky/*"
      ]
    }
  ]
}
```

Replace `photos-sinky` with your bucket name.

### Step 4: Create Lambda Function

```bash
aws lambda create-function \
  --function-name download-files \
  --runtime nodejs20.x \
  --role arn:aws:iam::YOUR_ACCOUNT_ID:role/lambda-s3-role \
  --handler index.handler \
  --zip-file fileb://function.zip \
  --timeout 30 \
  --memory-size 256 \
  --environment Variables={BUCKET_NAME=photos-sinky,AWS_REGION=us-east-1}
```

Replace:
- `YOUR_ACCOUNT_ID` with your AWS account ID
- `lambda-s3-role` with your IAM role name
- `photos-sinky` with your S3 bucket name
- `us-east-1` with your bucket region

### Step 5: Create Lambda Function URL (Optional)

For direct HTTPS access without API Gateway:

```bash
aws lambda create-function-url-config \
  --function-name download-files \
  --auth-type NONE \
  --cors AllowOrigins="*",AllowMethods="POST,OPTIONS",AllowHeaders="content-type"
```

This returns a Function URL like:
```
https://abc123xyz.lambda-url.us-east-1.on.aws/
```

**Note:** `NONE` auth means the function is publicly accessible. For production, use `AWS_IAM` and implement authentication.

### Step 6: Test the Function

```bash
curl -X POST https://YOUR_FUNCTION_URL \
  -H "Content-Type: application/json" \
  -d '{
    "user_email": "user@example.com",
    "prefix": "user@example.com_a1b2c3d4-e5f6-7890-abcd-ef1234567890/2024-12"
  }'
```

## Updating the Function

After making code changes:

```bash
cd lambda/download-files
npm run deploy
aws lambda update-function-code \
  --function-name download-files \
  --zip-file fileb://function.zip
```

## Environment Variables

Set in Lambda configuration:

- `BUCKET_NAME` (required): S3 bucket name (e.g., `photos-sinky`)
- `AWS_REGION` (optional): AWS region (defaults to `us-east-1`)

## Security Considerations

### Production Recommendations

1. **Authentication**: Change `auth-type` from `NONE` to `AWS_IAM` and implement Cognito or API key authentication

2. **Rate Limiting**: Add API Gateway with throttling to prevent abuse

3. **Input Validation**: Add email format validation and sanitization

4. **Prefix Restrictions**: Ensure users can only access their own files:

```javascript
// Add validation in Lambda
if (!key.startsWith(user_email)) {
    continue; // Skip files not belonging to user
}
```

5. **CloudWatch Alarms**: Monitor invocation count and errors

6. **VPC**: Consider running Lambda in VPC if S3 bucket is in VPC

7. **Encryption**: Ensure S3 bucket has encryption at rest enabled

## API Gateway Integration (Alternative to Function URL)

If you prefer API Gateway:

```bash
# Create REST API
aws apigateway create-rest-api --name "PhotoDownloadAPI"

# Create resource and method
# ... (detailed steps in AWS API Gateway docs)

# Link to Lambda function
aws lambda add-permission \
  --function-name download-files \
  --statement-id apigateway-access \
  --action lambda:InvokeFunction \
  --principal apigateway.amazonaws.com \
  --source-arn "arn:aws:execute-api:REGION:ACCOUNT:API_ID/*"
```

## Troubleshooting

### "BUCKET_NAME environment variable not set"

Solution: Set environment variable in Lambda configuration:

```bash
aws lambda update-function-configuration \
  --function-name download-files \
  --environment Variables={BUCKET_NAME=photos-sinky,AWS_REGION=us-east-1}
```

### "Access Denied" errors

Solution: Verify IAM role has `s3:GetObject` and `s3:ListBucket` permissions for your bucket.

### No files returned

- Verify user_email matches the folder prefix in S3
- Check S3 bucket for actual file structure
- Enable CloudWatch logs to see debug output:

```bash
aws logs tail /aws/lambda/download-files --follow
```

### CORS errors in browser

Solution: Ensure Lambda Function URL has correct CORS configuration or add CORS headers in API Gateway.

## Cost Considerations

- Lambda invocations: $0.20 per 1M requests
- Lambda duration: $0.0000166667 per GB-second
- S3 API calls: $0.0004 per 1,000 LIST requests
- S3 data transfer out: $0.09 per GB (after 100 GB/month free tier)

For 10,000 monthly requests listing 100 files each:
- Lambda cost: ~$0.002
- S3 LIST cost: ~$0.004
- Data transfer: Based on download size

## License

Open source - Educational purposes
