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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.light.lightnotifi.ui.theme.LightNotifiTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LightNotifiTheme {
                var currentScreen by remember { mutableStateOf("main") }
                
                if (currentScreen == "main") {
                    MainScreen(onAboutClick = { currentScreen = "about" })
                } else {
                    AboutScreen(onBackClick = { currentScreen = "main" })
                }
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
fun MainScreen(onAboutClick: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val sharedPrefs = context.getSharedPreferences("LightNotifiPrefs", Context.MODE_PRIVATE)

    var apps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isNotificationEnabled by remember { mutableStateOf(isNotificationServiceEnabled(context)) }
    var isOverlayEnabled by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var batteryOptimizedStatus by remember { mutableStateOf(!isIgnoringBatteryOptimizations(context)) }
    var stayUntilDismissed by remember { mutableStateOf(sharedPrefs.getBoolean("stay_until_dismissed", false)) }
    var horizontalLayout by remember { mutableStateOf(sharedPrefs.getBoolean("horizontal_layout", false)) }
    var swipeNotifications by remember { mutableStateOf(sharedPrefs.getBoolean("swipe_notifications", false)) }
    var wakeScreen by remember { mutableStateOf(sharedPrefs.getBoolean("wake_screen", false)) }
    var verticalOffset by remember { mutableFloatStateOf(sharedPrefs.getFloat("vertical_offset", 55f)) }
    var appStateResId by remember { mutableIntStateOf(R.string.state_unknown) }

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
            appStateResId = when (event) {
                Lifecycle.Event.ON_START -> R.string.state_foreground
                Lifecycle.Event.ON_STOP -> R.string.state_background
                else -> appStateResId
            }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(observer)
        onDispose {
            ProcessLifecycleOwner.get().lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(Unit) {
        apps = getInstalledApps(context, sharedPrefs)
        isLoading = false
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.Black,
        topBar = {
            Column(modifier = Modifier.padding(top = 48.dp, start = 16.dp, end = 16.dp, bottom = 8.dp)) {
                val allPermissionsGranted = isNotificationEnabled && isOverlayEnabled && !batteryOptimizedStatus
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (allPermissionsGranted) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        } else {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_light_notifi),
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.main_screen_title), style = MaterialTheme.typography.headlineMedium, color = Color.White)
                    }
                    Button(
                        onClick = onAboutClick,
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Black, contentColor = Color.White),
                        border = BorderStroke(1.dp, Color.White),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text(stringResource(R.string.btn_info), style = MaterialTheme.typography.bodySmall)
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
                item {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_light_notifi),
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.app_state_label, stringResource(appStateResId)), color = Color.White)
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isNotificationEnabled) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            } else {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_light_notifi),
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.notification_access_label, if (isNotificationEnabled) stringResource(R.string.status_granted) else stringResource(R.string.status_denied)), color = Color.White)
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isOverlayEnabled) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            } else {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_light_notifi),
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.overlay_permission_label, if (isOverlayEnabled) stringResource(R.string.status_granted) else stringResource(R.string.status_denied)), color = Color.White)
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (!batteryOptimizedStatus) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            } else {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_light_notifi),
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.battery_optimization_label, if (batteryOptimizedStatus) stringResource(R.string.status_battery_enabled) else stringResource(R.string.status_battery_disabled)), color = Color.White)
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        AndroidView(
                            modifier = Modifier.fillMaxWidth(),
                            factory = { ctx ->
                                LightToggle(ctx).apply {
                                    setText(ctx.getString(R.string.stay_until_dismissed_label))
                                    isChecked = stayUntilDismissed
                                    setOnCheckedChangeListener { isChecked ->
                                        stayUntilDismissed = isChecked
                                        sharedPrefs.edit().putBoolean("stay_until_dismissed", isChecked).apply()
                                    }
                                }
                            },
                            update = { view ->
                                view.isChecked = stayUntilDismissed
                            }
                        )

                        Spacer(modifier = Modifier.height(16.dp))
                        AndroidView(
                            modifier = Modifier.fillMaxWidth(),
                            factory = { ctx ->
                                LightToggle(ctx).apply {
                                    setText(ctx.getString(R.string.horizontal_layout_label))
                                    isChecked = horizontalLayout
                                    setOnCheckedChangeListener { isChecked ->
                                        horizontalLayout = isChecked
                                        sharedPrefs.edit().putBoolean("horizontal_layout", isChecked).apply()
                                    }
                                }
                            },
                            update = { view ->
                                view.isChecked = horizontalLayout
                            }
                        )

                        Spacer(modifier = Modifier.height(16.dp))
                        AndroidView(
                            modifier = Modifier.fillMaxWidth(),
                            factory = { ctx ->
                                LightToggle(ctx).apply {
                                    setText(ctx.getString(R.string.swipe_notifications_label))
                                    isChecked = swipeNotifications
                                    setOnCheckedChangeListener { isChecked ->
                                        swipeNotifications = isChecked
                                        sharedPrefs.edit().putBoolean("swipe_notifications", isChecked).apply()
                                    }
                                }
                            },
                            update = { view ->
                                view.isChecked = swipeNotifications
                            }
                        )

                        Spacer(modifier = Modifier.height(16.dp))
                        AndroidView(
                            modifier = Modifier.fillMaxWidth(),
                            factory = { ctx ->
                                LightToggle(ctx).apply {
                                    setText(ctx.getString(R.string.wake_screen_label))
                                    isChecked = wakeScreen
                                    setOnCheckedChangeListener { isChecked ->
                                        wakeScreen = isChecked
                                        sharedPrefs.edit().putBoolean("wake_screen", isChecked).apply()
                                    }
                                }
                            },
                            update = { view ->
                                view.isChecked = wakeScreen
                            }
                        )

                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.notification_position_label, verticalOffset.toInt()),
                            color = Color.White,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Slider(
                            value = verticalOffset,
                            onValueChange = { 
                                verticalOffset = it
                                sharedPrefs.edit().putFloat("vertical_offset", it).apply()
                            },
                            valueRange = 0f..400f,
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = Color.White,
                                inactiveTrackColor = Color.Gray
                            )
                        )

                        Spacer(modifier = Modifier.height(16.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Button(
                                onClick = { openNotificationAccessSettings(context) },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Black, contentColor = Color.White),
                                border = BorderStroke(1.dp, Color.White),
                                contentPadding = PaddingValues(horizontal = 4.dp)
                            ) {
                                Text(stringResource(R.string.btn_notif_access), style = MaterialTheme.typography.bodySmall)
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            Button(
                                onClick = { openOverlaySettings(context) },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Black, contentColor = Color.White),
                                border = BorderStroke(1.dp, Color.White),
                                contentPadding = PaddingValues(horizontal = 4.dp)
                            ) {
                                Text(stringResource(R.string.btn_overlay), style = MaterialTheme.typography.bodySmall)
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            Button(
                                onClick = { requestIgnoreBatteryOptimizations(context) },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Black, contentColor = Color.White),
                                border = BorderStroke(1.dp, Color.White),
                                contentPadding = PaddingValues(horizontal = 4.dp)
                            ) {
                                Text(stringResource(R.string.btn_battery), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(24.dp))
                        HorizontalDivider(color = Color.DarkGray, thickness = 1.dp)
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
                items(apps) { app ->
                    AppItem(app) { isChecked ->
                        val currentSelected = sharedPrefs.getStringSet("selected_apps", emptySet()) ?: emptySet()
                        val newSelected = currentSelected.toMutableSet()
                        if (isChecked) {
                            newSelected.add(app.packageName)
                        } else {
                            newSelected.remove(app.packageName)
                        }
                        sharedPrefs.edit().putStringSet("selected_apps", newSelected).apply()
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

@Composable
fun AboutScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0)
    }
    val version = packageInfo.versionName ?: "Unknown"

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.Black,
        topBar = {
            Column(modifier = Modifier.padding(top = 48.dp, start = 16.dp, end = 16.dp)) {
                Text(stringResource(R.string.about_title), style = MaterialTheme.typography.headlineMedium, color = Color.White)
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineLarge,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.app_version, version),
                style = MaterialTheme.typography.bodyLarge,
                color = Color.Gray
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.copyright),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.license_text),
                style = MaterialTheme.typography.bodySmall,
                color = Color.LightGray,
                textAlign = TextAlign.Justify
            )
            Spacer(modifier = Modifier.height(32.dp))
            
            Button(
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://ko-fi.com/ruditimmermans"))
                    context.startActivity(intent)
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Black, contentColor = Color.White),
                border = BorderStroke(1.dp, Color.White)
            ) {
                Text(stringResource(R.string.btn_donate))
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Button(
                onClick = onBackClick,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Black, contentColor = Color.White),
                border = BorderStroke(1.dp, Color.White)
            ) {
                Text(stringResource(R.string.btn_back))
            }
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

suspend fun getInstalledApps(context: Context, sharedPrefs: android.content.SharedPreferences): List<AppInfo> = withContext(Dispatchers.IO) {
    val pm = context.packageManager
    val packages = pm.getInstalledApplications(0)
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
