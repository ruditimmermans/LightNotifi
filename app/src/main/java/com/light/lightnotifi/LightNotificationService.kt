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
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*

class LightNotificationService : NotificationListenerService() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main.immediate + Job())
    private var dismissJob: Job? = null
    private var selectedAppsCache: Set<String> = emptySet()
    private var stayUntilDismissedCache: Boolean = false
    private var currentNotificationKey: String? = null

    private val prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
        when (key) {
            "selected_apps" -> {
                selectedAppsCache = sharedPreferences.getStringSet("selected_apps", emptySet()) ?: emptySet()
            }
            "stay_until_dismissed" -> {
                stayUntilDismissedCache = sharedPreferences.getBoolean("stay_until_dismissed", false)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        
        val sharedPrefs = getSharedPreferences("LightNotifiPrefs", MODE_PRIVATE)
        selectedAppsCache = sharedPrefs.getStringSet("selected_apps", emptySet()) ?: emptySet()
        stayUntilDismissedCache = sharedPrefs.getBoolean("stay_until_dismissed", false)
        sharedPrefs.registerOnSharedPreferenceChangeListener(prefsListener)

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

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.notif_active_title))
            .setContentText(getString(R.string.notif_monitoring_text))
            .setSmallIcon(R.drawable.ic_light_notifi)
            .setColor(ContextCompat.getColor(this, R.color.dark_gray))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .build()

        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn?.let {
            val packageName = it.packageName
            if (isAppSelected(packageName)) {
                currentNotificationKey = it.key
                val extras = it.notification.extras
                
                val title = extras.getCharSequence(NotificationCompat.EXTRA_TITLE)?.toString()
                    ?: extras.getCharSequence(NotificationCompat.EXTRA_TITLE_BIG)?.toString()
                    ?: getString(R.string.new_notification_default_title)
                
                var text = extras.getCharSequence(NotificationCompat.EXTRA_TEXT)?.toString()
                
                if (text.isNullOrEmpty()) {
                    val messagingStyle = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(it.notification)
                    messagingStyle?.messages?.lastOrNull()?.let { message ->
                        val sender = message.person?.name
                        val content = message.text?.toString()
                        text = if (!sender.isNullOrEmpty()) "$sender: $content" else content
                    }
                }

                if (text.isNullOrEmpty()) {
                    text = extras.getCharSequence(NotificationCompat.EXTRA_BIG_TEXT)?.toString()
                }
                
                if (text.isNullOrEmpty()) {
                    val textLines = extras.getCharSequenceArray(NotificationCompat.EXTRA_TEXT_LINES)
                    if (!textLines.isNullOrEmpty()) {
                        text = textLines.last().toString()
                    }
                }
                
                if (text.isNullOrEmpty()) {
                    text = extras.getCharSequence(NotificationCompat.EXTRA_SUB_TEXT)?.toString()
                }
                
                if (text.isNullOrEmpty()) {
                    text = extras.getCharSequence(NotificationCompat.EXTRA_SUMMARY_TEXT)?.toString()
                }

                if (text.isNullOrEmpty()) {
                    text = it.notification.tickerText?.toString()
                }

                showOverlay(title, text ?: "", packageName, it.notification.contentIntent)
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn?.key == currentNotificationKey && !stayUntilDismissedCache) {
            hideOverlay()
        }
    }

    private fun isAppSelected(packageName: String): Boolean {
        return selectedAppsCache.contains(packageName)
    }

    private fun showOverlay(title: String, text: String, packageName: String, contentIntent: PendingIntent?) {
        serviceScope.launch {
            dismissJob?.cancel()
            if (overlayView != null) {
                hideOverlay()
            }

            val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
            overlayView = inflater.inflate(R.layout.overlay_layout, null)

            overlayView?.setOnClickListener {
                try {
                    if (contentIntent != null) {
                        // Use send(Context, int, Intent) to ensure the intent is started correctly from a service
                        val fillInIntent = Intent().apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        contentIntent.send(this@LightNotificationService, 0, fillInIntent)
                    } else {
                        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
                        if (launchIntent != null) {
                            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(launchIntent)
                        }
                    }
                    
                    if (!stayUntilDismissedCache) {
                        hideOverlay()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    // Fallback to launch intent if PendingIntent fails
                    try {
                        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
                        launchIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(launchIntent)
                    } catch (ex: Exception) {
                        ex.printStackTrace()
                    }
                    if (!stayUntilDismissedCache) {
                        hideOverlay()
                    }
                }
            }

            val titleTextView = overlayView?.findViewById<TextView>(R.id.overlay_title)
            val contentTextView = overlayView?.findViewById<TextView>(R.id.overlay_text)
            val iconView = overlayView?.findViewById<ImageView>(R.id.overlay_icon)
            val closeButton = overlayView?.findViewById<ImageView>(R.id.overlay_close)

            titleTextView?.text = title
            contentTextView?.text = text
            
            iconView?.setImageResource(R.drawable.ic_light_notifi)

            if (stayUntilDismissedCache) {
                closeButton?.visibility = View.VISIBLE
                closeButton?.setOnClickListener {
                    hideOverlay()
                }
            } else {
                closeButton?.visibility = View.GONE
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
                
                if (!stayUntilDismissedCache) {
                    dismissJob = launch {
                        delay(5000)
                        hideOverlay()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun hideOverlay() {
        dismissJob?.cancel()
        currentNotificationKey = null
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
        val sharedPrefs = getSharedPreferences("LightNotifiPrefs", MODE_PRIVATE)
        sharedPrefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        serviceScope.cancel()
        hideOverlay()
    }
}
