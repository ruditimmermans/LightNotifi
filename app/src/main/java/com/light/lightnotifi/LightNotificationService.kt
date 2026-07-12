package com.light.lightnotifi

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class LightNotificationService : NotificationListenerService() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForegroundService()
    }

    private fun startForegroundService() {
        val channelId = "LightNotificationChannel"
        val channel = NotificationChannel(
            channelId,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.notif_active_title))
            .setContentText(getString(R.string.notif_monitoring_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn?.let {
            val packageName = it.packageName
            if (isAppSelected(packageName)) {
                val title = it.notification.extras.getString("android.title") ?: getString(R.string.new_notification_default_title)
                val text = it.notification.extras.getString("android.text") ?: ""
                showOverlay(title, text, packageName)
            }
        }
    }

    private fun isAppSelected(packageName: String): Boolean {
        val sharedPrefs = getSharedPreferences("LightNotifiPrefs", Context.MODE_PRIVATE)
        val selectedApps = sharedPrefs.getStringSet("selected_apps", emptySet())
        return selectedApps?.contains(packageName) == true
    }

    private fun showOverlay(title: String, text: String, packageName: String) {
        serviceScope.launch {
            if (overlayView != null) {
                hideOverlay()
            }

            val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
            overlayView = inflater.inflate(R.layout.overlay_layout, null)

            val titleTextView = overlayView?.findViewById<TextView>(R.id.overlay_title)
            val contentTextView = overlayView?.findViewById<TextView>(R.id.overlay_text)
            val iconView = overlayView?.findViewById<ImageView>(R.id.overlay_icon)

            titleTextView?.text = title
            contentTextView?.text = text
            
            try {
                val icon = packageManager.getApplicationIcon(packageName)
                iconView?.setImageDrawable(icon)
            } catch (e: Exception) {
                iconView?.setImageResource(R.drawable.ic_light_notifi)
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = (12 * resources.displayMetrics.density).toInt() // 12dp from top
                windowAnimations = android.R.style.Animation_Dialog // Basic animation
            }

            try {
                windowManager?.addView(overlayView, params)
                
                // Hide after 5 seconds
                delay(5000)
                hideOverlay()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun hideOverlay() {
        if (overlayView != null) {
            try {
                windowManager?.removeView(overlayView)
                overlayView = null
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        hideOverlay()
    }
}
