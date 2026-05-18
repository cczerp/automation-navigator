package com.navigator.automation.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.core.app.NotificationCompat
import com.navigator.automation.MainActivity
import com.navigator.automation.R

class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private var overlayView: View? = null

    companion object {
        private const val CHANNEL_ID   = "overlay_service"
        private const val NOTIF_ID     = 1
        var isRunning = false
            private set
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        startForeground(NOTIF_ID, buildNotification())
        showOverlay()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        removeOverlay()
    }

    // ── Overlay ───────────────────────────────────────────────────────────

    private fun showOverlay() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16; y = 200
        }

        val container = FrameLayout(this)
        val btn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_play_circle)
            background = null
            alpha = 0.85f
            setPadding(12, 12, 12, 12)
            setOnClickListener { openMainApp() }
        }

        // Drag to move
        var dragX = 0; var dragY = 0; var startRawX = 0f; var startRawY = 0f
        btn.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragX = params.x; dragY = params.y
                    startRawX = ev.rawX; startRawY = ev.rawY
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (dragX - (ev.rawX - startRawX)).toInt()
                    params.y = (dragY + (ev.rawY - startRawY)).toInt()
                    wm.updateViewLayout(container, params)
                    true
                }
                else -> false
            }
        }

        container.addView(btn)
        wm.addView(container, params)
        overlayView = container
    }

    private fun removeOverlay() {
        overlayView?.let { wm.removeView(it) }
        overlayView = null
    }

    private fun openMainApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
    }

    // ── Notification ──────────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Overlay", NotificationManager.IMPORTANCE_LOW)
        )
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Automation Navigator")
            .setContentText("Tap to open")
            .setSmallIcon(R.drawable.ic_play_circle)
            .setContentIntent(tapIntent)
            .build()
    }
}
