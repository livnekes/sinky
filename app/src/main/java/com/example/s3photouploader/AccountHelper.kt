package com.example.s3photouploader

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

/**
 * Helper class to manage user email and secure GUID for organizing uploads
 */
object AccountHelper {

    private const val PREFS_NAME = "user_prefs"
    private const val KEY_USER_EMAIL = "user_email"
    private const val KEY_USER_GUID = "user_guid"
    private const val KEY_S3_PREFIX = "s3_prefix"
    private const val KEY_COGNITO_IDENTITY_ID = "cognito_identity_id"

    /**
     * Gets the stored user email
     *
     * @param context Application context
     * @return The user's email address, or null if not set
     */
    fun getUserEmail(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_USER_EMAIL, null)
    }

    /**
     * Saves the user's email address
     *
     * @param context Application context
     * @param email The email address to save
     */
    fun saveUserEmail(context: Context, email: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_USER_EMAIL, email).apply()
        android.util.Log.d("AccountHelper", "Saved user email: $email")
    }

    /**
     * Gets or generates the user's unique GUID for secure uploads
     * The GUID is generated once and stored permanently
     *
     * @param context Application context
     * @return The user's unique GUID
     */
    fun getUserGuid(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var guid = prefs.getString(KEY_USER_GUID, null)

        if (guid == null) {
            // Generate new GUID and save it
            guid = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_USER_GUID, guid).apply()
            android.util.Log.d("AccountHelper", "Generated new GUID: $guid")
        }

        return guid
    }

    /**
     * Saves the S3 prefix from Lambda
     *
     * @param context Application context
     * @param prefix The S3 prefix returned from Lambda
     */
    fun saveS3Prefix(context: Context, prefix: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_S3_PREFIX, prefix).apply()
        android.util.Log.d("AccountHelper", "Saved S3 prefix: $prefix")
    }

    /**
     * Gets the S3 prefix from Lambda (if available)
     *
     * @param context Application context
     * @return S3 prefix, or null if not set
     */
    fun getS3Prefix(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_S3_PREFIX, null)
    }

    /**
     * Clears the saved S3 prefix
     * This forces regeneration of the prefix using email + Cognito Identity ID
     *
     * @param context Application context
     */
    fun clearSavedPrefix(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_S3_PREFIX).apply()
        android.util.Log.d("AccountHelper", "Cleared saved S3 prefix")
    }

    /**
     * Gets the secure upload prefix
     * If S3 prefix exists (from account), return it
     * Otherwise, create email_cognitoIdentityId format
     *
     * Uses Cognito Identity ID as the GUID for stability across app reinstalls.
     * The same Google account always gets the same Cognito Identity ID.
     *
     * @param context Application context
     * @return S3 prefix in format: email or email_cognitoIdentityId
     */
    fun getSecureUploadPrefix(context: Context): String? {
        // If we have a saved S3 prefix (from account), use it
        val savedPrefix = getS3Prefix(context)
        if (savedPrefix != null) {
            return savedPrefix
        }

        // Otherwise, create email_cognitoIdentityId format
        val email = getUserEmail(context) ?: return null

        // Use Cognito Identity ID as the GUID for stability
        // This ensures the same prefix across app reinstalls
        val cognitoIdentityId = getCognitoIdentityId(context)
        if (cognitoIdentityId != null) {
            android.util.Log.d("AccountHelper", "Using Cognito Identity ID as GUID: $cognitoIdentityId")
            return "${email}_${cognitoIdentityId}"
        }

        // Fallback to random GUID if Cognito ID not available
        // (This shouldn't happen in normal flow)
        android.util.Log.w("AccountHelper", "Cognito Identity ID not available, using random GUID")
        val guid = getUserGuid(context)
        return "${email}_${guid}"
    }

    /**
     * Saves the Cognito identity ID
     *
     * @param context Application context
     * @param identityId The Cognito identity ID
     */
    fun saveCognitoIdentityId(context: Context, identityId: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_COGNITO_IDENTITY_ID, identityId).apply()
        android.util.Log.d("AccountHelper", "Saved Cognito identity ID: $identityId")
    }

    /**
     * Gets the Cognito identity ID
     *
     * @param context Application context
     * @return Cognito identity ID, or null if not set
     */
    fun getCognitoIdentityId(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_COGNITO_IDENTITY_ID, null)
    }

    /**
     * Clears all user data (email, GUID, S3 prefix, Cognito identity)
     */
    fun clearUserData(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        android.util.Log.d("AccountHelper", "Cleared all user data")
    }

    /**
     * Clears the saved email (deprecated - use clearUserData)
     */
    @Deprecated("Use clearUserData() instead")
    fun clearUserEmail(context: Context) {
        clearUserData(context)
    }
}
