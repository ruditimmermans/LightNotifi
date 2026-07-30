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

class NotificationActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val sharedPrefs = getSharedPreferences("LightNotifiPrefs", MODE_PRIVATE)
        val wakeScreenPref = sharedPrefs.getBoolean("wake_screen", false)

        setShowWhenLocked(true)
        setTurnScreenOn(wakeScreenPref)
        
        // Allow the screen to turn off even while this activity is on top of the lock screen
        window.addFlags(WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON)

        if (wakeScreenPref) {
            try {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                val wakeLock = powerManager.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "LightNotifi:ActivityWake"
                )
                wakeLock.acquire(3000) // Force screen on for exactly 3 seconds
            } catch (e: Exception) {
                e.printStackTrace()
            }
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
}
