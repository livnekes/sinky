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
     * Gets the secure upload prefix combining email and GUID
     * Format: email_guid
     *
     * @param context Application context
     * @return Secure upload prefix, or null if email not set
     */
    fun getSecureUploadPrefix(context: Context): String? {
        val email = getUserEmail(context) ?: return null
        val guid = getUserGuid(context)
        return "${email}_${guid}"
    }

    /**
     * Clears the saved email
     */
    fun clearUserEmail(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_USER_EMAIL).apply()
    }
}
