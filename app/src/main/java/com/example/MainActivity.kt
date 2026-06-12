package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import androidx.navigation.compose.*
import coil.compose.AsyncImage
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        var allGranted = true
        permissions.entries.forEach { (perm, granted) ->
            if (!granted) {
                allGranted = false
                Log("Permission denied: $perm")
            }
        }
        if (allGranted) {
            Toast.makeText(this, "تم منح جميع الصلاحيات اللازمة", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "بعض الصلاحيات لم تُمنح، قد تتأثر دقة التحكم", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Double check Firebase on Startup
        SyncEngine.checkFirebase(this)

        setContent {
            MyApplicationTheme {
                val navController = rememberNavController()
                var currentRole by remember { mutableStateOf<String?>(null) } // "admin" or "user" or null

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        if (currentRole != null) {
                            NavigationBar(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                tonalElevation = 8.dp
                            ) {
                                NavigationBarItem(
                                    selected = currentRole == "admin",
                                    onClick = {
                                        currentRole = "admin"
                                        navController.navigate("admin_login") {
                                            popUpTo(navController.graph.startDestinationId) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    icon = { Icon(Icons.Default.SupervisorAccount, contentDescription = "مشرف") },
                                    label = { Text("بوابة المشرف", fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                                )
                                NavigationBarItem(
                                    selected = currentRole == "user",
                                    onClick = {
                                        currentRole = "user"
                                        navController.navigate("user_agent") {
                                            popUpTo(navController.graph.startDestinationId) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    icon = { Icon(Icons.Default.AppSettingsAlt, contentDescription = "عميل") },
                                    label = { Text("جهاز العميل", fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding)) {
                        NavHost(navController = navController, startDestination = "launcher_gate") {
                            composable("launcher_gate") {
                                LauncherGateScreen(
                                    onSelectAdmin = {
                                        currentRole = "admin"
                                        navController.navigate("admin_login")
                                    },
                                    onSelectUser = {
                                        currentRole = "user"
                                        navController.navigate("user_agent")
                                    }
                                )
                            }
                            composable("admin_login") {
                                LoginScreen(navController)
                            }
                            composable("admin_dashboard") {
                                AdminDashboard(navController)
                            }
                            composable("device_control/{deviceId}") { backStackEntry ->
                                val deviceId = backStackEntry.arguments?.getString("deviceId") ?: "mock"
                                DeviceControlScreen(deviceId, navController)
                            }
                            composable("user_agent") {
                                UserAgentScreen(
                                    onRequestPermissions = { requestAllApplicationPermissions() },
                                    onStartService = { triggerForegroundService(true) },
                                    onStopService = { triggerForegroundService(false) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun requestAllApplicationPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        requestPermissionLauncher.launch(permissions.toTypedArray())
    }

    private fun triggerForegroundService(start: Boolean) {
        val serviceIntent = Intent(this, UserForegroundService::class.java)
        if (start) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Toast.makeText(this, "تم تشغيل الخدمة بنجاح", Toast.LENGTH_SHORT).show()
        } else {
            stopService(serviceIntent)
            Toast.makeText(this, "تم إيقاف الخدمة بنجاح", Toast.LENGTH_SHORT).show()
        }
    }

    private fun Log(msg: String) {
        android.util.Log.d("MainActivity", msg)
    }
}

@Composable
fun LauncherGateScreen(onSelectAdmin: () -> Unit, onSelectUser: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.background
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.AdminPanelSettings,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(96.dp)
            )

            Spacer(Modifier.height(16.dp))

            Text(
                "نظام التحكم والمراقبة عن بعد",
                style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground
            )

            Text(
                "يرجى تحديد دور الاستخدام والتشغيل للمنصة",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 32.dp)
            )

            Card(
                onClick = onSelectAdmin,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .testTag("launcher_admin_card"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.SupervisorAccount,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    Spacer(Modifier.width(16.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "بوابة المشرف (Admin APK)",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "تسجيل الدخول، عرض قائمة الأجهزة وسلسلة المواقع، إرسال الأوامر والاطلاع على لقطات الشاشة والصوت المسترجع.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Card(
                onClick = onSelectUser,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .testTag("launcher_user_card"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.AppSettingsAlt,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    Spacer(Modifier.width(16.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "جهاز العميل (User APK)",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.tertiary
                        )
                        Text(
                            "منح الصلاحيات، تشغيل الخدمة الدائمة بالخلفية، تسجيل وتحديث حالة الاتصال والاطلاع على سجل استلام الأوامر الفوري.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(32.dp))

            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "يعمل التطبيق بمحاكاة ذاتية متزامنة تتيح تجربة دورين معاً في نفس الوقت على المحاكي دون تطلب تهيئة سيرفر خارجي.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }
    }
}

@Composable
fun LoginScreen(navController: NavController) {
    var email by remember { mutableStateOf("admin@remote.com") }
    var password by remember { mutableStateOf("admin12345") }
    var isError by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Security,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(80.dp)
        )

        Spacer(Modifier.height(16.dp))

        Text(
            "تسجيل دخول المشرف",
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground
        )

        Text(
            "أدخل بيانات المشرف للدخول إلى لوحة التحكم والمراقبة",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp, bottom = 32.dp)
        )

        OutlinedTextField(
            value = email,
            onValueChange = {
                email = it
                isError = false
            },
            label = { Text("البريد الإلكتروني") },
            placeholder = { Text("example@admin.com") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .testTag("admin_email_input"),
            shape = RoundedCornerShape(12.dp),
            isError = isError,
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Email, contentDescription = null) }
        )

        OutlinedTextField(
            value = password,
            onValueChange = {
                password = it
                isError = false
            },
            label = { Text("كلمة المرور") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .testTag("admin_password_input"),
            shape = RoundedCornerShape(12.dp),
            isError = isError,
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) }
        )

        if (isError) {
            Text(
                "خطأ في البريد الإلكتروني أو كلمة المرور",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .align(Alignment.Start)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = {
                if (email.isNotEmpty() && password.length >= 6) {
                    navController.navigate("admin_dashboard") {
                        popUpTo("launcher_gate")
                    }
                } else {
                    isError = true
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .testTag("login_submit_button"),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Text(
                "دخول لوحة التحكم",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminDashboard(navController: NavController) {
    val devices by SyncEngine.devices.collectAsState()
    val localContext = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "لوحة تحكم المشرف",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                actions = {
                    IconButton(onClick = {
                        val deviceId = Settings.Secure.getString(localContext.contentResolver, Settings.Secure.ANDROID_ID) ?: "device_id"
                        SyncEngine.registerDevice(localContext, "token_${System.currentTimeMillis()}")
                        Toast.makeText(localContext, "تم توصيل جهازك الحالي كمحاكاة", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "تحديث الأجهزة المتصلة")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            Text(
                "الأجهزة المتصلة بالمنظومة (${devices.size}):",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(Modifier.height(12.dp))

            if (devices.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Devices,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "لا توجد أجهزة متصلة في الوقت الحالي\n(قم بتشغيل جهاز العميل للتوصيل الفوري)",
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(devices) { device ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("device_card_${device.deviceId}"),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(
                                width = 1.dp,
                                color = if (device.isOnline) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                else MaterialTheme.colorScheme.outlineVariant
                            ),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(12.dp)
                                            .clip(CircleShape)
                                            .background(if (device.isOnline) Color(0xFF4CAF50) else Color(0xFFF44336))
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = device.deviceName,
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (device.isOnline) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                                    ) {
                                        Text(
                                            text = if (device.isOnline) "نشط" else "غير متصل",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (device.isOnline) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }

                                Spacer(Modifier.height(8.dp))
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                Spacer(Modifier.height(8.dp))

                                Text(
                                    text = "مُعرف الجهاز: ${device.deviceId}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                val date = SimpleDateFormat("yyyy-MM-dd hh:mm a", Locale.getDefault()).format(Date(device.lastSeen))
                                Text(
                                    text = "آخر ظهور: $date",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Spacer(Modifier.height(16.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            SyncEngine.sendCommand(device.deviceId, "عرض الموقع")
                                            Toast.makeText(localContext, "تم طلب إحداثيات GPS", Toast.LENGTH_SHORT).show()
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.padding(end = 8.dp)
                                    ) {
                                        Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("موقع سريع")
                                    }

                                    Button(
                                        onClick = {
                                            navController.navigate("device_control/${device.deviceId}")
                                        },
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Icon(Icons.Default.SettingsRemote, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("التحكم الكامل")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceControlScreen(deviceId: String, navController: NavController) {
    val context = LocalContext.current
    val commandsMap by SyncEngine.commands.collectAsState()
    val screenshotsMap by SyncEngine.screenshots.collectAsState()
    val locationsMap by SyncEngine.locations.collectAsState()
    val recordingsMap by SyncEngine.recordings.collectAsState()
    val cameraSnipsMap by SyncEngine.cameraSnips.collectAsState()

    val currentCommands = commandsMap[deviceId] ?: emptyList()
    val screenshots = screenshotsMap[deviceId] ?: emptyList()
    val locations = locationsMap[deviceId] ?: emptyList()
    val recordings = recordingsMap[deviceId] ?: emptyList()
    val cameraSnips = cameraSnipsMap[deviceId] ?: emptyList()

    var activeTab by remember { mutableIntStateOf(0) } // 0: Commands, 1: Screenshots, 2: Camera Snaps, 3: Audio Clips, 4: GPS Locations

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("تحكم جهاز: $deviceId", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Command trigger bar
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "أرسل أمراً فورياً إلى جهاز العميل:",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Spacer(Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CommandButton(text = "لقطة شاشة", icon = Icons.Default.Screenshot, onClick = {
                            SyncEngine.sendCommand(deviceId, "لقطة شاشة")
                            Toast.makeText(context, "تم إرسال أمر لقطة الشاشة", Toast.LENGTH_SHORT).show()
                        })
                        CommandButton(text = "قفل الشاشة", icon = Icons.Default.Lock, onClick = {
                            SyncEngine.sendCommand(deviceId, "قفل الشاشة")
                            Toast.makeText(context, "تم إرسال أمر قفل الشاشة", Toast.LENGTH_SHORT).show()
                        })
                        CommandButton(text = "عرض الموقع", icon = Icons.Default.MyLocation, onClick = {
                            SyncEngine.sendCommand(deviceId, "عرض الموقع")
                            Toast.makeText(context, "تم إرسال طلب الموقع الجغرافي", Toast.LENGTH_SHORT).show()
                        })
                        CommandButton(text = "تشغيل الكاميرا", icon = Icons.Default.PhotoCamera, onClick = {
                            SyncEngine.sendCommand(deviceId, "تشغيل الكاميرا")
                            Toast.makeText(context, "تم إرسال أمر التقاط الكاميرا", Toast.LENGTH_SHORT).show()
                        })
                        CommandButton(text = "تسجيل الصوت", icon = Icons.Default.Mic, onClick = {
                            SyncEngine.sendCommand(deviceId, "تسجيل الصوت")
                            Toast.makeText(context, "تم إرسال أمر تسجيل الصوت", Toast.LENGTH_SHORT).show()
                        })
                    }
                }
            }

            // Results sub tab selection row
            SecondaryScrollableTabRow(
                selectedTabIndex = activeTab,
                modifier = Modifier.fillMaxWidth()
            ) {
                Tab(selected = activeTab == 0, onClick = { activeTab = 0 }) {
                    Text("سجل الأوامر", modifier = Modifier.padding(12.dp), fontWeight = FontWeight.Bold)
                }
                Tab(selected = activeTab == 1, onClick = { activeTab = 1 }) {
                    Text("لقطات الشاشة (${screenshots.size})", modifier = Modifier.padding(12.dp), fontWeight = FontWeight.Bold)
                }
                Tab(selected = activeTab == 2, onClick = { activeTab = 2 }) {
                    Text("لقطات الكاميرا (${cameraSnips.size})", modifier = Modifier.padding(12.dp), fontWeight = FontWeight.Bold)
                }
                Tab(selected = activeTab == 3, onClick = { activeTab = 3 }) {
                    Text("التسجيلات الصوتية (${recordings.size})", modifier = Modifier.padding(12.dp), fontWeight = FontWeight.Bold)
                }
                Tab(selected = activeTab == 4, onClick = { activeTab = 4 }) {
                    Text("المواقع الجغرافية (${locations.size})", modifier = Modifier.padding(12.dp), fontWeight = FontWeight.Bold)
                }
            }

            // Sub tabs content views
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
                when (activeTab) {
                    0 -> CommandLogSubTab(currentCommands)
                    1 -> ScreenshotSubTab(screenshots)
                    2 -> CameraSnipSubTab(cameraSnips)
                    3 -> AudioRecordingSubTab(recordings)
                    4 -> LocationSubTab(locations)
                }
            }
        }
    }
}

@Composable
fun CommandButton(text: String, icon: ImageVector, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.testTag("command_btn_$text")
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun CommandLogSubTab(commands: List<CommandModel>) {
    if (commands.isEmpty()) {
        EmptyLogsView(message = "لا توجد أوامر مرسلة بعد")
    } else {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(commands.reversed()) { cmd ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(cmd.command, fontWeight = FontWeight.Bold)
                            val timeStr = SimpleDateFormat("yyyy-MM-dd hh:mm a", Locale.getDefault()).format(Date(cmd.timestamp))
                            Text(timeStr, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }

                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = when (cmd.status) {
                                "executed" -> Color(0xFFE8F5E9)
                                "executing" -> Color(0xFFFFF3E0)
                                else -> Color(0xFFECEFF1)
                            }
                        ) {
                            Text(
                                text = when (cmd.status) {
                                    "executed" -> "تم التنفيذ"
                                    "executing" -> "قيد التشغيل"
                                    else -> "قيد الانتظار"
                                },
                                color = when (cmd.status) {
                                    "executed" -> Color(0xFF2E7D32)
                                    "executing" -> Color(0xFFEF6C00)
                                    else -> Color(0xFF37474F)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ScreenshotSubTab(screenshots: List<ScreenshotModel>) {
    if (screenshots.isEmpty()) {
        EmptyLogsView(message = "لا توجد لقطات شاشة تم استلامها بعد\n(أرسل أمر لقطة الشاشة ليتم المحاكاة فورياً)")
    } else {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(screenshots) { item ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column {
                        AsyncImage(
                            model = item.url,
                            contentDescription = "لقطة الشاشة المسترجعة",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                        )
                        val date = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(item.timestamp))
                        Text(
                            "تم التقاطها: $date",
                            modifier = Modifier.padding(8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CameraSnipSubTab(snips: List<CameraSnipModel>) {
    if (snips.isEmpty()) {
        EmptyLogsView(message = "لم يتم التقاط لقطات الكاميرا بعد")
    } else {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(snips) { item ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column {
                        AsyncImage(
                            model = item.url,
                            contentDescription = "لقطة من الكاميرا",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(150.dp)
                        )
                        val date = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(item.timestamp))
                        Text(
                            "وقت الالتقاط: $date",
                            modifier = Modifier.padding(8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AudioRecordingSubTab(recordings: List<RecordingModel>) {
    var playingId by remember { mutableStateOf<String?>(null) }

    if (recordings.isEmpty()) {
        EmptyLogsView(message = "لا يوجد ملفات لتسجيلات الصوت بعد")
    } else {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(recordings) { item ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.AudioFile, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }

                        Spacer(Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(item.filePath, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                            val dateStr = SimpleDateFormat("yyyy-MM-dd hh:mm:ss a", Locale.getDefault()).format(Date(item.timestamp))
                            Text("المدة: ${item.durationSeconds} ثانية • $dateStr", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }

                        IconButton(
                            onClick = {
                                playingId = if (playingId == item.id) null else item.id
                            }
                        ) {
                            Icon(
                                imageVector = if (playingId == item.id) Icons.Default.PauseCircle else Icons.Default.PlayCircle,
                                contentDescription = "تشغيل",
                                modifier = Modifier.size(36.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LocationSubTab(locations: List<LocationModel>) {
    if (locations.isEmpty()) {
        EmptyLogsView(message = "لا توجد قراءات لتاريخ الإحداثيات")
    } else {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(locations) { loc ->
                Card(
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.LocationOn,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "الموقع الجغرافي: ${loc.latitude} , ${loc.longitude}",
                                fontWeight = FontWeight.Bold
                            )
                            val time = SimpleDateFormat("yyyy-MM-dd hh:mm a", Locale.getDefault()).format(Date(loc.timestamp))
                            Text("تاريخ قراءة GPS: $time", style = MaterialTheme.typography.bodySmall, color = Color.DarkGray)
                        }

                        IconButton(onClick = {
                            // Simulation showing maps details
                        }) {
                            Icon(Icons.Default.Map, contentDescription = "خرائط")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyLogsView(message: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.Analytics,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.size(56.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                message,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserAgentScreen(
    onRequestPermissions: () -> Unit,
    onStartService: () -> Unit,
    onStopService: () -> Unit
) {
    val context = LocalContext.current
    var isPermissionAudioGranted by remember { mutableStateOf(false) }
    var isPermissionLocationGranted by remember { mutableStateOf(false) }
    var isPermissionNotifyGranted by remember { mutableStateOf(false) }
    var isDeviceAdminActive by remember { mutableStateOf(false) }
    var isServiceActiveState by remember { mutableStateOf(false) }

    // Diagnostic loop to actively refresh check statuses
    LaunchedEffect(Unit) {
        while (true) {
            isPermissionAudioGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            isPermissionLocationGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                isPermissionNotifyGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else {
                isPermissionNotifyGranted = true
            }

            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = ComponentName(context, AdminReceiver::class.java)
            isDeviceAdminActive = dpm.isAdminActive(adminComponent)

            // Check if service is currently running
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            @Suppress("DEPRECATION")
            val runningServices = am.getRunningServices(100)
            isServiceActiveState = runningServices.any { it.service.className == UserForegroundService::class.java.name }

            delay(1500)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("عميل المراقبة والمتابعة عن بعد", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onTertiaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isServiceActiveState) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (isServiceActiveState) Icons.Default.PlayCircle else Icons.Default.Cancel,
                            contentDescription = null,
                            tint = if (isServiceActiveState) Color(0xFF2E7D32) else Color(0xFFD32F2F),
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                if (isServiceActiveState) "الخدمة الدائمة نشطة" else "الخدمة متوقفة الآن",
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                color = if (isServiceActiveState) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                if (isServiceActiveState) "جاهز في الخلفية لتلقي الأوامر الفورية" else "الرجاء النقر على بدء الخدمة لبدء الاستماع للأجهزة",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isServiceActiveState) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = onStartService,
                            enabled = !isServiceActiveState,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f).testTag("start_service_btn"),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("بدأ الخدمة", fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = onStopService,
                            enabled = isServiceActiveState,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f).testTag("stop_service_btn"),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828))
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("إيقاف الخدمة", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            Text(
                "متطلبات الصلاحيات:",
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Start,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
            )

            Spacer(Modifier.height(8.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    PermissionRow(title = "صلاحية تسجيل الصوت والميكروفون", granted = isPermissionAudioGranted)
                    PermissionRow(title = "صلاحية استشعار الموقع الجغرافي GPS", granted = isPermissionLocationGranted)
                    PermissionRow(title = "صلاحية الإشعارات الفورية والتنبيهات", granted = isPermissionNotifyGranted)
                    PermissionRow(title = "صلاحية مسؤول وقفل الشاشة الجهاز (Admin Panel)", granted = isDeviceAdminActive)

                    Spacer(Modifier.height(16.dp))

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onRequestPermissions,
                            modifier = Modifier.fillMaxWidth().testTag("req_permissions_btn")
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("منح تراخيص النظام")
                        }

                        OutlinedButton(
                            onClick = {
                                val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                                val adminComponent = ComponentName(context, AdminReceiver::class.java)
                                if (!dpm.isAdminActive(adminComponent)) {
                                    val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                                        putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                                        putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "يُرجى تفعيل الصلاحية لقفل الشاشة فورياً")
                                    }
                                    context.startActivity(intent)
                                } else {
                                    Toast.makeText(context, "صلاحية مسؤول الجهاز نشطة بالفعل", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.fillMaxWidth().testTag("activate_admin_btn")
                        ) {
                            Icon(Icons.Default.LockOpen, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("تنشيط مسؤول الجهاز (Device Admin)")
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "معلومات محاكاة التطبيق الحالية:",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "• اسم العميل: ${Build.MODEL}\n" +
                                "• المُعرف: ${Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)}\n" +
                                "• نظام التشغيل: Android API ${Build.VERSION.SDK_INT}\n" +
                                "• خادم الربط: ${if (SyncEngine.isFirebaseActive) "Firebase Cloud Storage" else "In-Memory Dual Emulator Sync"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp
                    )
                }
            }
        }
    }
}

@Composable
fun PermissionRow(title: String, granted: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (granted) Icons.Default.CheckCircle else Icons.Default.Warning,
            contentDescription = null,
            tint = if (granted) Color(0xFF4CAF50) else Color(0xFFFF9800),
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = if (granted) "نشط" else "معلق",
            color = if (granted) Color(0xFF2E7D32) else Color(0xFFEF6C00),
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
        )
    }
}
