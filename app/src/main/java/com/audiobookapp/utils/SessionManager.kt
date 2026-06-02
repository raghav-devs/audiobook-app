package com.audiobookapp.utils

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SessionManager(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "audiobookapp_session",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    companion object {
        const val AUTH_TYPE_LOCAL = "local"
        const val AUTH_TYPE_GOOGLE = "google"
        private const val KEY_AUTH_TYPE = "auth_type"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_GOOGLE_ID = "google_id"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_EMAIL = "email"
        private const val KEY_IS_LOGGED_IN = "is_logged_in"
    }

    fun saveLocalSession(userId: Int, email: String, displayName: String) {
        prefs.edit()
            .putString(KEY_AUTH_TYPE, AUTH_TYPE_LOCAL)
            .putInt(KEY_USER_ID, userId)
            .putString(KEY_EMAIL, email)
            .putString(KEY_DISPLAY_NAME, displayName)
            .putBoolean(KEY_IS_LOGGED_IN, true)
            .apply()
    }

    fun saveGoogleSession(googleId: String, email: String, displayName: String) {
        prefs.edit()
            .putString(KEY_AUTH_TYPE, AUTH_TYPE_GOOGLE)
            .putString(KEY_GOOGLE_ID, googleId)
            .putString(KEY_EMAIL, email)
            .putString(KEY_DISPLAY_NAME, displayName)
            .putBoolean(KEY_IS_LOGGED_IN, true)
            .apply()
    }

    fun isLoggedIn(): Boolean = prefs.getBoolean(KEY_IS_LOGGED_IN, false)
    fun getAuthType(): String = prefs.getString(KEY_AUTH_TYPE, "") ?: ""
    fun getLocalUserId(): Int = prefs.getInt(KEY_USER_ID, -1)
    fun getGoogleUserId(): String = prefs.getString(KEY_GOOGLE_ID, "") ?: ""
    fun getDisplayName(): String = prefs.getString(KEY_DISPLAY_NAME, "") ?: ""
    fun getEmail(): String = prefs.getString(KEY_EMAIL, "") ?: ""

    fun clearSession() {
        prefs.edit().clear().apply()
    }
}
