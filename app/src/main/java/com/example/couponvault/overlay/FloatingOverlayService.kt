package com.example.couponvault.overlay

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.couponvault.CouponVaultApplication
import com.example.couponvault.data.CouponQueueManager
import com.example.couponvault.data.OverlayPreferences
import com.example.couponvault.data.QueueState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs

class FloatingOverlayService : Service() {

    companion object {
        const val CHANNEL_ID = "floating_paste_button"
        const val NOTIFICATION_ID = 2048

        private val _isRunning = MutableStateFlow(false)
        val isRunning = _isRunning.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, FloatingOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, FloatingOverlayService::class.java)
            context.stopService(intent)
        }
    }

    private lateinit var windowManager: WindowManager
    private lateinit var queueManager: CouponQueueManager
    private lateinit var preferences: OverlayPreferences

    private var overlayView: View? = null
    private var windowParams: WindowManager.LayoutParams? = null

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private val mainHandler = Handler(Looper.getMainLooper())

    private var pillTextView: TextView? = null
    private var pillIconView: ImageView? = null
    private var pillContainer: LinearLayout? = null

    private var currentQueueState = QueueState()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        _isRunning.value = true

        val app = application as CouponVaultApplication
        queueManager = app.queueManager
        preferences = app.overlayPreferences
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(currentQueueState))

        // Check if we can draw overlays
        if (Settings.canDrawOverlays(this)) {
            serviceScope.launch {
                val pos = preferences.overlayPositionFlow.first()
                initOverlayView(pos.first, pos.second)
            }
        } else {
            stopSelf()
            return
        }

        // Collect queue state changes to update both overlay and notification reactively
        serviceScope.launch {
            queueManager.queueState.collect { state ->
                currentQueueState = state
                updateOverlayUI(state)
                val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIFICATION_ID, buildNotification(state))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        _isRunning.value = false
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        overlayView = null
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Floating paste button",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows the active floating coupon paste queue"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(state: QueueState): Notification {
        val appIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            appIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val copyNextIntent = Intent(this, OverlayActionReceiver::class.java).apply {
            action = OverlayActionReceiver.ACTION_COPY_NEXT
        }
        val copyNextPendingIntent = PendingIntent.getBroadcast(
            this,
            1,
            copyNextIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val failedIntent = Intent(this, OverlayActionReceiver::class.java).apply {
            action = OverlayActionReceiver.ACTION_FAILED
        }
        val failedPendingIntent = PendingIntent.getBroadcast(
            this,
            2,
            failedIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, OverlayActionReceiver::class.java).apply {
            action = OverlayActionReceiver.ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getBroadcast(
            this,
            3,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val statusText = if (state.isAllDone) {
            "All coupons redeemed"
        } else {
            val code = state.currentCode ?: "Ready"
            "$code · ${state.remainingCount} left"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Floating Paste Button Active")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_send, "Copy next", copyNextPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Failed", failedPendingIntent)
            .addAction(android.R.drawable.ic_delete, "Stop", stopPendingIntent)
            .build()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initOverlayView(initialX: Int, initialY: Int) {
        val dpToPx = { dp: Float ->
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics).toInt()
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(12f), dpToPx(8f), dpToPx(14f), dpToPx(8f))

            background = GradientDrawable().apply {
                setColor(0xEE1E293B.toInt()) // Deep slate navy
                cornerRadius = dpToPx(24f).toFloat()
                setStroke(dpToPx(1.5f), 0x8860A5FA.toInt()) // Electric light blue border
            }
            elevation = dpToPx(8f).toFloat()
        }
        pillContainer = container

        val iconView = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_save)
            setColorFilter(0xFF60A5FA.toInt())
            layoutParams = LinearLayout.LayoutParams(dpToPx(18f), dpToPx(18f)).apply {
                marginEnd = dpToPx(8f)
            }
        }
        pillIconView = iconView
        container.addView(iconView)

        val textView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
            text = "Loading…"
            maxLines = 1
        }
        pillTextView = textView
        container.addView(textView)

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initialX
            y = initialY
        }
        windowParams = layoutParams

        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var initialTouchX = 0f
        var initialTouchY = 0f
        var initialWindowX = 0
        var initialWindowY = 0
        var isDragging = false
        var isLongPressTriggered = false

        val longPressRunnable = Runnable {
            if (!isDragging) {
                isLongPressTriggered = true
                triggerHapticFeedback()
                showOverlayMenu()
            }
        }

        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    initialWindowX = layoutParams.x
                    initialWindowY = layoutParams.y
                    isDragging = false
                    isLongPressTriggered = false
                    mainHandler.postDelayed(longPressRunnable, 500)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()

                    if (!isDragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        isDragging = true
                        mainHandler.removeCallbacks(longPressRunnable)
                    }

                    if (isDragging) {
                        layoutParams.x = initialWindowX + dx
                        layoutParams.y = initialWindowY + dy
                        windowManager.updateViewLayout(container, layoutParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    if (isDragging) {
                        serviceScope.launch {
                            preferences.saveOverlayPosition(layoutParams.x, layoutParams.y)
                        }
                    } else if (!isLongPressTriggered) {
                        // TAP: perform copyNext
                        onOverlayTapped()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    true
                }
                else -> false
            }
        }

        overlayView = container
        windowManager.addView(container, layoutParams)
        updateOverlayUI(currentQueueState)
    }

    private fun updateOverlayUI(state: QueueState) {
        val textView = pillTextView ?: return
        if (state.isAllDone) {
            textView.text = "All done"
            pillIconView?.setImageResource(android.R.drawable.checkbox_on_background)
            pillIconView?.setColorFilter(0xFF10B981.toInt())
        } else {
            val rawCode = state.currentCode
            val shortened = if (rawCode != null) {
                if (rawCode.length > 9) {
                    "${rawCode.take(4)}…${rawCode.takeLast(3)}"
                } else rawCode
            } else "Next"

            val countText = "${state.remainingCount} left"
            textView.text = "$shortened · $countText"
            pillIconView?.setImageResource(android.R.drawable.ic_menu_save)
            pillIconView?.setColorFilter(0xFF60A5FA.toInt())
        }
    }

    private fun onOverlayTapped() {
        triggerHapticFeedback()
        flashVisualFeedback()

        serviceScope.launch {
            queueManager.copyNext()
        }
    }

    private fun flashVisualFeedback() {
        val container = pillContainer ?: return
        val originalBg = container.background

        // Flash green
        val flashBg = GradientDrawable().apply {
            setColor(0xEE065F46.toInt()) // Deep emerald
            cornerRadius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24f, resources.displayMetrics)
            setStroke(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1.5f, resources.displayMetrics).toInt(), 0xFF34D399.toInt())
        }
        container.background = flashBg
        pillIconView?.setImageResource(android.R.drawable.checkbox_on_background)
        pillIconView?.setColorFilter(0xFF34D399.toInt())

        mainHandler.postDelayed({
            container.background = originalBg
            updateOverlayUI(currentQueueState)
        }, 300)
    }

    private fun triggerHapticFeedback() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(30)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun showOverlayMenu() {
        val options = arrayOf(
            "Repeat current code",
            "Mark Failed & Next",
            "Undo previous",
            "Open CouponVault",
            "Close overlay"
        )

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("CouponVault Overlay")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> { // Repeat
                        triggerHapticFeedback()
                        serviceScope.launch { queueManager.repeat() }
                    }
                    1 -> { // Failed
                        triggerHapticFeedback()
                        serviceScope.launch { queueManager.markCurrentFailed() }
                    }
                    2 -> { // Undo
                        triggerHapticFeedback()
                        serviceScope.launch { queueManager.undo() }
                    }
                    3 -> { // Open CouponVault
                        val intent = Intent(this, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        }
                        startActivity(intent)
                    }
                    4 -> { // Close overlay
                        serviceScope.launch {
                            preferences.setOverlayEnabled(false)
                            stopSelf()
                        }
                    }
                }
            }
            .create()
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                } else {
                    window?.setType(WindowManager.LayoutParams.TYPE_PHONE)
                }
            }
            .show()
    }
}
