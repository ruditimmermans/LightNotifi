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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.background
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
import java.util.LinkedHashMap
import kotlinx.coroutines.*

class LightNotificationService : NotificationListenerService(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private var windowManager: WindowManager? = null
    private val activeOverlays = LinkedHashMap<String, View>()
    private val dismissJobs = mutableMapOf<String, Job>()
    private val serviceScope = CoroutineScope(Dispatchers.Main.immediate + Job())
    private var selectedAppsCache: Set<String> = emptySet()
    private var stayUntilDismissedCache: Boolean = false
    private var horizontalLayoutCache: Boolean = false
    private var swipeNotificationsCache: Boolean = false

    private val notificationsState = mutableStateListOf<NotificationData>()
    private var swipeOverlayView: View? = null

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
            "stay_until_dismissed" -> {
                stayUntilDismissedCache = sharedPreferences.getBoolean("stay_until_dismissed", false)
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
        }
    }

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        
        val sharedPrefs = getSharedPreferences("LightNotifiPrefs", MODE_PRIVATE)
        selectedAppsCache = sharedPrefs.getStringSet("selected_apps", emptySet()) ?: emptySet()
        stayUntilDismissedCache = sharedPrefs.getBoolean("stay_until_dismissed", false)
        horizontalLayoutCache = sharedPrefs.getBoolean("horizontal_layout", false)
        swipeNotificationsCache = sharedPrefs.getBoolean("swipe_notifications", false)
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

    private fun isAppSelected(packageName: String): Boolean {
        return selectedAppsCache.contains(packageName)
    }

    private fun showOverlay(key: String, title: String, text: String, packageName: String, contentIntent: PendingIntent?) {
        serviceScope.launch {
            if (swipeNotificationsCache) {
                // Swipe Mode
                notificationsState.removeAll { it.key == key }
                notificationsState.add(NotificationData(key, title, text, packageName, contentIntent))
                
                // Limit to 10 for swipe mode to avoid memory issues
                if (notificationsState.size > 10) {
                    notificationsState.removeAt(0)
                }
                
                if (swipeOverlayView == null) {
                    addSwipeOverlay()
                }

                if (!stayUntilDismissedCache) {
                    dismissJobs[key]?.cancel()
                    dismissJobs[key] = launch {
                        delay(5000)
                        notificationsState.removeAll { it.key == key }
                        if (notificationsState.isEmpty()) {
                            removeSwipeOverlay()
                        }
                    }
                }
            } else {
                // Individual Overlay Mode (Vertical or Grid)
                if (activeOverlays.containsKey(key)) {
                    removeOverlay(key, reposition = false)
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
                    
                    if (!stayUntilDismissedCache) {
                        dismissJobs[key]?.cancel()
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
        
        titleView?.textSize = 15f
        textView?.textSize = 14f
        
        if (horizontalLayoutCache) {
            val density = resources.displayMetrics.density
            val compactWidth = (170 * density).toInt()
            titleView?.maxWidth = compactWidth
            textView?.maxWidth = compactWidth
            textView?.maxLines = 2
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
            yOffset = (55 + row * 84) * density // Slightly more height for 2-line text
            // Using 96dp as offset for side-by-side to allow wider cards
            xOffset = if (col == 0) -(96 * density).toInt() else (96 * density).toInt()
        } else if (horizontalLayoutCache && activeOverlays.size == 1) {
            // Center the single notification even in horizontal mode
            yOffset = 55 * density
            xOffset = 0
        } else {
            // Vertical layout
            yOffset = (55 + index * 76) * density // Slightly more height for 2-line text
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
                    newY = ((55 + row * 84) * density).toInt()
                    newX = if (col == 0) -(96 * density).toInt() else (96 * density).toInt()
                } else if (horizontalLayoutCache && activeOverlays.size == 1) {
                    newY = (55 * density).toInt()
                    newX = 0
                } else {
                    newY = ((55 + index * 76) * density).toInt()
                    newX = 0
                }

                // Update text max widths if layout mode changed
                val titleView = view.findViewById<TextView>(R.id.overlay_title)
                val textView = view.findViewById<TextView>(R.id.overlay_text)
                
                titleView?.textSize = 15f
                textView?.textSize = 14f
                
                val compactWidth = if (horizontalLayoutCache) (170 * density).toInt() else (260 * density).toInt()
                titleView?.maxWidth = compactWidth
                textView?.maxWidth = compactWidth
                textView?.maxLines = 2

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

    private fun addSwipeOverlay() {
        val composeView = ComposeView(this).apply {
            setContent {
                NotificationCarousel(
                    notifications = notificationsState,
                    onNotificationClick = { data ->
                        handleNotificationClick(data)
                        notificationsState.removeAll { it.key == data.key }
                        if (notificationsState.isEmpty()) {
                            removeSwipeOverlay()
                        }
                    },
                    onDismiss = { data ->
                        notificationsState.removeAll { it.key == data.key }
                        if (notificationsState.isEmpty()) {
                            removeSwipeOverlay()
                        }
                    },
                    stayUntilDismissed = stayUntilDismissedCache
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
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = (55 * density).toInt()
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

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
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
    onNotificationClick: (LightNotificationService.NotificationData) -> Unit,
    onDismiss: (LightNotificationService.NotificationData) -> Unit,
    stayUntilDismissed: Boolean
) {
    val pagerState = rememberPagerState(pageCount = { notifications.size })
    
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp),
            pageSpacing = 12.dp
        ) { page ->
            val data = notifications[page]
            CarouselNotificationItem(
                data = data,
                onClick = { onNotificationClick(data) },
                onClose = { onDismiss(data) },
                stayUntilDismissed = stayUntilDismissed
            )
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
    onClick: () -> Unit,
    onClose: () -> Unit,
    stayUntilDismissed: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(100.dp))
            .background(Color(0xE6000000))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(Color.Black)
                .padding(6.dp)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_light_notifi),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.fillMaxSize()
            )
        }
        
        Spacer(modifier = Modifier.width(12.dp))
        
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = data.title,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = data.text,
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        
        if (stayUntilDismissed) {
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                painter = painterResource(id = R.drawable.ic_close),
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.7f),
                modifier = Modifier
                    .size(20.dp)
                    .clickable { onClose() }
            )
        }
    }
}

// I need to add sp unit helper or just use sp from unit package.
// Actually, it's already available via import if I use sp.
// But wait, I missed importing sp.
