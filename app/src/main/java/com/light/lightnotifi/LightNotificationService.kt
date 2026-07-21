package com.light.lightnotifi

import android.app.ActivityOptions
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
import java.util.LinkedHashMap
import kotlinx.coroutines.*

class LightNotificationService : NotificationListenerService() {

    private var windowManager: WindowManager? = null
    private val activeOverlays = LinkedHashMap<String, View>()
    private val dismissJobs = mutableMapOf<String, Job>()
    private val serviceScope = CoroutineScope(Dispatchers.Main.immediate + Job())
    private var selectedAppsCache: Set<String> = emptySet()
    private var stayUntilDismissedCache: Boolean = false
    private var horizontalLayoutCache: Boolean = false

    private val prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
        when (key) {
            "selected_apps" -> {
                selectedAppsCache = sharedPreferences.getStringSet("selected_apps", emptySet()) ?: emptySet()
            }
            "stay_until_dismissed" -> {
                stayUntilDismissedCache = sharedPreferences.getBoolean("stay_until_dismissed", false)
            }
            "horizontal_layout" -> {
                horizontalLayoutCache = sharedPreferences.getBoolean("horizontal_layout", false)
                serviceScope.launch {
                    updateOverlayPositions()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        
        val sharedPrefs = getSharedPreferences("LightNotifiPrefs", MODE_PRIVATE)
        selectedAppsCache = sharedPrefs.getStringSet("selected_apps", emptySet()) ?: emptySet()
        stayUntilDismissedCache = sharedPrefs.getBoolean("stay_until_dismissed", false)
        horizontalLayoutCache = sharedPrefs.getBoolean("horizontal_layout", false)
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
                // Filter out group summary notifications (e.g. WhatsApp "X new messages")
                if ((it.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY) != 0) {
                    return
                }

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

                showOverlay(it.key, title, text ?: "", packageName, it.notification.contentIntent)
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn?.let {
            serviceScope.launch {
                removeOverlay(it.key)
            }
        }
    }

    private fun isAppSelected(packageName: String): Boolean {
        return selectedAppsCache.contains(packageName)
    }

    private fun showOverlay(key: String, title: String, text: String, packageName: String, contentIntent: PendingIntent?) {
        serviceScope.launch {
            // If already showing this notification, remove the old view first to refresh it
            if (activeOverlays.containsKey(key)) {
                removeOverlay(key, reposition = false)
            }

            // Limit to 4 concurrent overlays to avoid clutter
            if (activeOverlays.size >= 4) {
                activeOverlays.keys.firstOrNull()?.let { removeOverlay(it, reposition = false) }
            }

            val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
            val view = inflater.inflate(R.layout.overlay_layout, null)
            
            setupOverlayView(view, key, title, text, packageName, contentIntent)
            
            activeOverlays[key] = view
            
            // Calculate index for the new overlay
            val index = activeOverlays.size - 1
            val params = createLayoutParams(index)
            
            try {
                windowManager?.addView(view, params)
                
                // Reposition all overlays to ensure they are correctly spaced
                updateOverlayPositions()
                
                if (!stayUntilDismissedCache) {
                    dismissJobs[key] = launch {
                        delay(5000)
                        removeOverlay(key)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                activeOverlays.remove(key)
            }
        }
    }

    private fun setupOverlayView(view: View, key: String, title: String, text: String, packageName: String, contentIntent: PendingIntent?) {
        // ... (existing code for contentContainer click listener) ...
        // I will keep the existing code but update the max width dynamically if needed.
        // Actually, let's update setupOverlayView to handle compact mode.
        val contentContainer = view.findViewById<View>(R.id.overlay_content_container)
        contentContainer?.setOnClickListener {
            // ...
            try {
                val options = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ActivityOptions.makeBasic()
                        .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                        .toBundle()
                } else {
                    null
                }

                if (contentIntent != null) {
                    val fillInIntent = Intent().apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    contentIntent.send(this@LightNotificationService, 0, fillInIntent, null, null, null, options)
                } else {
                    val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(launchIntent, options)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                removeOverlay(key)
            }
        }

        val titleView = view.findViewById<TextView>(R.id.overlay_title)
        val textView = view.findViewById<TextView>(R.id.overlay_text)
        
        titleView?.text = title
        textView?.text = text
        
        if (horizontalLayoutCache) {
            val density = resources.displayMetrics.density
            val compactWidth = (110 * density).toInt()
            titleView?.maxWidth = compactWidth
            textView?.maxWidth = compactWidth
        }
        
        view.findViewById<ImageView>(R.id.overlay_icon)?.setImageResource(R.mipmap.ic_launcher)

        val closeButton = view.findViewById<ImageView>(R.id.overlay_close)
        if (stayUntilDismissedCache) {
            closeButton?.visibility = View.VISIBLE
            closeButton?.setOnClickListener {
                removeOverlay(key)
            }
        } else {
            closeButton?.visibility = View.GONE
        }
    }

    private fun createLayoutParams(index: Int): WindowManager.LayoutParams {
        val density = resources.displayMetrics.density
        
        var xOffset = 0
        var yOffset: Float
        
        if (horizontalLayoutCache && activeOverlays.size > 1) {
            val row = index / 2
            val col = index % 2
            yOffset = (16 + row * 64) * density
            // Using 92dp as offset for side-by-side
            xOffset = if (col == 0) -(92 * density).toInt() else (92 * density).toInt()
        } else if (horizontalLayoutCache && activeOverlays.size == 1) {
            // Center the single notification even in horizontal mode
            yOffset = 16 * density
            xOffset = 0
        } else {
            // Vertical layout
            yOffset = (16 + index * 56) * density
            xOffset = 0
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = xOffset
            y = yOffset.toInt()
            windowAnimations = android.R.style.Animation_Toast
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        return params
    }

    private fun removeOverlay(key: String, reposition: Boolean = true) {
        dismissJobs.remove(key)?.cancel()
        val view = activeOverlays.remove(key)
        if (view != null) {
            try {
                windowManager?.removeView(view)
                if (reposition) {
                    updateOverlayPositions()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun updateOverlayPositions() {
        var index = 0
        val density = resources.displayMetrics.density
        
        activeOverlays.forEach { (_, view) ->
            val params = view.layoutParams as? WindowManager.LayoutParams
            if (params != null) {
                var newX = 0
                var newY: Int
                
                if (horizontalLayoutCache && activeOverlays.size > 1) {
                    val row = index / 2
                    val col = index % 2
                    newY = ((16 + row * 64) * density).toInt()
                    newX = if (col == 0) -(92 * density).toInt() else (92 * density).toInt()
                } else if (horizontalLayoutCache && activeOverlays.size == 1) {
                    newY = (16 * density).toInt()
                    newX = 0
                } else {
                    newY = ((16 + index * 56) * density).toInt()
                    newX = 0
                }

                // Update text max widths if layout mode changed
                val titleView = view.findViewById<TextView>(R.id.overlay_title)
                val textView = view.findViewById<TextView>(R.id.overlay_text)
                val compactWidth = if (horizontalLayoutCache) (110 * density).toInt() else (200 * density).toInt()
                titleView?.maxWidth = compactWidth
                textView?.maxWidth = compactWidth

                if (params.y != newY || params.x != newX) {
                    params.y = newY
                    params.x = newX
                    try {
                        windowManager?.updateViewLayout(view, params)
                    } catch (e: Exception) {
                        // View might have been removed already
                    }
                }
            }
            index++
        }
    }

    private fun clearAllOverlays() {
        val keys = activeOverlays.keys.toList()
        keys.forEach { removeOverlay(it, reposition = false) }
    }

    override fun onDestroy() {
        super.onDestroy()
        val sharedPrefs = getSharedPreferences("LightNotifiPrefs", MODE_PRIVATE)
        sharedPrefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        clearAllOverlays()
        serviceScope.cancel()
    }
}
