package com.example.s3photouploader

import android.content.Context
import com.amazonaws.auth.CognitoCachingCredentialsProvider
import com.amazonaws.regions.Regions

/**
 * Manages Cognito authentication using Google ID tokens
 * Implements Option B: Exchange Google ID token with Cognito for authenticated access
 */
object CognitoAuthManager {

    private var credentialsProvider: CognitoCachingCredentialsProvider? = null

    /**
     * Authenticates with Cognito using Google ID token
     * Creates/links a Cognito identity for the Google user
     *
     * @param context Application context
     * @param googleIdToken ID token from Google Sign-In
     * @param identityPoolId Cognito Identity Pool ID
     * @param region AWS region
     * @return CognitoCachingCredentialsProvider with authenticated credentials
     */
    fun authenticateWithGoogle(
        context: Context,
        googleIdToken: String,
        identityPoolId: String,
        region: Regions
    ): CognitoCachingCredentialsProvider {
        android.util.Log.d("CognitoAuthManager", "Authenticating with Cognito using Google ID token")
        android.util.Log.d("CognitoAuthManager", "Identity Pool ID: $identityPoolId")
        android.util.Log.d("CognitoAuthManager", "Region: $region")
        android.util.Log.d("CognitoAuthManager", "Token length: ${googleIdToken.length}")

        // DON'T log the actual token for security, but check if it looks valid
        if (googleIdToken.isEmpty()) {
            throw Exception("Google ID token is empty")
        }

        // Decode JWT to check audience (for debugging)
        try {
            val parts = googleIdToken.split(".")
            if (parts.size >= 2) {
                val payload = String(android.util.Base64.decode(parts[1], android.util.Base64.URL_SAFE))
                android.util.Log.d("CognitoAuthManager", "Token payload (first 200 chars): ${payload.take(200)}")

                // Extract audience
                val audMatch = Regex("\"aud\":\"([^\"]+)\"").find(payload)
                if (audMatch != null) {
                    val audience = audMatch.groupValues[1]
                    android.util.Log.d("CognitoAuthManager", "Token audience (aud): $audience")
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("CognitoAuthManager", "Could not decode token for debugging: ${e.message}")
        }

        // Create credentials provider
        val provider = CognitoCachingCredentialsProvider(
            context.applicationContext,
            identityPoolId,
            region
        )

        // Set Google as the login provider
        // The key "accounts.google.com" tells Cognito this is a Google token
        val logins = HashMap<String, String>()
        logins["accounts.google.com"] = googleIdToken
        provider.logins = logins

        android.util.Log.d("CognitoAuthManager", "Login provider set: accounts.google.com")

        // Refresh to get credentials and create/link Cognito identity
        try {
            android.util.Log.d("CognitoAuthManager", "Calling provider.refresh()...")
            provider.refresh()

            val cognitoId = provider.identityId
            android.util.Log.d("CognitoAuthManager", "Successfully authenticated. Cognito Identity ID: $cognitoId")

            // Save Cognito identity ID
            AccountHelper.saveCognitoIdentityId(context, cognitoId)

            // Cache the provider
            credentialsProvider = provider

            return provider
        } catch (e: Exception) {
            android.util.Log.e("CognitoAuthManager", "Failed to authenticate with Cognito: ${e.message}", e)
            android.util.Log.e("CognitoAuthManager", "Exception type: ${e.javaClass.name}")
            if (e.cause != null) {
                android.util.Log.e("CognitoAuthManager", "Caused by: ${e.cause?.message}", e.cause)
            }
            throw e
        }
    }

    /**
     * Gets the current cached credentials provider
     * Returns null if not authenticated
     */
    fun getCredentialsProvider(): CognitoCachingCredentialsProvider? {
        return credentialsProvider
    }

    /**
     * Creates an unauthenticated credentials provider (fallback)
     * Use this only if Google sign-in fails
     */
    fun createUnauthenticatedProvider(
        context: Context,
        identityPoolId: String,
        region: Regions
    ): CognitoCachingCredentialsProvider {
        android.util.Log.d("CognitoAuthManager", "Creating unauthenticated credentials provider")

        val provider = CognitoCachingCredentialsProvider(
            context.applicationContext,
            identityPoolId,
            region
        )

        credentialsProvider = provider
        return provider
    }

    /**
     * Checks if user is authenticated with Cognito
     */
    fun isAuthenticated(): Boolean {
        return credentialsProvider?.logins?.isNotEmpty() == true
    }

    /**
     * Gets the Cognito identity ID
     */
    fun getCognitoIdentityId(): String? {
        return credentialsProvider?.identityId
    }

    /**
     * Clears cached credentials and signs out from Cognito
     */
    fun signOut() {
        android.util.Log.d("CognitoAuthManager", "Signing out from Cognito")

        credentialsProvider?.let { provider ->
            // Clear credentials
            provider.clear()
            provider.clearCredentials()
        }

        credentialsProvider = null
    }

    /**
     * Refreshes credentials if needed
     * Call this before making AWS API calls
     */
    fun refreshCredentialsIfNeeded() {
        try {
            credentialsProvider?.refresh()
        } catch (e: Exception) {
            android.util.Log.e("CognitoAuthManager", "Failed to refresh credentials", e)
        }
    }
}
