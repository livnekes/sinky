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
        // Create AWS request for signing
        val awsRequest = DefaultRequest<Any>("lambda")
        awsRequest.httpMethod = HttpMethodName.valueOf(method)
        awsRequest.endpoint = URI.create(url)
        awsRequest.resourcePath = URI.create(url).path

        // Add body if present
        if (body != null) {
            awsRequest.content = ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))
            awsRequest.addHeader("Content-Type", contentType)
            awsRequest.addHeader("Content-Length", body.toByteArray(Charsets.UTF_8).size.toString())
        }

        // Sign the request using AWS Signature V4
        val signer = com.amazonaws.auth.AWS4Signer()
        signer.setRegionName(region)
        signer.setServiceName("lambda")
        signer.sign(awsRequest, credentialsProvider.credentials)

        // Build OkHttp request with signed headers
        val requestBuilder = Request.Builder()
            .url(url)

        // Add all signed headers from AWS request
        for ((key, value) in awsRequest.headers) {
            requestBuilder.addHeader(key, value)
        }

        // Add body if present
        if (body != null) {
            requestBuilder.method(
                method,
                body.toRequestBody(contentType.toMediaType())
            )
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
