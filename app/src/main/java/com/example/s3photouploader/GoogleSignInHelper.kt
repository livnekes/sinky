package com.example.s3photouploader

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.services.drive.DriveScopes

/**
 * Helper class for managing Google Sign-In and OAuth tokens
 */
object GoogleSignInHelper {

    // Scopes needed for Google Photos and Drive API access
    private val SCOPES = listOf(
        Scope(DriveScopes.DRIVE_READONLY), // For accessing Google Drive quota
        Scope("https://www.googleapis.com/auth/photoslibrary.readonly") // For Google Photos
    )

    /**
     * Build GoogleSignInClient with required scopes
     */
    fun getGoogleSignInClient(context: Context): GoogleSignInClient {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestIdToken(context.getString(R.string.google_web_client_id))
            .requestScopes(SCOPES[0], *SCOPES.drop(1).toTypedArray())
            .build()

        return GoogleSignIn.getClient(context, gso)
    }

    /**
     * Get the currently signed-in Google account
     */
    fun getSignedInAccount(context: Context): GoogleSignInAccount? {
        return GoogleSignIn.getLastSignedInAccount(context)
    }

    /**
     * Check if user is signed in with Google
     */
    fun isSignedIn(context: Context): Boolean {
        return getSignedInAccount(context) != null
    }

    /**
     * Get the access token for API calls
     */
    fun getAccessToken(context: Context): String? {
        val account = getSignedInAccount(context) ?: return null

        // Note: For actual API calls, you may need to refresh the token
        // using GoogleAuthUtil.getToken() in a background thread
        return try {
            val token = com.google.android.gms.auth.GoogleAuthUtil.getToken(
                context,
                account.account!!,
                "oauth2:${SCOPES.joinToString(" ") { it.scopeUri }}"
            )
            token
        } catch (e: Exception) {
            android.util.Log.e("GoogleSignInHelper", "Error getting access token", e)
            null
        }
    }

    /**
     * Sign out from Google account
     */
    fun signOut(context: Context, onComplete: () -> Unit) {
        getGoogleSignInClient(context).signOut().addOnCompleteListener {
            onComplete()
        }
    }

    /**
     * Revoke access completely
     */
    fun revokeAccess(context: Context, onComplete: () -> Unit) {
        getGoogleSignInClient(context).revokeAccess().addOnCompleteListener {
            onComplete()
        }
    }

    /**
     * Silently refresh the Google ID token
     * This is used to get a fresh ID token when the cached one expires
     * Returns the fresh account with a new ID token, or null if failed
     */
    suspend fun silentlyRefreshIdToken(context: Context): GoogleSignInAccount? {
        return kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            val client = getGoogleSignInClient(context)

            client.silentSignIn()
                .addOnSuccessListener { account ->
                    android.util.Log.d("GoogleSignInHelper", "Silent sign-in successful, got fresh ID token")
                    continuation.resume(account) { }
                }
                .addOnFailureListener { e ->
                    android.util.Log.e("GoogleSignInHelper", "Silent sign-in failed", e)
                    continuation.resume(null) { }
                }
        }
    }
}
