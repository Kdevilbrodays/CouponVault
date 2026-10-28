package com.example.couponvault.sync

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed class SupabaseError(message: String) : Exception(message) {
    class ConfigurationMissing(message: String) : SupabaseError(message)
    class Network(message: String) : SupabaseError(message)
    class Auth(message: String) : SupabaseError(message)
    class Server(message: String) : SupabaseError(message)
}

open class SupabaseClient(
    private val preferences: SupabasePreferences,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        private const val TAG = "SupabaseClient"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private suspend fun getBaseUrlAndKey(): Pair<String, String>? {
        val url = preferences.getEffectiveUrl()
        val key = preferences.getEffectiveAnonKey()
        if (url.isBlank() || key.isBlank()) {
            return null
        }
        return Pair(url.removeSuffix("/"), key)
    }

    /**
     * Executes an authenticated request, automatically refreshing expired tokens on 401.
     */
    private suspend fun executeWithAuth(
        buildRequest: (baseUrl: String, anonKey: String, token: String) -> Request
    ): Result<String> = withContext(Dispatchers.IO) {
        val config = getBaseUrlAndKey()
            ?: return@withContext Result.failure(
                SupabaseError.ConfigurationMissing("Sync requires a server. Enter your Supabase URL and anon key in Advanced below.")
            )
        val (baseUrl, anonKey) = config
        val accessToken = preferences.getAccessToken()
            ?: return@withContext Result.failure(SupabaseError.Auth("User is not signed in."))

        try {
            val request = buildRequest(baseUrl, anonKey, accessToken)
            val response = httpClient.newCall(request).execute()

            if (response.code == 401) {
                response.close()
                // Try refresh token
                val refreshed = refreshSessionInternal(baseUrl, anonKey)
                if (refreshed.isSuccess) {
                    val newAccessToken = refreshed.getOrThrow().accessToken
                    val retryRequest = buildRequest(baseUrl, anonKey, newAccessToken)
                    val retryResponse = httpClient.newCall(retryRequest).execute()
                    val retryBody = retryResponse.body?.string() ?: ""
                    return@withContext if (retryResponse.isSuccessful) {
                        Result.success(retryBody)
                    } else {
                        Result.failure(SupabaseError.Server("Server error (${retryResponse.code}): $retryBody"))
                    }
                } else {
                    return@withContext Result.failure(SupabaseError.Auth("Session expired. Please sign in again."))
                }
            }

            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                val errorMsg = try {
                    JSONObject(body).optString("message", body)
                } catch (_: Exception) {
                    body
                }
                return@withContext Result.failure(SupabaseError.Server("Error (${response.code}): $errorMsg"))
            }

            Result.success(body)
        } catch (e: IOException) {
            Log.e(TAG, "Network failure: ${e.message}", e)
            Result.failure(SupabaseError.Network(e.message ?: "Network failure"))
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun signUp(email: String, password: String): Result<AuthSession> = withContext(Dispatchers.IO) {
        val config = getBaseUrlAndKey()
            ?: return@withContext Result.failure(
                SupabaseError.ConfigurationMissing("Sync requires a server. Enter your Supabase URL and anon key in Advanced below.")
            )
        val (baseUrl, anonKey) = config

        val bodyJson = JSONObject().apply {
            put("email", email.trim())
            put("password", password)
        }

        val request = Request.Builder()
            .url("$baseUrl/auth/v1/signup")
            .header("apikey", anonKey)
            .header("Authorization", "Bearer $anonKey")
            .post(bodyJson.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        try {
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                val errorDesc = try {
                    val json = JSONObject(body)
                    json.optString("msg", json.optString("error_description", body))
                } catch (_: Exception) {
                    body
                }
                return@withContext Result.failure(SupabaseError.Auth(errorDesc))
            }

            val json = JSONObject(body)
            val accessToken = json.optString("access_token")
            val refreshToken = json.optString("refresh_token")
            val user = json.optJSONObject("user")
            val userEmail = user?.optString("email") ?: email
            val userId = user?.optString("id") ?: ""

            if (accessToken.isNotEmpty()) {
                val session = AuthSession(
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    userEmail = userEmail,
                    userId = userId
                )
                preferences.saveAuthSession(session)
                Result.success(session)
            } else {
                // If email confirmation is enabled on Supabase, an access token may not be returned immediately
                Result.failure(SupabaseError.Auth("Account created. Please verify your email before signing in."))
            }
        } catch (e: IOException) {
            Result.failure(SupabaseError.Network("Network error: ${e.message}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun signIn(email: String, password: String): Result<AuthSession> = withContext(Dispatchers.IO) {
        val config = getBaseUrlAndKey()
            ?: return@withContext Result.failure(
                SupabaseError.ConfigurationMissing("Sync requires a server. Enter your Supabase URL and anon key in Advanced below.")
            )
        val (baseUrl, anonKey) = config

        val bodyJson = JSONObject().apply {
            put("email", email.trim())
            put("password", password)
        }

        val request = Request.Builder()
            .url("$baseUrl/auth/v1/token?grant_type=password")
            .header("apikey", anonKey)
            .header("Authorization", "Bearer $anonKey")
            .post(bodyJson.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        try {
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                val errorDesc = try {
                    val json = JSONObject(body)
                    json.optString("error_description", json.optString("msg", body))
                } catch (_: Exception) {
                    body
                }
                return@withContext Result.failure(SupabaseError.Auth(errorDesc))
            }

            val json = JSONObject(body)
            val accessToken = json.getString("access_token")
            val refreshToken = json.optString("refresh_token")
            val user = json.optJSONObject("user")
            val userEmail = user?.optString("email") ?: email
            val userId = user?.optString("id") ?: ""

            val session = AuthSession(
                accessToken = accessToken,
                refreshToken = refreshToken,
                userEmail = userEmail,
                userId = userId
            )
            preferences.saveAuthSession(session)
            Result.success(session)
        } catch (e: IOException) {
            Result.failure(SupabaseError.Network("Network error: ${e.message}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun refreshSessionInternal(baseUrl: String, anonKey: String): Result<AuthSession> {
        val refreshToken = preferences.getRefreshToken()
            ?: return Result.failure(SupabaseError.Auth("No refresh token"))

        val bodyJson = JSONObject().apply {
            put("refresh_token", refreshToken)
        }

        val request = Request.Builder()
            .url("$baseUrl/auth/v1/token?grant_type=refresh_token")
            .header("apikey", anonKey)
            .header("Authorization", "Bearer $anonKey")
            .post(bodyJson.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return try {
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                Result.failure(SupabaseError.Auth("Refresh token invalid"))
            } else {
                val json = JSONObject(body)
                val newAccess = json.getString("access_token")
                val newRefresh = json.optString("refresh_token", refreshToken)
                val user = json.optJSONObject("user")
                val email = user?.optString("email") ?: ""
                val id = user?.optString("id") ?: ""

                val session = AuthSession(
                    accessToken = newAccess,
                    refreshToken = newRefresh,
                    userEmail = email,
                    userId = id
                )
                preferences.saveAuthSession(session)
                Result.success(session)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun signOut(): Result<Unit> = withContext(Dispatchers.IO) {
        val config = getBaseUrlAndKey()
        if (config != null) {
            val (baseUrl, anonKey) = config
            val token = preferences.getAccessToken()
            if (token != null) {
                try {
                    val request = Request.Builder()
                        .url("$baseUrl/auth/v1/logout")
                        .header("apikey", anonKey)
                        .header("Authorization", "Bearer $token")
                        .post("{}".toRequestBody(JSON_MEDIA_TYPE))
                        .build()
                    httpClient.newCall(request).execute().close()
                } catch (_: Exception) {
                    // Ignore network failure on sign out
                }
            }
        }
        preferences.clearAuthSession()
        Result.success(Unit)
    }

    /**
     * Calls push_coupons RPC with batch of items.
     */
    suspend fun pushCoupons(items: JSONArray): Result<JSONArray> {
        val payload = JSONObject().apply {
            put("items", items)
        }

        val responseResult = executeWithAuth { baseUrl, anonKey, token ->
            Request.Builder()
                .url("$baseUrl/rest/v1/rpc/push_coupons")
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
        }

        return responseResult.mapCatching { bodyString ->
            JSONArray(bodyString)
        }
    }

    /**
     * Pulls coupons updated after afterIsoTimestamp in batches of limit.
     */
    suspend fun pullCoupons(afterIsoTimestamp: String?, limit: Int = 500): Result<JSONArray> {
        val responseResult = executeWithAuth { baseUrl, anonKey, token ->
            val urlBuilder = StringBuilder("$baseUrl/rest/v1/coupons?select=*&order=updated_at.asc&limit=$limit")
            if (!afterIsoTimestamp.isNullOrBlank()) {
                urlBuilder.append("&updated_at=gt.").append(java.net.URLEncoder.encode(afterIsoTimestamp, "UTF-8"))
            }

            Request.Builder()
                .url(urlBuilder.toString())
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $token")
                .get()
                .build()
        }

        return responseResult.mapCatching { bodyString ->
            JSONArray(bodyString)
        }
    }
}
