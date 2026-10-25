package com.example.couponvault.overlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.couponvault.CouponVaultApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class OverlayActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_COPY_NEXT = "com.example.couponvault.ACTION_OVERLAY_COPY_NEXT"
        const val ACTION_REPEAT = "com.example.couponvault.ACTION_OVERLAY_REPEAT"
        const val ACTION_FAILED = "com.example.couponvault.ACTION_OVERLAY_FAILED"
        const val ACTION_STOP = "com.example.couponvault.ACTION_OVERLAY_STOP"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext as? CouponVaultApplication ?: return
        val queueManager = app.queueManager
        val preferences = app.overlayPreferences

        when (intent?.action) {
            ACTION_COPY_NEXT -> {
                CoroutineScope(Dispatchers.Default).launch {
                    queueManager.copyNext()
                }
            }
            ACTION_REPEAT -> {
                CoroutineScope(Dispatchers.Default).launch {
                    queueManager.repeat()
                }
            }
            ACTION_FAILED -> {
                CoroutineScope(Dispatchers.Default).launch {
                    queueManager.markCurrentFailed()
                }
            }
            ACTION_STOP -> {
                CoroutineScope(Dispatchers.Default).launch {
                    preferences.setOverlayEnabled(false)
                    val stopIntent = Intent(context, FloatingOverlayService::class.java)
                    context.stopService(stopIntent)
                }
            }
        }
    }
}
