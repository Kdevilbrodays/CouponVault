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

    /**
     * Calls server_now() RPC and returns the estimated clock offset in ms:
     * serverTime - midpoint(t0, t1).
     */
    suspend fun fetchServerNow(): Result<Long> {
        val t0 = System.currentTimeMillis()
        val responseResult = executeWithAuth { baseUrl, anonKey, token ->
            Request.Builder()
                .url("$baseUrl/rest/v1/rpc/server_now")
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .post("{}".toRequestBody(JSON_MEDIA_TYPE))
                .build()
        }
        val t1 = System.currentTimeMillis()
        val tMid = (t0 + t1) / 2

        return responseResult.mapCatching { bodyString ->
            val clean = bodyString.trim().removeSurrounding("\"")
            val serverTimeMillis = try {
                java.time.OffsetDateTime.parse(clean).toInstant().toEpochMilli()
            } catch (_: Exception) {
                try {
                    java.time.Instant.parse(clean).toEpochMilli()
                } catch (_: Exception) {
                    clean.toLongOrNull() ?: tMid
                }
            }
            val offset = serverTimeMillis - tMid
            preferences.setClockOffset(offset)
            offset
        }
    }

    /**
     * Calls claim_next_coupon RPC with deviceId.
     * Returns Result.success(JSONObject) if a coupon was claimed,
     * Result.success(null) if empty set (no coupons to claim),
     * or Result.failure on error.
     */
    open suspend fun claimNextCoupon(deviceId: String): Result<JSONObject?> {
        val payload = JSONObject().apply {
            put("p_device", deviceId)
        }

        val responseResult = executeWithAuth { baseUrl, anonKey, token ->
            Request.Builder()
                .url("$baseUrl/rest/v1/rpc/claim_next_coupon")
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
        }

        return responseResult.mapCatching { bodyString ->
            val trimmed = bodyString.trim()
            if (trimmed.startsWith("[")) {
                val array = JSONArray(trimmed)
                if (array.length() > 0) {
                    array.getJSONObject(0)
                } else {
                    null
                }
            } else if (trimmed.startsWith("{")) {
                JSONObject(trimmed)
            } else {
                null
            }
        }
    }

    /**
     * Calls release_coupon RPC with syncId (UUID).
     */
    open suspend fun releaseCoupon(syncId: String): Result<Unit> {
        val payload = JSONObject().apply {
            put("p_id", syncId)
        }

        val responseResult = executeWithAuth { baseUrl, anonKey, token ->
            Request.Builder()
                .url("$baseUrl/rest/v1/rpc/release_coupon")
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
        }

        return responseResult.mapCatching {
            Unit
        }
    }

    /**
     * Pages through all coupons for a full reconcile, returning a consolidated JSONArray.
     */
    suspend fun fetchAllCouponsForReconcile(pageSize: Int = 500): Result<JSONArray> {
        val allRows = JSONArray()
        var offset = 0
        var hasMore = true

        while (hasMore) {
            val currentOffset = offset
            val pageResult = executeWithAuth { baseUrl, anonKey, token ->
                val url = "$baseUrl/rest/v1/coupons?select=*&order=updated_at.asc&limit=$pageSize&offset=$currentOffset"
                Request.Builder()
                    .url(url)
                    .header("apikey", anonKey)
                    .header("Authorization", "Bearer $token")
                    .get()
                    .build()
            }

            if (pageResult.isFailure) {
                return Result.failure(pageResult.exceptionOrNull() ?: Exception("Failed to fetch page for reconcile"))
            }

            val pageArray = JSONArray(pageResult.getOrThrow())
            for (i in 0 until pageArray.length()) {
                allRows.put(pageArray.getJSONObject(i))
            }

            if (pageArray.length() < pageSize) {
                hasMore = false
            } else {
                offset += pageSize
            }
        }

        return Result.success(allRows)
    }

    /**
     * Executes an authenticated request that returns raw bytes (e.g. photo download),
     * automatically refreshing expired tokens on 401.
     */
    private suspend fun executeWithAuthBytes(
        buildRequest: (baseUrl: String, anonKey: String, token: String) -> Request
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
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
                val refreshed = refreshSessionInternal(baseUrl, anonKey)
                if (refreshed.isSuccess) {
                    val newAccessToken = refreshed.getOrThrow().accessToken
                    val retryRequest = buildRequest(baseUrl, anonKey, newAccessToken)
                    val retryResponse = httpClient.newCall(retryRequest).execute()
                    val retryBytes = retryResponse.body?.bytes() ?: byteArrayOf()
                    val code = retryResponse.code
                    retryResponse.close()
                    return@withContext if (code in 200..299) {
                        Result.success(retryBytes)
                    } else {
                        Result.failure(SupabaseError.Server("Server error ($code)"))
                    }
                } else {
                    return@withContext Result.failure(SupabaseError.Auth("Session expired. Please sign in again."))
                }
            }

            val code = response.code
            val bytes = response.body?.bytes() ?: byteArrayOf()
            response.close()
            if (code !in 200..299) {
                return@withContext Result.failure(SupabaseError.Server("Error ($code)"))
            }

            Result.success(bytes)
        } catch (e: IOException) {
            Log.e(TAG, "Network failure: ${e.message}", e)
            Result.failure(SupabaseError.Network(e.message ?: "Network failure"))
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Uploads photo bytes to private bucket 'coupon-photos/<userId>/<syncId>.jpg'.
     * Handles 409 ('already exists') by downloading and verifying if the existing file matches expectedSha256.
     * Returns Result with the remoteImagePath string ("<userId>/<syncId>.jpg").
     */
    open suspend fun uploadPhoto(
        userId: String,
        syncId: String,
        imageBytes: ByteArray,
        expectedSha256: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val config = getBaseUrlAndKey()
            ?: return@withContext Result.failure(
                SupabaseError.ConfigurationMissing("Sync requires a server.")
            )
        val (baseUrl, anonKey) = config
        val token = preferences.getAccessToken()
            ?: return@withContext Result.failure(SupabaseError.Auth("User is not signed in."))

        val remotePath = "$userId/$syncId.jpg"
        val requestBody = imageBytes.toRequestBody("image/jpeg".toMediaType())

        suspend fun attemptUpload(authToken: String): okhttp3.Response {
            val url = "$baseUrl/storage/v1/object/coupon-photos/$remotePath"
            val request = Request.Builder()
                .url(url)
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $authToken")
                .header("Content-Type", "image/jpeg")
                .header("x-upsert", "false")
                .post(requestBody)
                .build()
            return httpClient.newCall(request).execute()
        }

        try {
            var response = attemptUpload(token)
            if (response.code == 401) {
                response.close()
                val refreshed = refreshSessionInternal(baseUrl, anonKey)
                if (refreshed.isSuccess) {
                    val newToken = refreshed.getOrThrow().accessToken
                    response = attemptUpload(newToken)
                } else {
                    return@withContext Result.failure(SupabaseError.Auth("Session expired."))
                }
            }

            val code = response.code
            val bodyString = response.body?.string() ?: ""
            response.close()

            if (code in 200..299) {
                return@withContext Result.success(remotePath)
            } else if (code == 409) {
                // 409 "already exists" counts as success if the hashes match
                Log.d(TAG, "Storage returned 409 for $remotePath; checking if hashes match")
                val existingBytesResult = downloadPhoto(remotePath)
                if (existingBytesResult.isSuccess) {
                    val existingSha = PhotoSyncHelper.computeSha256(existingBytesResult.getOrThrow())
                    if (existingSha.equals(expectedSha256, ignoreCase = true)) {
                        Log.d(TAG, "409 confirmed: SHA-256 match for $remotePath")
                        return@withContext Result.success(remotePath)
                    } else {
                        return@withContext Result.failure(SupabaseError.Server("409 Conflict: Remote image exists with different hash"))
                    }
                } else {
                    return@withContext Result.failure(SupabaseError.Server("409 Conflict: Remote image already exists"))
                }
            } else {
                return@withContext Result.failure(SupabaseError.Server("Upload failed ($code): $bodyString"))
            }
        } catch (e: IOException) {
            Log.e(TAG, "Network failure uploading photo: ${e.message}", e)
            Result.failure(SupabaseError.Network(e.message ?: "Network failure"))
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error uploading photo: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Downloads photo bytes from private bucket 'coupon-photos/<remoteImagePath>'.
     * Authenticated download only.
     */
    open suspend fun downloadPhoto(remoteImagePath: String): Result<ByteArray> {
        val cleanPath = remoteImagePath.trim().removePrefix("/")
        val result = executeWithAuthBytes { baseUrl, anonKey, token ->
            Request.Builder()
                .url("$baseUrl/storage/v1/object/authenticated/coupon-photos/$cleanPath")
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $token")
                .get()
                .build()
        }
        if (result.isSuccess) {
            return result
        }
        // Fallback to standard object endpoint with Authorization header
        return executeWithAuthBytes { baseUrl, anonKey, token ->
            Request.Builder()
                .url("$baseUrl/storage/v1/object/coupon-photos/$cleanPath")
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $token")
                .get()
                .build()
        }
    }

    /**
     * Best-effort deletion of photo from storage bucket.
     * Never crashes or throws on failure.
     */
    open suspend fun deletePhoto(userId: String, syncId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val remotePath = "$userId/$syncId.jpg"
        try {
            val config = getBaseUrlAndKey() ?: return@withContext Result.success(Unit)
            val (baseUrl, anonKey) = config
            val token = preferences.getAccessToken() ?: return@withContext Result.success(Unit)

            val request = Request.Builder()
                .url("$baseUrl/storage/v1/object/coupon-photos/$remotePath")
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $token")
                .delete()
                .build()
            val response = httpClient.newCall(request).execute()
            response.close()
            Result.success(Unit)
        } catch (t: Throwable) {
            Log.w(TAG, "Best-effort deletePhoto skipped/failed for $remotePath: ${t.message}")
            Result.success(Unit)
        }
    }
}
