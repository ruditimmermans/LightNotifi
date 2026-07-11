package com.light.lightnotifi

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.light.lightnotifi.ui.theme.LightNotifiTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LightNotifiTheme {
                MainScreen()
            }
        }
    }
}

data class AppInfo(
    val name: String,
    val packageName: String,
    var isSelected: Boolean
)

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var apps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isNotificationEnabled by remember { mutableStateOf(isNotificationServiceEnabled(context)) }
    var isOverlayEnabled by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var batteryOptimizedStatus by remember { mutableStateOf(!isIgnoringBatteryOptimizations(context)) }
    var appState by remember { mutableStateOf("Unknown") }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isNotificationEnabled = isNotificationServiceEnabled(context)
                isOverlayEnabled = Settings.canDrawOverlays(context)
                batteryOptimizedStatus = !isIgnoringBatteryOptimizations(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    DisposableEffect(ProcessLifecycleOwner.get()) {
        val observer = LifecycleEventObserver { _, event ->
            appState = when (event) {
                Lifecycle.Event.ON_START -> "Foreground"
                Lifecycle.Event.ON_STOP -> "Background"
                else -> appState
            }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(observer)
        onDispose {
            ProcessLifecycleOwner.get().lifecycle.removeObserver(observer)
        }
    }

    val sharedPrefs = context.getSharedPreferences("LightNotifiPrefs", Context.MODE_PRIVATE)

    LaunchedEffect(Unit) {
        apps = getInstalledApps(context, sharedPrefs)
        isLoading = false
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.Black,
        topBar = {
            Column(modifier = Modifier.padding(top = 48.dp, start = 16.dp, end = 16.dp)) {
                Text("LightOS Notification App", style = MaterialTheme.typography.headlineMedium, color = Color.White)
                Spacer(modifier = Modifier.height(8.dp))
                
                Text("App State: $appState", color = Color.White)
                Text("Notification Access: ${if (isNotificationEnabled) "Granted" else "Denied"}", color = Color.White)
                Text("Overlay Permission: ${if (isOverlayEnabled) "Granted" else "Denied"}", color = Color.White)
                Text("Battery Optimization: ${if (batteryOptimizedStatus) "Enabled (May kill app)" else "Disabled (Safe)"}", color = Color.White)
                
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Button(onClick = { openNotificationAccessSettings(context) }, modifier = Modifier.weight(1f)) {
                        Text("Notif Access", style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Button(onClick = { openOverlaySettings(context) }, modifier = Modifier.weight(1f)) {
                        Text("Overlay", style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Button(onClick = { requestIgnoreBatteryOptimizations(context) }, modifier = Modifier.weight(1f)) {
                        Text("Battery", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    ) { innerPadding ->
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
            ) {
                items(apps) { app ->
                    AppItem(app) { isChecked ->
                        val selectedApps = sharedPrefs.getStringSet("selected_apps", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
                        if (isChecked) {
                            selectedApps.add(app.packageName)
                        } else {
                            selectedApps.remove(app.packageName)
                        }
                        sharedPrefs.edit().putStringSet("selected_apps", selectedApps).apply()
                        
                        // Restart service to pick up changes if needed, 
                        // or service can just read prefs on every notification
                    }
                }
            }
        }
    }
}

@Composable
fun AppItem(app: AppInfo, onCheckedChange: (Boolean) -> Unit) {
    AndroidView(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        factory = { context ->
            LightToggle(context).apply {
                setText(app.name)
                isChecked = app.isSelected
                setOnCheckedChangeListener { isChecked ->
                    onCheckedChange(isChecked)
                }
            }
        },
        update = { view ->
            view.setText(app.name)
            view.isChecked = app.isSelected
        }
    )
}

suspend fun getInstalledApps(context: Context, sharedPrefs: android.content.SharedPreferences): List<AppInfo> = withContext(Dispatchers.IO) {
    val pm = context.packageManager
    val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
    val selectedApps = sharedPrefs.getStringSet("selected_apps", emptySet()) ?: emptySet()
    
    val excludedPackages = setOf("com.android.vending", "com.google.android.gsf")
    
    packages
        .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 } // Filter out system apps
        .filter { it.packageName !in excludedPackages }
        .map {
            AppInfo(
                name = it.loadLabel(pm).toString(),
                packageName = it.packageName,
                isSelected = selectedApps.contains(it.packageName)
            )
        }.sortedBy { it.name }
}

fun openNotificationAccessSettings(context: Context) {
    val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    context.startActivity(intent)
}

fun openOverlaySettings(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
        context.startActivity(intent)
    }
}

fun isNotificationServiceEnabled(context: Context): Boolean {
    val pkgName = context.packageName
    val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
    if (flat != null) {
        val names = flat.split(":").toTypedArray()
        for (name in names) {
            val cn = android.content.ComponentName.unflattenFromString(name)
            if (cn != null) {
                if (pkgName == cn.packageName) {
                    return true
                }
            }
        }
    }
    return false
}

fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        powerManager.isIgnoringBatteryOptimizations(context.packageName)
    } else {
        true
    }
}

fun requestIgnoreBatteryOptimizations(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        context.startActivity(intent)
    }
}
