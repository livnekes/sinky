package com.example.s3photouploader

import com.amazonaws.DefaultRequest
import com.amazonaws.auth.AWSCredentialsProvider
import com.amazonaws.http.HttpMethodName
import com.amazonaws.util.IOUtils
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayInputStream
import java.net.URI

/**
 * Helper class to sign HTTP requests with AWS Signature V4
 * This allows the app to authenticate with IAM-protected Lambda Function URLs
 */
object AwsSignedRequestHelper {

    /**
     * Creates a signed OkHttp request using AWS Signature V4
     *
     * @param url The Lambda Function URL
     * @param method HTTP method (GET, POST, etc.)
     * @param body Request body (for POST requests)
     * @param contentType Content type (e.g., "application/json")
     * @param credentialsProvider AWS credentials provider (from Cognito)
     * @param region AWS region (e.g., "eu-central-1")
     * @return Signed OkHttp Request ready to execute
     */
    fun createSignedRequest(
        url: String,
        method: String = "POST",
        body: String? = null,
        contentType: String = "application/json",
        credentialsProvider: AWSCredentialsProvider,
        region: String
    ): Request {
        // Determine service name from URL (execute-api for API Gateway, lambda for Lambda URLs)
        val serviceName = if (url.contains("execute-api")) "execute-api" else "lambda"

        // Create AWS request for signing
        val awsRequest = DefaultRequest<Any>(serviceName)
        awsRequest.httpMethod = HttpMethodName.valueOf(method)

        // Set endpoint to just the host (without path) and resourcePath separately
        val uri = URI.create(url)
        val endpointUri = URI(uri.scheme, null, uri.host, uri.port, null, null, null)
        awsRequest.endpoint = endpointUri
        awsRequest.resourcePath = uri.path ?: "/"

        // Add body to the AWS request so the signer can calculate the correct payload hash
        // The signer needs this to include the SHA256 hash in the canonical string
        if (body != null) {
            // Use mark-supported stream to allow resetting after signing
            val bodyBytes = body.toByteArray(Charsets.UTF_8)
            val inputStream = ByteArrayInputStream(bodyBytes)
            awsRequest.content = inputStream

            // Log the payload hash for debugging
            val messageDigest = java.security.MessageDigest.getInstance("SHA-256")
            val hashBytes = messageDigest.digest(bodyBytes)
            val payloadHash = hashBytes.joinToString("") { "%02x".format(it) }
            android.util.Log.d("AwsSignedRequestHelper", "Payload SHA256: $payloadHash")
            android.util.Log.d("AwsSignedRequestHelper", "Body length: ${bodyBytes.size}")
        }

        // Sign the request using AWS Signature V4
        val signer = com.amazonaws.auth.AWS4Signer()
        signer.setRegionName(region)
        signer.setServiceName(serviceName)
        signer.sign(awsRequest, credentialsProvider.credentials)

        // Log all headers added by the signer
        android.util.Log.d("AwsSignedRequestHelper", "Headers after signing:")
        for ((key, value) in awsRequest.headers) {
            android.util.Log.d("AwsSignedRequestHelper", "  $key: ${if (key.lowercase().contains("token")) "[REDACTED]" else value}")
        }

        // Build OkHttp request with signed headers
        val requestBuilder = Request.Builder()
            .url(url)

        // Add all signed headers from AWS request
        for ((key, value) in awsRequest.headers) {
            requestBuilder.addHeader(key, value)
        }

        // Add Content-Type header (not part of signature for API Gateway)
        if (body != null) {
            requestBuilder.addHeader("Content-Type", contentType)
        }

        // Add body if present
        // Use a plain string body to avoid OkHttp adding extra headers
        if (body != null) {
            val requestBody = object : RequestBody() {
                override fun contentType(): okhttp3.MediaType? = null

                override fun writeTo(sink: okio.BufferedSink) {
                    sink.writeUtf8(body)
                }
            }
            requestBuilder.method(method, requestBody)
        } else {
            requestBuilder.method(method, null)
        }

        return requestBuilder.build()
    }

    /**
     * Convenience method for POST requests with JSON body
     */
    fun createSignedPostRequest(
        url: String,
        jsonBody: String,
        credentialsProvider: AWSCredentialsProvider,
        region: String
    ): Request {
        return createSignedRequest(
            url = url,
            method = "POST",
            body = jsonBody,
            contentType = "application/json",
            credentialsProvider = credentialsProvider,
            region = region
        )
    }
}
