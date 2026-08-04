package com.light.lightnotifi

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.view.MotionEvent

class NotificationActivity : ComponentActivity() {

    companion object {
        const val EXTRA_IS_MANUAL_WAKE = "extra_is_manual_wake"
    }

    private var inactivityJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val sharedPrefs = getSharedPreferences("LightNotifiPrefs", MODE_PRIVATE)
        val wakeScreenPref = sharedPrefs.getBoolean("wake_screen", false)
        val isManualWake = intent.getBooleanExtra(EXTRA_IS_MANUAL_WAKE, false)

        setShowWhenLocked(true)
        // setTurnScreenOn is intentionally NOT used to prevent the OS from keeping the screen on too long.
        // We rely strictly on a timed WakeLock instead.
        
        // Allow the screen to turn off even while this activity is on top of the lock screen
        window.addFlags(WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON)

        if (wakeScreenPref && !isManualWake) {
            try {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                val wakeLock = powerManager.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "LightNotifi:ActivityWake"
                )
                // Using 3000ms as requested for a quick peek
                wakeLock.acquire(3000)
                
                // Auto finish after 3 seconds to ensure the screen turns off immediately
                lifecycleScope.launch {
                    delay(3000)
                    if (!isDestroyed && !isFinishing) {
                        finish()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else {
            // If it's a manual wake, or wakeScreenPref is off, still apply a 30s safety timeout
            resetInactivityTimer()
        }

        setContent {
            val context = LocalContext.current
            val sharedPrefs = remember { context.getSharedPreferences("LightNotifiPrefs", MODE_PRIVATE) }
            val notificationSize = sharedPrefs.getFloat("notification_size", 1.0f)
            val verticalOffset = sharedPrefs.getFloat("vertical_offset", 55f)
            val stayUntilDismissed = sharedPrefs.getBoolean("stay_until_dismissed", false)

            LaunchedEffect(LightNotificationService.notificationsState.size) {
                if (LightNotificationService.notificationsState.isEmpty()) {
                    finish()
                }
            }

            Surface(
                color = Color.Transparent,
                modifier = Modifier.fillMaxSize()
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = verticalOffset.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        NotificationCarousel(
                            notifications = LightNotificationService.notificationsState,
                            sizeScale = notificationSize,
                            onNotificationClick = { data ->
                                handleNotificationClick(data)
                                LightNotificationService.notificationsState.removeAll { it.key == data.key }
                                if (LightNotificationService.notificationsState.isEmpty()) {
                                    finish()
                                }
                            },
                            onDismiss = { data ->
                                LightNotificationService.notificationsState.removeAll { it.key == data.key }
                                if (LightNotificationService.notificationsState.isEmpty()) {
                                    finish()
                                }
                            },
                            stayUntilDismissed = stayUntilDismissed
                        )
                    }
                }
            }
        }
    }

    private fun handleNotificationClick(data: LightNotificationService.NotificationData) {
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
                data.contentIntent.send(this, 0, fillInIntent, null, null, null, options)
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

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        resetInactivityTimer()
        return super.dispatchTouchEvent(ev)
    }

    private fun resetInactivityTimer() {
        inactivityJob?.cancel()
        inactivityJob = lifecycleScope.launch {
            delay(30000) // 30 seconds safety timeout
            if (!isDestroyed && !isFinishing) {
                finish()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        inactivityJob?.cancel()
    }
}
