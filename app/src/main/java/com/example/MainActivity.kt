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
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.foundation.lazy.rememberLazyListState
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

    private fun fetchFcmTokenAndUpload() {
        try {
            com.google.firebase.messaging.FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val token = task.result
                    if (!token.isNullOrBlank()) {
                        android.util.Log.i("MainActivity", "Real FCM Token fetched: $token")
                        // Save in memory/Firestore
                        SyncEngine.registerDevice(this, token)
                        // Auto-upload to Firebase Realtime Database
                        SyncEngine.uploadTokenToRealtimeDatabase(this, token)
                    }
                } else {
                    android.util.Log.e("MainActivity", "Failed to retrieve FCM token: ${task.exception?.message}")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error retrieving FCM registration token: ${e.message}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Double check Firebase on Startup
        SyncEngine.checkFirebase(this)

        // Fetch FCM Token and register on startup
        fetchFcmTokenAndUpload()

        setContent {
            MyApplicationTheme {
                val navController = rememberNavController()
                val context = LocalContext.current
                
                // جلب الدور المثبت والمسجل حالياً للجهاز أو وضع البناء المخصص
                val savedRole = remember(context) { AppPreferences.getSavedRole(context) }
                var currentRole by remember { mutableStateOf<String?>(savedRole) }
                
                val startDestination = remember(savedRole) {
                    when (savedRole) {
                        "admin" -> "admin_login"
                        "user" -> "user_agent"
                        else -> "launcher_gate"
                    }
                }

                // Collect Firestore/sync errors and toasts them to the admin/user immediately
                LaunchedEffect(Unit) {
                    SyncEngine.syncErrors.collect { errorMsg ->
                        Toast.makeText(context, errorMsg, Toast.LENGTH_LONG).show()
                    }
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        // لا نظهر شريط التنقل السفلي إلا في وضع التطبيق المزدوج للمحاكاة DUAL وعند عدم تثبيت الدور بشكل دائم
                        if (AppConfig.BUILD_ROLE == AppConfig.ROLE_DUAL && AppPreferences.getSavedRole(context) == null && currentRole != null) {
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
                        NavHost(navController = navController, startDestination = startDestination) {
                            composable("launcher_gate") {
                                LauncherGateScreen(
                                    onSelectAdmin = { permanent ->
                                        if (permanent) {
                                            AppPreferences.saveSavedRole(context, "admin")
                                        }
                                        currentRole = "admin"
                                        navController.navigate("admin_login") {
                                            popUpTo("launcher_gate") { inclusive = true }
                                        }
                                    },
                                    onSelectUser = { permanent ->
                                        if (permanent) {
                                            AppPreferences.saveSavedRole(context, "user")
                                        }
                                        currentRole = "user"
                                        navController.navigate("user_agent") {
                                            popUpTo("launcher_gate") { inclusive = true }
                                        }
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
fun LauncherGateScreen(onSelectAdmin: (Boolean) -> Unit, onSelectUser: (Boolean) -> Unit) {
    var showAdminDialog by remember { mutableStateOf(false) }
    var showUserDialog by remember { mutableStateOf(false) }

    if (showAdminDialog) {
        AlertDialog(
            onDismissRequest = { showAdminDialog = false },
            title = { Text("تأكيد دور لوحة تحكم المشرف", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "تريد فتح بوابة المشرف. يرجى تحديد ما إذا كنت تريد تثبيت هذا الهاتف كلوحة تحكم دائم (كنسخة APK منفصلة للمشرف) أو مجرد تشغيل مؤقت للمحاكاة متبادلة الأطراف:",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showAdminDialog = false
                        onSelectAdmin(true) // permanent lock
                    }
                ) {
                    Text("تثبيت دائم كـ مشرف")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showAdminDialog = false
                        onSelectAdmin(false) // session only
                    }
                ) {
                    Text("تشغيل مؤقت للمحاكاة")
                }
            }
        )
    }

    if (showUserDialog) {
        AlertDialog(
            onDismissRequest = { showUserDialog = false },
            title = { Text("تأكيد دور جهاز العميل", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "تريد فتح تطبيق العميل الخاضع للتتبع. يرجى اختيار ما إذا كنت ترغب في تثبيته بشكل دائم على هذا الهاتف (كحزمة منفصلة للعميل) أو كنسخة تجريبية مؤقتة:",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showUserDialog = false
                        onSelectUser(true) // permanent lock
                    }
                ) {
                    Text("تثبيت دائم كـ عميل")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showUserDialog = false
                        onSelectUser(false) // session only
                    }
                ) {
                    Text("تشغيل مؤقت للمحاكاة")
                }
            }
        )
    }

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
                onClick = { showAdminDialog = true },
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
                            "تسجيل الدخول، عرض قائمة الأجهزة وسلسلة المواقع، إرسال الأوامر والاطلاع على لقطات الشاشة والصوت المسترجع الفوري.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Card(
                onClick = { showUserDialog = true },
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
                        "تستطيع إعادة ضبط خيار تثبيت الجهاز وإظهار الشاشة المزدوجة من جديد عبر النقر على زر إعادة الضبط في شريط العنوان العلوى.",
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
    var selectedTab by remember { mutableStateOf(0) } // 0 = الأجهزة المتصلة, 1 = إشعارات Realtime DB, 2 = إدارة حزمة العميل (APK)

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
                    if (AppConfig.BUILD_ROLE == AppConfig.ROLE_DUAL && AppPreferences.getSavedRole(localContext) != null) {
                        IconButton(onClick = {
                            AppPreferences.clearSavedRole(localContext)
                            Toast.makeText(localContext, "تم إعادة تعيين دور الجهاز. يرجى إعادة تشغيل التطبيق.", Toast.LENGTH_LONG).show()
                        }) {
                            Icon(Icons.Default.Logout, contentDescription = "إعادة ضبط اختيار الدور")
                        }
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
            // شريط التنقل العلوي المخصص للأقسام (Custom M3 Tabs)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(24.dp))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Surface(
                    onClick = { selectedTab = 0 },
                    modifier = Modifier.weight(1.3f),
                    shape = RoundedCornerShape(20.dp),
                    color = if (selectedTab == 0) MaterialTheme.colorScheme.primary else Color.Transparent,
                    contentColor = if (selectedTab == 0) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                ) {
                    Box(
                        modifier = Modifier.padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Devices, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("الأجهزة والتحكم", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                Surface(
                    onClick = { selectedTab = 1 },
                    modifier = Modifier.weight(1.3f),
                    shape = RoundedCornerShape(20.dp),
                    color = if (selectedTab == 1) MaterialTheme.colorScheme.primary else Color.Transparent,
                    contentColor = if (selectedTab == 1) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                ) {
                    Box(
                        modifier = Modifier.padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.NotificationsActive, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("إشعارات RTDB", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                Surface(
                    onClick = { selectedTab = 2 },
                    modifier = Modifier.weight(1.3f),
                    shape = RoundedCornerShape(20.dp),
                    color = if (selectedTab == 2) MaterialTheme.colorScheme.primary else Color.Transparent,
                    contentColor = if (selectedTab == 2) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                ) {
                    Box(
                        modifier = Modifier.padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("مشاركة العميل", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            if (selectedTab == 0) {
                // القسم الأول: الأجهزة المتصلة بالمنظومة للتحكم الفوري
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "الأجهزة المتصلة بالمنظومة (${devices.size}):",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.weight(1f)
                    )
                    
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (SyncEngine.isFirebaseActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (SyncEngine.isFirebaseActive) Color(0xFF4CAF50) else Color(0xFFFF9800))
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (SyncEngine.isFirebaseActive) "سيرفر نشط" else "محاكاة محلية",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (SyncEngine.isFirebaseActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }

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
                                    if (device.lastCommand.isNotEmpty()) {
                                        Spacer(Modifier.height(8.dp))
                                        Surface(
                                            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(10.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    Icons.Default.Info,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.tertiary,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Spacer(Modifier.width(8.dp))
                                                Column {
                                                    Text(
                                                        text = "آخر أمر منفذ: ${device.lastCommand}",
                                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                                        color = MaterialTheme.colorScheme.onTertiaryContainer
                                                    )
                                                    if (device.lastCommandTime > 0) {
                                                        val cmdTimeStr = SimpleDateFormat("hh:mm:ss a", Locale.getDefault()).format(Date(device.lastCommandTime))
                                                        Text(
                                                            text = "وقت الإرسال: $cmdTimeStr",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    Spacer(Modifier.height(12.dp))
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                    Spacer(Modifier.height(12.dp))

                                    Text(
                                        text = "لوحة التحكم السريع بالأوامر:",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.primary
                                    )

                                    Spacer(Modifier.height(8.dp))

                                    var cmdText by remember(device.deviceId) { mutableStateOf("") }

                                    OutlinedTextField(
                                        value = cmdText,
                                        onValueChange = { cmdText = it },
                                        placeholder = { Text("أدخل أمراً مخصصاً أو رسالة للعميل...") },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("device_custom_cmd_input_${device.deviceId}"),
                                        shape = RoundedCornerShape(12.dp),
                                        singleLine = true,
                                        trailingIcon = {
                                            if (cmdText.isNotEmpty()) {
                                                IconButton(onClick = { cmdText = "" }) {
                                                    Icon(Icons.Default.Clear, contentDescription = "مسح")
                                                }
                                            }
                                        },
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                                        )
                                    )

                                    Spacer(Modifier.height(8.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        AssistChip(
                                            onClick = {
                                                val command = if (cmdText.isNotBlank()) "إعادة تشغيل: $cmdText" else "إعادة تشغيل"
                                                SyncEngine.sendCommand(device.deviceId, command)
                                                Toast.makeText(localContext, "تم إرسال أمر إعادة التشغيل", Toast.LENGTH_SHORT).show()
                                                cmdText = ""
                                            },
                                            label = { Text("إعادة تشغيل", fontWeight = FontWeight.Bold, fontSize = 11.sp) },
                                            leadingIcon = {
                                                Icon(
                                                    Icons.Default.Sync,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                            },
                                            colors = AssistChipDefaults.assistChipColors(
                                                leadingIconContentColor = MaterialTheme.colorScheme.error,
                                                labelColor = MaterialTheme.colorScheme.onSurface
                                            ),
                                            modifier = Modifier.weight(1f).testTag("reboot_btn_${device.deviceId}")
                                        )

                                        AssistChip(
                                            onClick = {
                                                val command = if (cmdText.isNotBlank()) "قفل الشاشة: $cmdText" else "قفل الشاشة"
                                                SyncEngine.sendCommand(device.deviceId, command)
                                                Toast.makeText(localContext, "تم إرسال أمر قفل الشاشة", Toast.LENGTH_SHORT).show()
                                                cmdText = ""
                                            },
                                            label = { Text("قفل الشاشة", fontWeight = FontWeight.Bold, fontSize = 11.sp) },
                                            leadingIcon = {
                                                Icon(
                                                    Icons.Default.Lock,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                            },
                                            colors = AssistChipDefaults.assistChipColors(
                                                leadingIconContentColor = MaterialTheme.colorScheme.primary,
                                                labelColor = MaterialTheme.colorScheme.onSurface
                                            ),
                                            modifier = Modifier.weight(1f).testTag("lock_btn_${device.deviceId}")
                                        )

                                        AssistChip(
                                            onClick = {
                                                val command = if (cmdText.isNotBlank()) "أرسل رسالة: $cmdText" else "أرسل رسالة: مرحباً"
                                                SyncEngine.sendCommand(device.deviceId, command)
                                                Toast.makeText(localContext, "تم إرسال الرسالة للعميل", Toast.LENGTH_SHORT).show()
                                                cmdText = ""
                                            },
                                            label = { Text("رسالة", fontWeight = FontWeight.Bold, fontSize = 11.sp) },
                                            leadingIcon = {
                                                Icon(
                                                    Icons.Default.Send,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                            },
                                            colors = AssistChipDefaults.assistChipColors(
                                                leadingIconContentColor = MaterialTheme.colorScheme.tertiary,
                                                labelColor = MaterialTheme.colorScheme.onSurface
                                            ),
                                            modifier = Modifier.weight(1f).testTag("send_msg_btn_${device.deviceId}")
                                        )
                                    }

                                    Spacer(Modifier.height(8.dp))
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
            } else if (selectedTab == 1) {
                // القسم الثاني: إدارة وقراءة رموز الأجهزة من Firebase Realtime Database وإرسال الإشعارات
                RealtimeDatabaseTokensTabContent(localContext)
            } else {
                // القسم الثالث: إدارة ومشاركة حزمة تطبيق العميل المؤمنة (Client APK)
                ClientApkTabContent(localContext)
            }
        }
    }
}

/**
 * محتوى قسم إدارة حزمة العميل (Client APK) - تشفير، ومشاركة، وتنزيل، وتحديث
 */
@Composable
fun ClientApkTabContent(localContext: Context) {
    var apkDetails by remember { mutableStateOf(ClientApkManager.getActiveApkDetails(localContext)) }
    var showExtractionProgress by remember { mutableStateOf(false) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val success = ClientApkManager.upgradeClientApk(localContext, uri)
            if (success) {
                apkDetails = ClientApkManager.getActiveApkDetails(localContext)
                Toast.makeText(localContext, "تم تحديث حزمة العميل وتشفيرها بنجاح!", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(localContext, "خطأ: فشل قراءة أو تعمية حزمة APK المرفوعة", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val writePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            ClientApkManager.saveClientApkToDownloads(
                context = localContext,
                onProgressChange = { showExtractionProgress = it },
                onSuccess = { msg -> Toast.makeText(localContext, msg, Toast.LENGTH_LONG).show() },
                onError = { err -> Toast.makeText(localContext, err, Toast.LENGTH_LONG).show() }
            )
        } else {
            Toast.makeText(localContext, "خطأ: يجب منح صلاحية التخزين لتصدير حزمة التطبيق للجهاز", Toast.LENGTH_SHORT).show()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. بطاقة الأمان التشفيري
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                ),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = "أمان الحزمة",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(44.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "حاوية مدمجة محمية بتشفير ثنائي",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "ملف تطبيق العميل مخزن بحماية تعمية XOR متماثلة لضمان عدم استخراج الحزمة بطرق ملتوية. فك التشفير والمصادقة الأمنية لسلامة البيانات تجري بشكل فوري عند التصدير والمشاركة.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 2. بطاقة المواصفات التعريفية للـ APK الكامن
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "مواصفات وفحوصات حزمة العميل (APK):",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(bottom = 12.dp))

                    MetadataItem(
                        label = "رقم إصدار تطبيق العميل:",
                        value = apkDetails.versionName,
                        icon = Icons.Default.Info,
                        color = MaterialTheme.colorScheme.primary
                    )

                    MetadataItem(
                        label = "حجم الحزمة المشفرة:",
                        value = apkDetails.sizeFormatted,
                        icon = Icons.Default.Folder,
                        color = MaterialTheme.colorScheme.secondary
                    )

                    MetadataItem(
                        label = "تاريخ آخر تعديل وتحديث:",
                        value = apkDetails.lastUpdated,
                        icon = Icons.Default.History,
                        color = MaterialTheme.colorScheme.tertiary
                    )

                    MetadataItem(
                        label = "مصدر حزمة تطبيق العميل:",
                        value = apkDetails.source,
                        icon = Icons.Default.CheckCircle,
                        color = if (apkDetails.source.contains("افتراضي")) MaterialTheme.colorScheme.outline else Color(0xFF4CAF50)
                    )

                    Spacer(modifier = Modifier.height(14.dp))
                    
                    // بصمة SHA-256 للمصادقة والسلامة البرمجية
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "بصمة التحقق والسلامة (SHA-256 Hash):",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = apkDetails.sha256,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // 3. أزرار المشاركة والتصدير الشاملة
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "خيارات تصدير ومشاركة الحاوية:",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )

                    Button(
                        onClick = {
                            ClientApkManager.shareClientApk(
                                context = localContext,
                                onProgressChange = { showExtractionProgress = it },
                                onError = { err -> Toast.makeText(localContext, err, Toast.LENGTH_LONG).show() }
                            )
                        },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(12.dp),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Share, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("مشاركة حزمة تطبيق العميل والبدء بالمراقبة", fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
                                ContextCompat.checkSelfPermission(localContext, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                                ClientApkManager.saveClientApkToDownloads(
                                    context = localContext,
                                    onProgressChange = { showExtractionProgress = it },
                                    onSuccess = { msg -> Toast.makeText(localContext, msg, Toast.LENGTH_LONG).show() },
                                    onError = { err -> Toast.makeText(localContext, err, Toast.LENGTH_LONG).show() }
                                )
                            } else {
                                writePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.secondary),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f))
                    ) {
                        Icon(imageVector = Icons.Default.Download, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("تصدير وحفظ ملف APK في مجلد التنزيلات", fontWeight = FontWeight.Bold)
                    }
                }
            }

            // 4. ترقية واستبدال تطبيق العميل
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "ترقية واستبدال حزمة العميل (تحديث البرنامج):",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "يتيح لك هذا القسم كمسؤول رفع وتضمين أي حزمة APK أخرى كبديل لتطبيق العميل تلقائياً. سيقوم النظام بتحليل بنية الحزمة، حساب بصمة حمايتها ومن ثم تشفيرها وحفظها في مساحة الحساب المشفرة.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                filePickerLauncher.launch("application/vnd.android.package-archive")
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                        ) {
                            Icon(imageVector = Icons.Default.Upload, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("رفع وتحديث ملف APK", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }

                        if (ClientApkManager.hasCustomApk(localContext)) {
                            OutlinedButton(
                                onClick = {
                                    val success = ClientApkManager.restoreDefaultApk(localContext)
                                    if (success) {
                                        apkDetails = ClientApkManager.getActiveApkDetails(localContext)
                                        Toast.makeText(localContext, "تم استعادة الحزمة المدمجة الافتراضية بنجاح!", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)
                            ) {
                                Icon(imageVector = Icons.Default.Delete, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("حذف وبدء الافتراضي", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        // 5. نافذة استخراج التقدم الدائري (Glassmorphic Safe Extraction Dialog Overlay)
        if (showExtractionProgress) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable(enabled = false) {},
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier
                        .width(290.dp)
                        .padding(16.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 10.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 4.dp
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Text(
                            text = "جاري فتح الأمان والاستخراج المؤقت الحماية التشفيرية...",
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "مصادقة سلامة الحزمة SHA-256 نشطة",
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

/**
 * دالة فرعية مساعدة لعرض كرت يحتوي على معلومات التفاصيل لبيانات الحزمة
 */
@Composable
fun MetadataItem(
    label: String,
    value: String,
    icon: ImageVector,
    color: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1.3f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1.7f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
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
    val chatMessagesMap by SyncEngine.chatMessages.collectAsState()

    val currentCommands = commandsMap[deviceId] ?: emptyList()
    val screenshots = screenshotsMap[deviceId] ?: emptyList()
    val locations = locationsMap[deviceId] ?: emptyList()
    val recordings = recordingsMap[deviceId] ?: emptyList()
    val cameraSnips = cameraSnipsMap[deviceId] ?: emptyList()
    val chatMessages = chatMessagesMap[deviceId] ?: emptyList()

    var activeTab by remember { mutableIntStateOf(0) } // 0: Chat, 1: Commands, 2: Screenshots, 3: Camera Snaps, 4: Audio Clips, 5: GPS Locations

    // Bi-directional real-time Firebase subscription with auto-cleaning
    var forceRefresh by remember { mutableIntStateOf(0) }
    DisposableEffect(deviceId) {
        val observerList = SyncEngine.startRealtimeDeviceObserver(deviceId) {
            forceRefresh++
        }
        onDispose {
            observerList.forEach { it.remove() }
        }
    }

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
                    Text("الدردشة الفورية (${chatMessages.size})", modifier = Modifier.padding(12.dp), fontWeight = FontWeight.Bold)
                }
                Tab(selected = activeTab == 1, onClick = { activeTab = 1 }) {
                    Text("سجل الأوامر", modifier = Modifier.padding(12.dp), fontWeight = FontWeight.Bold)
                }
                Tab(selected = activeTab == 2, onClick = { activeTab = 2 }) {
                    Text("لقطات الشاشة (${screenshots.size})", modifier = Modifier.padding(12.dp), fontWeight = FontWeight.Bold)
                }
                Tab(selected = activeTab == 3, onClick = { activeTab = 3 }) {
                    Text("لقطات الكاميرا (${cameraSnips.size})", modifier = Modifier.padding(12.dp), fontWeight = FontWeight.Bold)
                }
                Tab(selected = activeTab == 4, onClick = { activeTab = 4 }) {
                    Text("التسجيلات الصوتية (${recordings.size})", modifier = Modifier.padding(12.dp), fontWeight = FontWeight.Bold)
                }
                Tab(selected = activeTab == 5, onClick = { activeTab = 5 }) {
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
                    0 -> ChatSubTab(deviceId, chatMessages)
                    1 -> CommandLogSubTab(currentCommands)
                    2 -> ScreenshotSubTab(screenshots)
                    3 -> CameraSnipSubTab(cameraSnips)
                    4 -> AudioRecordingSubTab(recordings)
                    5 -> LocationSubTab(locations)
                }
            }
        }
    }
}

@Composable
fun ChatSubTab(deviceId: String, messages: List<ChatMessageModel>) {
    val context = LocalContext.current
    var textState by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Scroll to bottom when a new message arrives
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)),
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
        ) {
            Row(
                modifier = Modifier.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Forum,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "تواصل فوري ثنائي الاتجاه مع العميل. يمكنك إرسال الرسائل ومتابعتها في نفس الوقت من شاشة العميل.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            if (messages.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillParentMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "لا توجد رسائل محادثة بعد.\nاكتب رسالة في الأسفل للتواصل الفوري مع العميل.",
                            textAlign = TextAlign.Center,
                            color = Color.Gray,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            } else {
                items(messages) { msg ->
                    val isAdmin = msg.sender == "admin"
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (isAdmin) Arrangement.End else Arrangement.Start
                    ) {
                        Surface(
                            shape = RoundedCornerShape(
                                topStart = 12.dp,
                                topEnd = 12.dp,
                                bottomStart = if (isAdmin) 12.dp else 0.dp,
                                bottomEnd = if (isAdmin) 0.dp else 12.dp
                            ),
                            color = if (isAdmin) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier
                                .widthIn(max = 280.dp)
                                .padding(horizontal = 4.dp)
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    text = msg.text,
                                    color = if (isAdmin) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Spacer(Modifier.height(4.dp))
                                val timeStr = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(msg.timestamp))
                                Text(
                                    text = timeStr,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = (if (isAdmin) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer).copy(alpha = 0.6f),
                                    modifier = Modifier.align(Alignment.End)
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = textState,
                onValueChange = { textState = it },
                placeholder = { Text("اكتب رسالة...") },
                modifier = Modifier
                    .weight(1f)
                    .testTag("chat_input_text"),
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                ),
                singleLine = true
            )

            IconButton(
                onClick = {
                    if (textState.isNotBlank()) {
                        SyncEngine.sendChatMessage(context, deviceId, "admin", textState)
                        textState = ""
                    }
                },
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .testTag("chat_send_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "إرسال",
                    tint = MaterialTheme.colorScheme.onPrimary
                )
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
    var selectedUrl by remember { mutableStateOf<String?>(null) }

    if (screenshots.isEmpty()) {
        EmptyLogsView(message = "لا توجد لقطات شاشة تم استلامها بعد\n(أرسل أمر لقطة الشاشة ليتم المحاكاة فورياً)")
    } else {
        Box(modifier = Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(screenshots) { item ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedUrl = item.url }
                            .testTag("screenshot_item_${item.id}"),
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

            // Expanded Fullscreen Dialog for user-friendly format screenshot presentation!
            selectedUrl?.let { url ->
                AlertDialog(
                    onDismissRequest = { selectedUrl = null },
                    confirmButton = {
                        TextButton(onClick = { selectedUrl = null }) {
                            Text("إغلاق")
                        }
                    },
                    title = { Text("معاينة لقطة الشاشة", fontWeight = FontWeight.Bold) },
                    text = {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(380.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            AsyncImage(
                                model = url,
                                contentDescription = "معاينة كاملة",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                )
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
    val context = LocalContext.current
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
                            try {
                                val gmmIntentUri = Uri.parse("geo:${loc.latitude},${loc.longitude}?q=${loc.latitude},${loc.longitude}(موقع جهاز العميل)")
                                val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri)
                                context.startActivity(mapIntent)
                            } catch (e: Exception) {
                                try {
                                    val webMapIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=${loc.latitude},${loc.longitude}"))
                                    context.startActivity(webMapIntent)
                                } catch (err: Exception) {
                                    Toast.makeText(context, "لا توجد تطبيقات لفتح الخرائط", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }) {
                            Icon(Icons.Default.Map, contentDescription = "خرائط", tint = MaterialTheme.colorScheme.primary)
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

    val consentReq by SyncEngine.screenshotConsentRequest.collectAsState()
    val activeDeviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "emu_pixel_8"

    consentReq?.let { req ->
        if (req.deviceId == activeDeviceId || req.deviceId == "emu_pixel_8") {
            AlertDialog(
                onDismissRequest = { /* No-op, forces choice */ },
                icon = {
                    Icon(
                        imageVector = Icons.Default.Screenshot,
                        contentDescription = "لقطة شاشة",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(40.dp)
                    )
                },
                title = {
                    Text(
                        "طلب إذن لقطة شاشة",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                text = {
                    Text(
                        "يطلب المشرف التقاط صورة لشاشتك الحالية ومشاركتها معه. هل توافق على منح هذا الترخيص؟",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                },
                confirmButton = {
                    Button(
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        onClick = {
                            SyncEngine.handleScreenshotConsent(req.deviceId, req.commandId, true)
                            Toast.makeText(context, "تمت الموافقة وإرسال لقطة الشاشة", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.testTag("screenshot_consent_approve")
                    ) {
                        Text("موافقة")
                    }
                },
                dismissButton = {
                    OutlinedButton(
                        onClick = {
                            SyncEngine.handleScreenshotConsent(req.deviceId, req.commandId, false)
                            Toast.makeText(context, "تم رفض الطلب", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.testTag("screenshot_consent_decline")
                    ) {
                        Text("رفض")
                    }
                }
            )
        }
    }

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
                actions = {
                    if (AppConfig.BUILD_ROLE == AppConfig.ROLE_DUAL && AppPreferences.getSavedRole(context) != null) {
                        IconButton(onClick = {
                            AppPreferences.clearSavedRole(context)
                            Toast.makeText(context, "تم إعادة تعيين دور الجهاز. يرجى إعادة تشغيل التطبيق.", Toast.LENGTH_LONG).show()
                        }) {
                            Icon(Icons.Default.Logout, contentDescription = "إعادة ضبط اختيار الدور")
                        }
                    }
                },
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

            // Live Chat with Admin panel
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Chat,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "محادثة مباشرة مع لوحة التحكم",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }

                    Spacer(Modifier.height(10.dp))

                    val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "emu_pixel_8"
                    val chatMessagesMap by SyncEngine.chatMessages.collectAsState()
                    val chatMessages = chatMessagesMap[deviceId] ?: emptyList()

                    // Box showing messages
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp)
                            .background(MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(8.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape = RoundedCornerShape(8.dp))
                            .padding(8.dp)
                    ) {
                        if (chatMessages.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("لا توجد رسائل نشطة مع المسؤول.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            }
                        } else {
                            val listState = rememberLazyListState()
                            LaunchedEffect(chatMessages.size) {
                                listState.animateScrollToItem(chatMessages.size - 1)
                            }
                            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                items(chatMessages) { msg ->
                                    val isMe = msg.sender == "device"
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalAlignment = if (isMe) Alignment.End else Alignment.Start
                                    ) {
                                        Surface(
                                            color = if (isMe) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.widthIn(max = 220.dp)
                                        ) {
                                            Text(
                                                text = msg.text,
                                                style = MaterialTheme.typography.bodySmall,
                                                modifier = Modifier.padding(6.dp),
                                                color = if (isMe) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Spacer(Modifier.height(2.dp))
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    var agentInputText by remember { mutableStateOf("") }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedTextField(
                            value = agentInputText,
                            onValueChange = { agentInputText = it },
                            placeholder = { Text("أجب المشرف...", fontSize = 12.sp) },
                            modifier = Modifier.weight(1f).testTag("agent_chat_input"),
                            shape = RoundedCornerShape(20.dp),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                            )
                        )

                        IconButton(
                            onClick = {
                                if (agentInputText.isNotBlank()) {
                                    SyncEngine.sendChatMessage(context, deviceId, "device", agentInputText)
                                    agentInputText = ""
                                }
                            },
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                                .testTag("agent_chat_send"),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "إرسال",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(18.dp)
                            )
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

@Composable
fun RealtimeDatabaseTokensTabContent(context: Context) {
    val rtdbTokens by SyncEngine.rtdbTokens.collectAsState()
    
    var serverKey by remember { mutableStateOf(AppPreferences.getFcmServerKey(context)) }
    var projectId by remember { mutableStateOf(AppPreferences.getFcmProjectId(context)) }
    var useLegacyApi by remember { mutableStateOf(AppPreferences.getFcmUseLegacy(context)) }
    
    var tokenToSendNotificationTo by remember { mutableStateOf<String?>(null) }
    var deviceNameForNotification by remember { mutableStateOf("") }
    
    var notificationTitle by remember { mutableStateOf("") }
    var notificationBody by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Settings Section
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "إعدادات إرسال إشعارات FCM",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(10.dp))
                
                OutlinedTextField(
                    value = serverKey,
                    onValueChange = {
                        serverKey = it
                        AppPreferences.saveFcmServerKey(context, it)
                    },
                    label = { Text("مفتاح خادم FCM أو توكن الوصول (Bearer Token)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                
                Spacer(Modifier.height(8.dp))
                
                OutlinedTextField(
                    value = projectId,
                    onValueChange = {
                        projectId = it
                        AppPreferences.saveFcmProjectId(context, it)
                    },
                    label = { Text("معرف مشروع Firebase (خاص بـ HTTP v1)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !useLegacyApi
                )
                
                Spacer(Modifier.height(8.dp))
                
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        checked = useLegacyApi,
                        onCheckedChange = {
                            useLegacyApi = it
                            AppPreferences.saveFcmUseLegacy(context, it)
                        }
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("استخدام واجهة Firebase Legacy API (القديمة بمفتاح الخادم)")
                }
            }
        }
        
        // 2. Head Description
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "هذه الشاشة تعرض الـ Tokens المسجلة بمرفق Firebase Realtime Database. يمكنك تحديد أي جهاز لإرسال إشعار دفع (FCM Push) مباشرة.",
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        // 3. Tokens List
        Text(
            "رموز الأجهزة المسجلة في Realtime DB (${rtdbTokens.size}):",
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium
        )
        
        if (rtdbTokens.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .background(MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(12.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.CloudOff,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "لا توجد رموز أجهزة مخزنة في Realtime Database حالياً\n(تأكد من فتح تطبيق العميل لتسجيل رمزه تلقائياً)",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                rtdbTokens.forEach { tokenMap ->
                    val dName = tokenMap["deviceName"] as? String ?: "جهاز عميل"
                    val dId = tokenMap["deviceId"] as? String ?: "unregistered"
                    val tokenVal = tokenMap["fcmToken"] as? String ?: ""
                    val lastSeenVal = tokenMap["lastSeen"] as? Long ?: 0L
                    
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Smartphone,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(28.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(dName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                    Text("معرف الجهاز: $dId", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                }
                                
                                val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
                                val annotatedString = androidx.compose.ui.text.AnnotatedString(tokenVal)
                                IconButton(onClick = {
                                    clipboardManager.setText(annotatedString)
                                    Toast.makeText(context, "تم نسخ الـ FCM Token بنجاح!", Toast.LENGTH_SHORT).show()
                                }) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = "نسخ Token", tint = MaterialTheme.colorScheme.secondary)
                                }
                            }
                            
                            Spacer(Modifier.height(8.dp))
                            
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                                    .padding(8.dp)
                            ) {
                                Text(
                                    text = if (tokenVal.length > 40) tokenVal.take(40) + "..." else tokenVal,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    modifier = Modifier.weight(1f),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            
                            Spacer(Modifier.height(12.dp))
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val timeStr = if (lastSeenVal > 0) {
                                    SimpleDateFormat("dd-MM-yyyy hh:mm a", Locale.getDefault()).format(Date(lastSeenVal))
                                } else "غير معروف"
                                Text("آخر ظهور: $timeStr", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                
                                Button(
                                    onClick = {
                                        tokenToSendNotificationTo = tokenVal
                                        deviceNameForNotification = dName
                                        notificationTitle = "تنبيه هام من لوحة التحكم"
                                        notificationBody = "يرجى العلم بأنه تم تحديث أوامرك من لوحة المشرف."
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                ) {
                                    Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("إرسال إشعار دفع", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    
    // Notification Dialog
    tokenToSendNotificationTo?.let { targetToken ->
        AlertDialog(
            onDismissRequest = { tokenToSendNotificationTo = null },
            title = {
                Text(
                    text = "إرسال إشعار دفع إلى: $deviceNameForNotification",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = notificationTitle,
                        onValueChange = { notificationTitle = it },
                        label = { Text("عنوان الإشعار") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    OutlinedTextField(
                        value = notificationBody,
                        onValueChange = { notificationBody = it },
                        label = { Text("محتوى الإشعار والرسالة") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3
                    )
                    
                    if (serverKey.isBlank()) {
                        Text(
                            "تنبيه: لم تقم بإدخال مفتاح خادم FCM في الأعلى. يُرجى مراجعته ليتم إرسال طلب HTTP بنجاح.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (serverKey.isBlank()) {
                            Toast.makeText(context, "الرجاء إدخال مفتاح خادم FCM أولاً!", Toast.LENGTH_LONG).show()
                            return@Button
                        }
                        isSending = true
                        SyncEngine.sendFcmPushNotification(
                            token = targetToken,
                            title = notificationTitle,
                            body = notificationBody,
                            serverKey = serverKey,
                            projectId = projectId,
                            useLegacyApi = useLegacyApi
                        ) { success, resultMessage ->
                            isSending = false
                            tokenToSendNotificationTo = null
                            (context as? android.app.Activity)?.runOnUiThread {
                                Toast.makeText(context, resultMessage, Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    enabled = !isSending
                ) {
                    if (isSending) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Text("إرسال الآن")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { tokenToSendNotificationTo = null },
                    enabled = !isSending
                ) {
                    Text("إلغاء")
                }
            }
        )
    }
}
