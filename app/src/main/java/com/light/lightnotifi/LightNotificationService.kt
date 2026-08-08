package com.light.lightnotifi

import androidx.compose.ui.unit.sp

import android.app.ActivityOptions
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.*
import androidx.savedstate.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker as ComposeVelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.abs
import kotlin.math.roundToInt

class LightNotificationService : NotificationListenerService(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    companion object {
        const val ACTION_SHOW_PREVIEW = "com.light.lightnotifi.ACTION_SHOW_PREVIEW"
        val notificationsState = mutableStateListOf<NotificationData>()

        var instance: LightNotificationService? = null
            private set

        fun dismissNotification(key: String) {
            instance?.let { service ->
                service.serviceScope.launch {
                    notificationsState.removeAll { it.key == key }
                    if (key != "preview_notification") {
                        try {
                            service.cancelNotification(key)
                        } catch (e: Exception) {
                            // Might not have permission or already gone
                        }
                    }
                    if (service.swipeNotificationsCache) {
                        if (notificationsState.isEmpty()) {
                            service.removeSwipeOverlay()
                        }
                    } else {
                        service.removeOverlay(key)
                    }
                }
            }
        }
    }

    private var windowManager: WindowManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val activeOverlays = LinkedHashMap<String, View>()
    private val dismissJobs = mutableMapOf<String, Job>()
    private val serviceScope = CoroutineScope(Dispatchers.Main.immediate + Job())
    private var selectedAppsCache: Set<String> = emptySet()
    private var horizontalLayoutCache: Boolean = false
    private var swipeNotificationsCache: Boolean = false
    private var wakeScreenCache: Boolean = false
    private var showOnLockScreenCache: Boolean = false
    private var verticalOffsetCache: Float = 55f
    private var notificationDurationCache: Float = 5f
    private var sizeScaleCache: Float = 1.0f

    private var swipeOverlayView: View? = null

    private val screenStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_ON) {
                if (showOnLockScreenCache && notificationsState.isNotEmpty()) {
                    val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
                    if (keyguardManager.isDeviceLocked) {
                        val activityIntent = Intent(this@LightNotificationService, NotificationActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            putExtra("extra_is_manual_wake", true)
                        }
                        startActivity(activityIntent)
                    }
                }
            }
        }
    }

    // Lifecycle boilerplate for ComposeView in Service
    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    data class NotificationData(
        val key: String,
        val title: String,
        val text: String,
        val packageName: String,
        val contentIntent: PendingIntent?,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
        when (key) {
            "selected_apps" -> {
                selectedAppsCache = sharedPreferences.getStringSet("selected_apps", emptySet()) ?: emptySet()
            }
            "horizontal_layout" -> {
                horizontalLayoutCache = sharedPreferences.getBoolean("horizontal_layout", false)
                serviceScope.launch {
                    updateOverlayPositions()
                }
            }
            "swipe_notifications" -> {
                swipeNotificationsCache = sharedPreferences.getBoolean("swipe_notifications", false)
                serviceScope.launch {
                    clearAllOverlays()
                }
            }
            "wake_screen" -> {
                wakeScreenCache = sharedPreferences.getBoolean("wake_screen", false)
            }
            "show_on_lock_screen" -> {
                showOnLockScreenCache = sharedPreferences.getBoolean("show_on_lock_screen", false)
                serviceScope.launch {
                    clearAllOverlays()
                }
            }
            "vertical_offset" -> {
                verticalOffsetCache = sharedPreferences.getFloat("vertical_offset", 55f)
                serviceScope.launch {
                    updateOverlayPositions()
                    if (swipeOverlayView != null) {
                        windowManager?.updateViewLayout(swipeOverlayView, createSwipeLayoutParams())
                    }
                }
            }
            "notification_duration" -> {
                notificationDurationCache = sharedPreferences.getFloat("notification_duration", 5f)
            }
            "notification_size" -> {
                sizeScaleCache = sharedPreferences.getFloat("notification_size", 1.0f)
                serviceScope.launch {
                    updateOverlayPositions()
                    if (swipeOverlayView != null) {
                        windowManager?.updateViewLayout(swipeOverlayView, createSwipeLayoutParams())
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        
        val sharedPrefs = getSharedPreferences("LightNotifiPrefs", MODE_PRIVATE)
        selectedAppsCache = sharedPrefs.getStringSet("selected_apps", emptySet()) ?: emptySet()
        horizontalLayoutCache = sharedPrefs.getBoolean("horizontal_layout", false)
        swipeNotificationsCache = sharedPrefs.getBoolean("swipe_notifications", false)
        wakeScreenCache = sharedPrefs.getBoolean("wake_screen", false)
        showOnLockScreenCache = sharedPrefs.getBoolean("show_on_lock_screen", false)
        verticalOffsetCache = sharedPrefs.getFloat("vertical_offset", 55f)
        notificationDurationCache = sharedPrefs.getFloat("notification_duration", 5f)
        sizeScaleCache = sharedPrefs.getFloat("notification_size", 1.0f)
        sharedPrefs.registerOnSharedPreferenceChangeListener(prefsListener)

        val filter = android.content.IntentFilter(Intent.ACTION_SCREEN_ON)
        registerReceiver(screenStateReceiver, filter)

        startForegroundService()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SHOW_PREVIEW) {
            showOverlay(
                key = "preview_notification",
                title = getString(R.string.preview_notification_title),
                text = getString(R.string.preview_notification_text),
                packageName = packageName,
                contentIntent = null
            )
        }
        return super.onStartCommand(intent, flags, startId)
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

                // Filter out empty notifications or "keep-alive" maintenance notifications
                // Special case: Always allow Luma (com.vandam.luma) notifications through
                if (packageName != "com.vandam.luma" && title.isBlank() && text.isNullOrEmpty()) {
                    return
                }

                showOverlay(it.key, title, text ?: "", packageName, it.notification.contentIntent)
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?, reason: Int) {
        sbn?.let {
            // Luma Compatibility: If the notification was removed by another listener (like Luma)
            // but not by the user, we keep our overlay visible so it can still be seen.
            if (reason == REASON_LISTENER_CANCEL) {
                return
            }

            serviceScope.launch {
                if (swipeNotificationsCache) {
                    notificationsState.removeAll { data -> data.key == it.key }
                    if (notificationsState.isEmpty()) {
                        removeSwipeOverlay()
                    }
                } else {
                    removeOverlay(it.key)
                }
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // This is the older API, we handle removal in the more detailed overload above.
    }

    private fun isAppSelected(packageName: String): Boolean {
        return selectedAppsCache.contains(packageName)
    }

    private fun showOverlay(key: String, title: String, text: String, packageName: String, contentIntent: PendingIntent?) {
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        val isLocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            keyguardManager.isDeviceLocked
        } else {
            keyguardManager.isKeyguardLocked
        }

        if (wakeScreenCache) {
            try {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                if (wakeLock == null) {
                    wakeLock = powerManager.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                        "LightNotifi:WakeScreen"
                    )
                }
                wakeLock?.let {
                    if (it.isHeld) it.release()
                    it.acquire(3000)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        serviceScope.launch {
            if (showOnLockScreenCache && isLocked) {
                notificationsState.removeAll { it.key == key }
                notificationsState.add(NotificationData(key, title, text, packageName, contentIntent))
                
                val intent = Intent(this@LightNotificationService, NotificationActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                startActivity(intent)

                if (true) { // Always dismiss after timeout
                    dismissJobs[key]?.cancel()
                    dismissJobs[key] = launch {
                        val duration = (notificationDurationCache * 1000).toLong()
                        delay(if (key == "preview_notification") Math.max(duration, 10000L) else duration)
                        notificationsState.removeAll { it.key == key }
                    }
                }
                return@launch
            }
            if (swipeNotificationsCache) {
                // Swipe Mode
                val existing = notificationsState.find { it.key == key }
                if (existing != null) {
                    // Just update positions via the state if it already exists
                    // Compose will handle the re-render
                } else {
                    notificationsState.add(NotificationData(key, title, text, packageName, contentIntent))
                }
                
                // Limit to 10 for swipe mode to avoid memory issues
                if (notificationsState.size > 10) {
                    notificationsState.removeAt(0)
                }
                
                if (swipeOverlayView == null) {
                    addSwipeOverlay()
                }

                if (true) { // Always dismiss after timeout
                    dismissJobs[key]?.cancel()
                    dismissJobs[key] = launch {
                        val duration = (notificationDurationCache * 1000).toLong()
                        delay(if (key == "preview_notification") Math.max(duration, 10000L) else duration)
                        notificationsState.removeAll { it.key == key }
                        if (notificationsState.isEmpty()) {
                            removeSwipeOverlay()
                        }
                    }
                }
            } else {
                // Individual Overlay Mode (Vertical or Grid)
                if (activeOverlays.containsKey(key)) {
                    // For previews, we don't want to re-inflate if it's already there
                    // updateOverlayPositions will handle the visual updates via the preference listener
                    if (key != "preview_notification") {
                        removeOverlay(key, reposition = false)
                    } else {
                        // Refresh the dismiss timer for the preview
                        dismissJobs[key]?.cancel()
                        dismissJobs[key] = launch {
                            val duration = (notificationDurationCache * 1000).toLong()
                            delay(Math.max(duration, 10000L))
                            removeOverlay(key)
                        }
                        return@launch
                    }
                }

                if (activeOverlays.size >= 4) {
                    activeOverlays.keys.firstOrNull()?.let { removeOverlay(it, reposition = false) }
                }

                val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
                val view = inflater.inflate(R.layout.overlay_layout, null)
                
                setupOverlayView(view, key, title, text, packageName, contentIntent)
                
                activeOverlays[key] = view
                
                val index = activeOverlays.size - 1
                val params = createLayoutParams(index)
                
                try {
                    windowManager?.addView(view, params)
                    updateOverlayPositions()
                    
                    dismissJobs[key]?.cancel()
                    dismissJobs[key] = launch {
                        delay((notificationDurationCache * 1000).toLong())
                        removeOverlay(key)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    activeOverlays.remove(key)
                }
            }
        }
    }

    private fun setupOverlayView(view: View, key: String, title: String, text: String, packageName: String, contentIntent: PendingIntent?) {
        val contentContainer = view.findViewById<View>(R.id.overlay_content_container)
        contentContainer?.setOnClickListener {
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
                dismissNotification(key)
            }
        }

        // Add swipe to dismiss
        view.setOnTouchListener(createSwipeDismissListener(key) {
            dismissNotification(key)
        })

        val titleView = view.findViewById<TextView>(R.id.overlay_title)
        val textView = view.findViewById<TextView>(R.id.overlay_text)
        
        titleView?.text = title
        textView?.text = text
        
        titleView?.textSize = 17f * sizeScaleCache
        textView?.textSize = 16f * sizeScaleCache
        
        val density = resources.displayMetrics.density
        val scaledPaddingVertical = (12 * sizeScaleCache * density).toInt()
        val scaledPaddingHorizontalStart = (12 * sizeScaleCache * density).toInt()
        val scaledPaddingHorizontalEnd = (14 * sizeScaleCache * density).toInt()
        view.setPadding(scaledPaddingHorizontalStart, scaledPaddingVertical, scaledPaddingHorizontalEnd, scaledPaddingVertical)
        
        val iconView = view.findViewById<ImageView>(R.id.overlay_icon)
        iconView?.layoutParams?.width = (36 * sizeScaleCache * density).toInt()
        iconView?.layoutParams?.height = (36 * sizeScaleCache * density).toInt()
        iconView?.setPadding((6 * sizeScaleCache * density).toInt(), (6 * sizeScaleCache * density).toInt(), (6 * sizeScaleCache * density).toInt(), (6 * sizeScaleCache * density).toInt())
        
        if (horizontalLayoutCache) {
            val compactWidth = (170 * sizeScaleCache * density).toInt()
            titleView?.maxWidth = compactWidth
            textView?.maxWidth = compactWidth
            textView?.maxLines = 2
        } else {
            val fullWidth = (260 * sizeScaleCache * density).toInt()
            titleView?.maxWidth = fullWidth
            textView?.maxWidth = fullWidth
        }
        
        iconView?.setImageResource(R.mipmap.ic_launcher)
    }

    private fun createLayoutParams(index: Int): WindowManager.LayoutParams {
        val density = resources.displayMetrics.density
        
        var xOffset = 0
        var yOffset: Float
        
        if (horizontalLayoutCache && activeOverlays.size > 1) {
            val row = index / 2
            val col = index % 2
            yOffset = (verticalOffsetCache + row * (100 * sizeScaleCache)) * density
            xOffset = if (col == 0) -(96 * sizeScaleCache * density).toInt() else (96 * sizeScaleCache * density).toInt()
        } else if (horizontalLayoutCache && activeOverlays.size == 1) {
            yOffset = verticalOffsetCache * density
            xOffset = 0
        } else {
            yOffset = (verticalOffsetCache + index * (92 * sizeScaleCache)) * density
            xOffset = 0
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    (if (showOnLockScreenCache) {
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or 
                        WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON
                    } else 0) or
                    (if (wakeScreenCache) {
                        0 // Controlled by WakeLock in showOverlay
                    } else 0),
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
        
        activeOverlays.forEach { (key, view) ->
            // Skip views that are being swiped (simplified check: if translation is significantly non-zero, maybe skip)
            // But actually, we want to reset them if they didn't dismiss.
            val params = view.layoutParams as? WindowManager.LayoutParams
            if (params != null) {
                // ...
                var newX = 0
                var newY: Int
                
                if (horizontalLayoutCache && activeOverlays.size > 1) {
                    val row = index / 2
                    val col = index % 2
                    newY = ((verticalOffsetCache + row * (100 * sizeScaleCache)) * density).toInt()
                    newX = if (col == 0) -(96 * sizeScaleCache * density).toInt() else (96 * sizeScaleCache * density).toInt()
                } else if (horizontalLayoutCache && activeOverlays.size == 1) {
                    newY = (verticalOffsetCache * density).toInt()
                    newX = 0
                } else {
                    newY = ((verticalOffsetCache + index * (92 * sizeScaleCache)) * density).toInt()
                    newX = 0
                }

                val titleView = view.findViewById<TextView>(R.id.overlay_title)
                val textView = view.findViewById<TextView>(R.id.overlay_text)
                
                titleView?.textSize = 17f * sizeScaleCache
                textView?.textSize = 16f * sizeScaleCache
                
                val scaledPaddingVertical = (12 * sizeScaleCache * density).toInt()
                val scaledPaddingHorizontalStart = (12 * sizeScaleCache * density).toInt()
                val scaledPaddingHorizontalEnd = (14 * sizeScaleCache * density).toInt()
                view.setPadding(scaledPaddingHorizontalStart, scaledPaddingVertical, scaledPaddingHorizontalEnd, scaledPaddingVertical)

                val iconView = view.findViewById<ImageView>(R.id.overlay_icon)
                iconView?.layoutParams?.width = (36 * sizeScaleCache * density).toInt()
                iconView?.layoutParams?.height = (36 * sizeScaleCache * density).toInt()
                iconView?.setPadding((6 * sizeScaleCache * density).toInt(), (6 * sizeScaleCache * density).toInt(), (6 * sizeScaleCache * density).toInt(), (6 * sizeScaleCache * density).toInt())

                val compactWidth = if (horizontalLayoutCache) (170 * sizeScaleCache * density).toInt() else (260 * sizeScaleCache * density).toInt()
                titleView?.maxWidth = compactWidth
                textView?.maxWidth = compactWidth
                textView?.maxLines = 2

                if (params.y != newY || params.x != newX || key == "preview_notification") {
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

    private fun addSwipeOverlay() {
        val composeView = ComposeView(this).apply {
            setContent {
                NotificationCarousel(
                    notifications = notificationsState,
                    sizeScale = sizeScaleCache,
                    onNotificationClick = { data ->
                        handleNotificationClick(data)
                        dismissNotification(data.key)
                    },
                    onNotificationDismiss = { data ->
                        dismissNotification(data.key)
                    }
                )
            }
        }

        // Set ViewTree owners for Compose
        composeView.setViewTreeLifecycleOwner(this)
        composeView.setViewTreeViewModelStoreOwner(this)
        composeView.setViewTreeSavedStateRegistryOwner(this)
        
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        
        val params = createSwipeLayoutParams()
        try {
            windowManager?.addView(composeView, params)
            swipeOverlayView = composeView
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun removeSwipeOverlay() {
        swipeOverlayView?.let {
            try {
                windowManager?.removeView(it)
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            swipeOverlayView = null
        }
    }

    private fun createSwipeLayoutParams(): WindowManager.LayoutParams {
        val density = resources.displayMetrics.density
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    (if (showOnLockScreenCache) {
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or 
                        WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON
                    } else 0) or
                    (if (wakeScreenCache) {
                        0 // Controlled by WakeLock in showOverlay
                    } else 0),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = (verticalOffsetCache * density).toInt()
            windowAnimations = android.R.style.Animation_Toast
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        return params
    }

    private fun handleNotificationClick(data: NotificationData) {
        try {
            val options = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                    .toBundle()
            } else {
                null
            }

            if (data.contentIntent != null) {
                val fillInIntent = Intent().apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                data.contentIntent.send(this@LightNotificationService, 0, fillInIntent, null, null, null, options)
            } else {
                val launchIntent = packageManager.getLaunchIntentForPackage(data.packageName)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(launchIntent, options)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun clearAllOverlays() {
        val keys = activeOverlays.keys.toList()
        keys.forEach { removeOverlay(it, reposition = false) }
        notificationsState.clear()
        removeSwipeOverlay()
    }

    private fun createSwipeDismissListener(key: String, onDismiss: () -> Unit): View.OnTouchListener {
        return object : View.OnTouchListener {
            private var initialTouchX = 0f
            private var initialTranslationX = 0f
            private var isSwiping = false
            private var velocityTracker: VelocityTracker? = null
            private val touchSlop = ViewConfiguration.get(this@LightNotificationService).scaledTouchSlop
            private val minVelocity = ViewConfiguration.get(this@LightNotificationService).scaledMinimumFlingVelocity
            
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialTouchX = event.rawX
                        initialTranslationX = v.translationX
                        isSwiping = false
                        velocityTracker = VelocityTracker.obtain()
                        velocityTracker?.addMovement(event)
                        return false // Allow click listener
                    }
                    MotionEvent.ACTION_MOVE -> {
                        velocityTracker?.addMovement(event)
                        val deltaX = event.rawX - initialTouchX
                        
                        if (!isSwiping && abs(deltaX) > touchSlop) {
                            isSwiping = true
                            // Cancel long clicks or other gestures on the view
                            v.parent.requestDisallowInterceptTouchEvent(true)
                            val cancelEvent = MotionEvent.obtain(event)
                            cancelEvent.action = MotionEvent.ACTION_CANCEL
                            v.dispatchTouchEvent(cancelEvent)
                            cancelEvent.recycle()
                        }
                        
                        if (isSwiping) {
                            v.translationX = initialTranslationX + deltaX
                            val width = v.width.toFloat()
                            v.alpha = 1f - (abs(deltaX) / width).coerceAtMost(0.7f)
                            return true
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (isSwiping) {
                            velocityTracker?.addMovement(event)
                            velocityTracker?.computeCurrentVelocity(1000)
                            val velocityX = velocityTracker?.xVelocity ?: 0f
                            val deltaX = event.rawX - initialTouchX
                            val width = v.width.toFloat()
                            
                            val dismissThreshold = width * 0.4f
                            val isFling = abs(velocityX) > minVelocity * 2
                            val isSwipedFarEnough = abs(deltaX) > dismissThreshold
                            
                            if (isSwipedFarEnough || (isFling && (deltaX > 0) == (velocityX > 0))) {
                                val targetX = if (deltaX > 0 || (isFling && velocityX > 0)) width * 1.5f else -width * 1.5f
                                v.animate()
                                    .translationX(targetX)
                                    .alpha(0f)
                                    .setDuration(250)
                                    .withEndAction { onDismiss() }
                                    .start()
                            } else {
                                v.animate()
                                    .translationX(0f)
                                    .alpha(1f)
                                    .setDuration(250)
                                    .start()
                            }
                            
                            velocityTracker?.recycle()
                            velocityTracker = null
                            return true
                        }
                        velocityTracker?.recycle()
                        velocityTracker = null
                    }
                }
                return false
            }
        }
    }

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        instance = null
        try {
            unregisterReceiver(screenStateReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        super.onDestroy()
        val sharedPrefs = getSharedPreferences("LightNotifiPrefs", MODE_PRIVATE)
        sharedPrefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        clearAllOverlays()
        serviceScope.cancel()
    }
}

@Composable
fun NotificationCarousel(
    notifications: List<LightNotificationService.NotificationData>,
    sizeScale: Float,
    onNotificationClick: (LightNotificationService.NotificationData) -> Unit,
    onNotificationDismiss: (LightNotificationService.NotificationData) -> Unit
) {
    val pagerState = rememberPagerState(pageCount = { notifications.size })
    
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = (16 * sizeScale).dp),
            pageSpacing = (12 * sizeScale).dp
        ) { page ->
            if (page < notifications.size) {
                val data = notifications[page]
                CarouselNotificationItem(
                    data = data,
                    sizeScale = sizeScale,
                    onClick = { onNotificationClick(data) },
                    onDismiss = { onNotificationDismiss(data) }
                )
            }
        }
        
        if (notifications.size > 1) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                Modifier
                    .height(8.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                repeat(notifications.size) { iteration ->
                    val color = if (pagerState.currentPage == iteration) Color.White else Color.Gray.copy(alpha = 0.5f)
                    Box(
                        modifier = Modifier
                            .padding(2.dp)
                            .clip(CircleShape)
                            .background(color)
                            .size(6.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun CarouselNotificationItem(
    data: LightNotificationService.NotificationData,
    sizeScale: Float,
    onClick: () -> Unit,
    onDismiss: () -> Unit = {}
) {
    var offsetY by remember { mutableFloatStateOf(0f) }
    var alpha by remember { mutableFloatStateOf(1f) }
    val density = LocalDensity.current
    val dismissThreshold = with(density) { 120.dp.toPx() }
    val scope = rememberCoroutineScope()

    Row(
        modifier = Modifier
            .offset { IntOffset(0, offsetY.roundToInt()) }
            .pointerInput(Unit) {
                val velocityTracker = ComposeVelocityTracker()
                detectDragGestures(
                    onDragStart = {
                        velocityTracker.resetTracking()
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        velocityTracker.addPosition(change.uptimeMillis, change.position)
                        offsetY += dragAmount.y
                        alpha = 1f - (kotlin.math.abs(offsetY) / (dismissThreshold * 2f)).coerceAtMost(0.8f)
                    },
                    onDragEnd = {
                        val velocity = velocityTracker.calculateVelocity().y
                        if (kotlin.math.abs(offsetY) > dismissThreshold || kotlin.math.abs(velocity) > 1000f) {
                            onDismiss()
                        } else {
                            scope.launch {
                                val startOffset = offsetY
                                val startAlpha = alpha
                                val duration = 250
                                val startTime = System.currentTimeMillis()
                                while (System.currentTimeMillis() - startTime < duration) {
                                    val progress = (System.currentTimeMillis() - startTime).toFloat() / duration
                                    offsetY = startOffset * (1f - progress)
                                    alpha = startAlpha + (1f - startAlpha) * progress
                                    delay(16)
                                }
                                offsetY = 0f
                                alpha = 1f
                            }
                        }
                    },
                    onDragCancel = {
                        offsetY = 0f
                        alpha = 1f
                    }
                )
            }
            .fillMaxWidth()
            .graphicsLayer(alpha = alpha)
            .clip(RoundedCornerShape(28.dp))
            .background(Color.Black)
            .clickable { onClick() }
            .padding(horizontal = (12 * sizeScale).dp, vertical = (12 * sizeScale).dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size((36 * sizeScale).dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black)
                .padding((6 * sizeScale).dp)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_light_notifi),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.fillMaxSize()
            )
        }
        
        Spacer(modifier = Modifier.width((12 * sizeScale).dp))
        
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = data.title,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = (17 * sizeScale).sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = data.text,
                color = Color.White.copy(alpha = 0.8f),
                fontSize = (16 * sizeScale).sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// I need to add sp unit helper or just use sp from unit package.
// Actually, it's already available via import if I use sp.
// But wait, I missed importing sp.
