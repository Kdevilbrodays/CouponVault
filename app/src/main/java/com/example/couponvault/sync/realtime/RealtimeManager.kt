package com.example.couponvault.sync.realtime

import android.util.Log
import com.example.couponvault.sync.SupabasePreferences
import io.github.jan.supabase.SupabaseClient as JanSupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RealtimeManager(
    private val preferences: SupabasePreferences,
    private val scope: CoroutineScope,
    private val onRealtimeChange: () -> Unit,
    private val onConnectOrReconnect: () -> Unit
) {
    companion object {
        private const val TAG = "RealtimeManager"
        private const val MIN_BACKOFF_MS = 1000L
        private const val MAX_BACKOFF_MS = 30000L
        private const val FALLBACK_POLL_INTERVAL_MS = 30000L
    }

    private val connectionMutex = Mutex()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _inForeground = MutableStateFlow(false)
    val inForeground: StateFlow<Boolean> = _inForeground.asStateFlow()

    private val _overlayRunning = MutableStateFlow(false)
    val overlayRunning: StateFlow<Boolean> = _overlayRunning.asStateFlow()

    private var client: JanSupabaseClient? = null
    private var activeChannel: RealtimeChannel? = null
    private var subscriptionJob: Job? = null
    private var statusObserverJob: Job? = null
    private var reconnectJob: Job? = null
    private var fallbackPollJob: Job? = null

    private var currentBackoffMs = MIN_BACKOFF_MS

    init {
        // Observe lifecycle: should be connected if foreground OR overlay is running
        scope.launch {
            combine(
                _inForeground,
                _overlayRunning,
                preferences.userIdFlow
            ) { fg, overlay, userId ->
                Triple(fg, overlay, userId)
            }.collectLatest { (fg, overlay, userId) ->
                val shouldBeConnected = (fg || overlay) && !userId.isNullOrBlank()
                Log.d(TAG, "Lifecycle state changed: fg=$fg, overlay=$overlay, user=${userId != null}, shouldConnect=$shouldBeConnected")
                if (shouldBeConnected) {
                    reconnectJob?.cancel()
                    currentBackoffMs = MIN_BACKOFF_MS
                    connectInternal()
                } else {
                    disconnectInternal()
                }
            }
        }

        // 30s Fallback polling when foregrounded but not connected to Realtime
        scope.launch {
            combine(_inForeground, _isConnected) { fg, connected ->
                fg && !connected
            }.collectLatest { needsFallbackPolling ->
                fallbackPollJob?.cancel()
                if (needsFallbackPolling) {
                    fallbackPollJob = scope.launch {
                        while (isActive) {
                            delay(FALLBACK_POLL_INTERVAL_MS)
                            if (_inForeground.value && !_isConnected.value) {
                                Log.d(TAG, "Realtime disconnected while foregrounded; running 30s fallback poll")
                                onRealtimeChange()
                            }
                        }
                    }
                }
            }
        }
    }

    fun setForeground(inForeground: Boolean) {
        val wasForeground = _inForeground.value
        _inForeground.value = inForeground
        if (!wasForeground && inForeground) {
            // App resume: trigger immediate pull
            onConnectOrReconnect()
        }
    }

    fun setOverlayRunning(running: Boolean) {
        _overlayRunning.value = running
    }

    fun onTokenRefreshed(newAccessToken: String) {
        scope.launch {
            connectionMutex.withLock {
                try {
                    client?.realtime?.setAuth(newAccessToken)
                    activeChannel?.updateAuth(newAccessToken)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to update realtime auth token: ${e.message}")
                    reconnectWithBackoff()
                }
            }
        }
    }

    private suspend fun connectInternal() = connectionMutex.withLock {
        val url = preferences.getEffectiveUrl()
        val anonKey = preferences.getEffectiveAnonKey()
        val userId = preferences.userIdFlow.first()
        val token = preferences.getAccessToken()

        if (url.isBlank() || anonKey.isBlank() || userId.isNullOrBlank() || token.isNullOrBlank()) {
            disconnectInternalLocked()
            return@withLock
        }

        try {
            cleanupActiveSessionLocked()

            val janClient = createSupabaseClient(supabaseUrl = url, supabaseKey = anonKey) {
                install(Realtime)
                httpEngine = OkHttp.create()
            }
            client = janClient

            janClient.realtime.setAuth(token)

            // Observe client connection status
            statusObserverJob = scope.launch {
                janClient.realtime.status.collect { status ->
                    Log.d(TAG, "Supabase Realtime status: $status")
                    when (status) {
                        Realtime.Status.CONNECTED -> {
                            _isConnected.value = true
                            currentBackoffMs = MIN_BACKOFF_MS
                            onConnectOrReconnect()
                        }
                        Realtime.Status.DISCONNECTED -> {
                            _isConnected.value = false
                            val shouldBeConnected = (_inForeground.value || _overlayRunning.value) &&
                                    !preferences.userIdFlow.first().isNullOrBlank()
                            if (shouldBeConnected) {
                                reconnectWithBackoff()
                            }
                        }
                        Realtime.Status.CONNECTING -> {
                            _isConnected.value = false
                        }
                    }
                }
            }

            janClient.realtime.connect()

            // Subscribe to Postgres changes on public.coupons filtered to owner = userId
            val channelTopic = "coupons_realtime_${userId.replace("-", "_")}"
            val channel = janClient.channel(channelTopic)
            activeChannel = channel

            val changeFlow = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                table = "coupons"
                filter("owner", FilterOperator.EQ, userId)
            }

            subscriptionJob = scope.launch {
                try {
                    changeFlow.collect { action ->
                        if (action is PostgresAction.Insert || action is PostgresAction.Update) {
                            Log.d(TAG, "Realtime Postgres change detected: ${action::class.simpleName}")
                            onRealtimeChange()
                        }
                    }
                } catch (e: Exception) {
                    if (e !is CancellationException) {
                        Log.e(TAG, "Realtime Postgres change collection error: ${e.message}", e)
                        reconnectWithBackoff()
                    }
                }
            }

            channel.subscribe()
            Log.d(TAG, "Subscribed to Realtime channel: $channelTopic")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect Realtime: ${e.message}", e)
            _isConnected.value = false
            reconnectWithBackoff()
        }
    }

    private fun reconnectWithBackoff() {
        val shouldBeConnected = (_inForeground.value || _overlayRunning.value)
        if (!shouldBeConnected) return

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val backoff = currentBackoffMs
            Log.d(TAG, "Scheduling Realtime reconnect in ${backoff}ms")
            delay(backoff)
            currentBackoffMs = (currentBackoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            connectInternal()
        }
    }

    private suspend fun disconnectInternal() = connectionMutex.withLock {
        disconnectInternalLocked()
    }

    private fun disconnectInternalLocked() {
        cleanupActiveSessionLocked()
        _isConnected.value = false
        reconnectJob?.cancel()
    }

    private fun cleanupActiveSessionLocked() {
        subscriptionJob?.cancel()
        subscriptionJob = null
        statusObserverJob?.cancel()
        statusObserverJob = null

        try {
            client?.realtime?.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "Error disconnecting realtime: ${e.message}")
        }
        client = null
        activeChannel = null
    }
}
