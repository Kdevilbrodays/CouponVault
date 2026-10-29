package com.example.couponvault.sync

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject

val Context.supabaseDataStore: DataStore<Preferences> by preferencesDataStore(name = "coupon_vault_supabase_prefs")

data class AuthSession(
    val accessToken: String,
    val refreshToken: String,
    val userEmail: String,
    val userId: String,
    val expiresAt: Long = 0L
)

class SupabasePreferences(private val context: Context) {

    companion object {
        val KEY_SUPABASE_URL = stringPreferencesKey("custom_supabase_url")
        val KEY_SUPABASE_ANON_KEY = stringPreferencesKey("custom_supabase_anon_key")
        val KEY_ACCESS_TOKEN = stringPreferencesKey("supabase_access_token")
        val KEY_REFRESH_TOKEN = stringPreferencesKey("supabase_refresh_token")
        val KEY_USER_EMAIL = stringPreferencesKey("supabase_user_email")
        val KEY_USER_ID = stringPreferencesKey("supabase_user_id")
        val KEY_PULL_CURSOR = stringPreferencesKey("supabase_pull_cursor")
        val KEY_LAST_SYNCED = longPreferencesKey("supabase_last_synced")
        val KEY_CLOCK_OFFSET = longPreferencesKey("supabase_clock_offset")
        val KEY_LAST_FULL_RECONCILE = longPreferencesKey("supabase_last_full_reconcile")
        val KEY_LAST_PUSH_TIME = longPreferencesKey("supabase_last_push_time")
        val KEY_LAST_PULL_TIME = longPreferencesKey("supabase_last_pull_time")
        val KEY_LAST_ERROR = stringPreferencesKey("supabase_last_error")
        val KEY_PHOTO_SYNC_ENABLED = booleanPreferencesKey("photo_sync_enabled")
        val KEY_PHOTO_WIFI_ONLY = booleanPreferencesKey("photo_wifi_only")
        val KEY_PHOTO_UPLOAD_QUALITY = stringPreferencesKey("photo_upload_quality")

        /**
         * Validates that the key is an anon/public key and NOT a service_role key.
         * Returns an error message if invalid, or null if valid.
         */
        fun validateAnonKey(key: String): String? {
            val trimmed = key.trim()
            if (trimmed.isEmpty()) return null

            if (trimmed.startsWith("sb_secret_")) {
                return "That's a secret key. Use the public (anon/publishable) key."
            }

            // Check JWT payload if key is in standard JWT format (header.payload.signature)
            val parts = trimmed.split(".")
            if (parts.size >= 2) {
                val payloadString = try {
                    String(java.util.Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8)
                } catch (_: Throwable) {
                    try {
                        val cleanPart = parts[1].replace('-', '+').replace('_', '/')
                        val padded = when (cleanPart.length % 4) {
                            2 -> "$cleanPart=="
                            3 -> "$cleanPart="
                            else -> cleanPart
                        }
                        String(java.util.Base64.getDecoder().decode(padded), Charsets.UTF_8)
                    } catch (_: Throwable) {
                        try {
                            val payloadBytes = Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
                            String(payloadBytes, Charsets.UTF_8)
                        } catch (_: Throwable) {
                            null
                        }
                    }
                }

                if (payloadString != null) {
                    if (payloadString.contains("service_role")) {
                        return "That's a secret key. Use the public (anon/publishable) key."
                    }
                    try {
                        val payloadJson = JSONObject(payloadString)
                        val role = payloadJson.optString("role")
                        if (role == "service_role") {
                            return "That's a secret key. Use the public (anon/publishable) key."
                        }
                    } catch (_: Exception) {
                        // ignore malformed json
                    }
                }
            }

            return null
        }
    }

    val supabaseUrlFlow: Flow<String> = context.supabaseDataStore.data.map { prefs ->
        val custom = prefs[KEY_SUPABASE_URL]?.trim() ?: ""
        if (custom.isNotEmpty()) custom else BuildConfig.SUPABASE_URL.trim()
    }

    val supabaseAnonKeyFlow: Flow<String> = context.supabaseDataStore.data.map { prefs ->
        val custom = prefs[KEY_SUPABASE_ANON_KEY]?.trim() ?: ""
        if (custom.isNotEmpty()) custom else BuildConfig.SUPABASE_ANON_KEY.trim()
    }

    val userEmailFlow: Flow<String?> = context.supabaseDataStore.data.map { prefs ->
        prefs[KEY_USER_EMAIL]
    }

    val userIdFlow: Flow<String?> = context.supabaseDataStore.data.map { prefs ->
        prefs[KEY_USER_ID]
    }

    val lastSyncedFlow: Flow<Long?> = context.supabaseDataStore.data.map { prefs ->
        prefs[KEY_LAST_SYNCED]
    }

    val pullCursorFlow: Flow<String?> = context.supabaseDataStore.data.map { prefs ->
        prefs[KEY_PULL_CURSOR]
    }

    suspend fun getEffectiveUrl(): String {
        return supabaseUrlFlow.first()
    }

    suspend fun getEffectiveAnonKey(): String {
        return supabaseAnonKeyFlow.first()
    }

    suspend fun getAccessToken(): String? {
        return context.supabaseDataStore.data.first()[KEY_ACCESS_TOKEN]
    }

    suspend fun getRefreshToken(): String? {
        return context.supabaseDataStore.data.first()[KEY_REFRESH_TOKEN]
    }

    suspend fun setCustomServer(url: String, anonKey: String) {
        context.supabaseDataStore.edit { prefs ->
            if (url.isNotBlank()) {
                prefs[KEY_SUPABASE_URL] = url.trim().removeSuffix("/")
            } else {
                prefs.remove(KEY_SUPABASE_URL)
            }
            if (anonKey.isNotBlank()) {
                prefs[KEY_SUPABASE_ANON_KEY] = anonKey.trim()
            } else {
                prefs.remove(KEY_SUPABASE_ANON_KEY)
            }
        }
    }

    suspend fun saveAuthSession(session: AuthSession) {
        context.supabaseDataStore.edit { prefs ->
            prefs[KEY_ACCESS_TOKEN] = session.accessToken
            prefs[KEY_REFRESH_TOKEN] = session.refreshToken
            prefs[KEY_USER_EMAIL] = session.userEmail
            prefs[KEY_USER_ID] = session.userId
        }
    }

    suspend fun clearAuthSession() {
        context.supabaseDataStore.edit { prefs ->
            prefs.remove(KEY_ACCESS_TOKEN)
            prefs.remove(KEY_REFRESH_TOKEN)
            prefs.remove(KEY_USER_EMAIL)
            prefs.remove(KEY_USER_ID)
            prefs.remove(KEY_PULL_CURSOR)
            prefs.remove(KEY_LAST_SYNCED)
            prefs.remove(KEY_LAST_PUSH_TIME)
            prefs.remove(KEY_LAST_PULL_TIME)
            prefs.remove(KEY_LAST_ERROR)
        }
    }

    @Volatile
    var cachedClockOffset: Long = 0L
        private set

    val clockOffsetFlow: Flow<Long> = context.supabaseDataStore.data.map { prefs ->
        val offset = prefs[KEY_CLOCK_OFFSET] ?: 0L
        cachedClockOffset = offset
        offset
    }

    val lastFullReconcileFlow: Flow<Long?> = context.supabaseDataStore.data.map { prefs ->
        prefs[KEY_LAST_FULL_RECONCILE]
    }

    val lastPushTimeFlow: Flow<Long?> = context.supabaseDataStore.data.map { prefs ->
        prefs[KEY_LAST_PUSH_TIME]
    }

    val lastPullTimeFlow: Flow<Long?> = context.supabaseDataStore.data.map { prefs ->
        prefs[KEY_LAST_PULL_TIME]
    }

    val lastErrorFlow: Flow<String?> = context.supabaseDataStore.data.map { prefs ->
        prefs[KEY_LAST_ERROR]
    }

    suspend fun getClockOffset(): Long {
        return context.supabaseDataStore.data.first()[KEY_CLOCK_OFFSET] ?: 0L
    }

    suspend fun setClockOffset(offset: Long) {
        cachedClockOffset = offset
        context.supabaseDataStore.edit { prefs ->
            prefs[KEY_CLOCK_OFFSET] = offset
        }
    }

    suspend fun setLastPushTime(timestamp: Long) {
        context.supabaseDataStore.edit { prefs ->
            prefs[KEY_LAST_PUSH_TIME] = timestamp
        }
    }

    suspend fun setLastPullTime(timestamp: Long) {
        context.supabaseDataStore.edit { prefs ->
            prefs[KEY_LAST_PULL_TIME] = timestamp
        }
    }

    suspend fun setLastFullReconcile(timestamp: Long) {
        context.supabaseDataStore.edit { prefs ->
            prefs[KEY_LAST_FULL_RECONCILE] = timestamp
        }
    }

    suspend fun setLastError(error: String?) {
        context.supabaseDataStore.edit { prefs ->
            if (error != null) {
                prefs[KEY_LAST_ERROR] = error
            } else {
                prefs.remove(KEY_LAST_ERROR)
            }
        }
    }

    suspend fun setPullCursor(cursor: String) {
        context.supabaseDataStore.edit { prefs ->
            prefs[KEY_PULL_CURSOR] = cursor
        }
    }

    suspend fun setLastSynced(timestamp: Long) {
        context.supabaseDataStore.edit { prefs ->
            prefs[KEY_LAST_SYNCED] = timestamp
        }
    }

    val photoSyncEnabledFlow: Flow<Boolean> = context.supabaseDataStore.data.map { prefs ->
        prefs[KEY_PHOTO_SYNC_ENABLED] ?: true
    }

    val photoWifiOnlyFlow: Flow<Boolean> = context.supabaseDataStore.data.map { prefs ->
        prefs[KEY_PHOTO_WIFI_ONLY] ?: true
    }

    val photoUploadQualityFlow: Flow<String> = context.supabaseDataStore.data.map { prefs ->
        prefs[KEY_PHOTO_UPLOAD_QUALITY] ?: "original"
    }

    suspend fun setPhotoSyncEnabled(enabled: Boolean) {
        context.supabaseDataStore.edit { prefs ->
            prefs[KEY_PHOTO_SYNC_ENABLED] = enabled
        }
    }

    suspend fun setPhotoWifiOnly(wifiOnly: Boolean) {
        context.supabaseDataStore.edit { prefs ->
            prefs[KEY_PHOTO_WIFI_ONLY] = wifiOnly
        }
    }

    suspend fun setPhotoUploadQuality(quality: String) {
        context.supabaseDataStore.edit { prefs ->
            prefs[KEY_PHOTO_UPLOAD_QUALITY] = quality
        }
    }
}
