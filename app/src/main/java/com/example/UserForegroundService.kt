package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

class UserForegroundService : Service() {
    private val TAG = "UserService"
    private val scope = CoroutineScope(Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private var mediaRecorder: MediaRecorder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        startForeground(1001, notification)

        // Listen for new inbound commands via local SyncEngine (for simulation)
        // and trigger execution responses
        scope.launch {
            val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "device_id"
            SyncEngine.commands.collectLatest { commandsMap ->
                val commandList = commandsMap[deviceId] ?: emptyList()
                val pendingCmd = commandList.firstOrNull { it.status == "pending" }
                if (pendingCmd != null) {
                    executeCommand(pendingCmd.command)
                }
            }
        }

        // Register in Firebase if configuration is alive
        SyncEngine.checkFirebase(this)
        listenToFirebaseCommands()

        return START_STICKY
    }

    private fun listenToFirebaseCommands() {
        if (!SyncEngine.isFirebaseActive) return
        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "device_id"
        try {
            val db = FirebaseFirestore.getInstance()
            db.collection("devices").document(deviceId).collection("commands")
                .addSnapshotListener { snapshot, error ->
                    if (error != null) return@addSnapshotListener
                    snapshot?.documentChanges?.forEach { change ->
                        if (change.type == com.google.firebase.firestore.DocumentChange.Type.ADDED) {
                            val command = change.document.getString("command") ?: ""
                            val status = change.document.getString("status") ?: "pending"
                            if (status == "pending") {
                                executeCommand(command)
                                change.document.reference.update("status", "executed")
                            }
                        }
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Firebase command listener initialization failed: ${e.localizedMessage}")
        }
    }

    private fun executeCommand(command: String) {
        handler.post {
            Toast.makeText(this, "تلقى العميل أمر: $command", Toast.LENGTH_SHORT).show()
        }
        when (command) {
            "لقطة شاشة" -> takeScreenshot()
            "تشغيل الكاميرا" -> captureCamera()
            "تسجيل الصوت" -> startAudioRecording()
            "قفل الشاشة" -> lockScreen()
            "عرض الموقع" -> getLocation()
        }
    }

    private fun takeScreenshot() {
        Log.i(TAG, "Taking high fidelity simulated screenshot")
        // The SyncEngine automatically generates a gorgeous preview URL and updates the state.
    }

    private fun captureCamera() {
        Log.i(TAG, "Taking high fidelity simulated snapshot")
        // The SyncEngine automatically processes a beautiful camera snapshot.
    }

    private fun startAudioRecording() {
        try {
            val file = File(cacheDir, "audio_${System.currentTimeMillis()}.3gp")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                mediaRecorder = MediaRecorder(this).apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                    setOutputFile(file.absolutePath)
                    prepare()
                    start()
                }
            } else {
                @Suppress("DEPRECATION")
                mediaRecorder = MediaRecorder().apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                    setOutputFile(file.absolutePath)
                    prepare()
                    start()
                }
            }
            handler.post {
                Toast.makeText(this, "بدأ تسجيل الصوت لمدة ٣ ثوانٍ...", Toast.LENGTH_SHORT).show()
            }
            // Record for 3 seconds instead of 30 for snappy simulator previews
            handler.postDelayed({
                stopAudioRecording()
            }, 3000)
        } catch (e: Exception) {
            Log.e(TAG, "Audio recording failed or mock initiated: ${e.localizedMessage}")
        }
    }

    private fun stopAudioRecording() {
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
            mediaRecorder = null
            handler.post {
                Toast.makeText(this, "اكتمل تسجيل الصوت وحُفظ بنجاح", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recorder: ${e.localizedMessage}")
        }
    }

    private fun lockScreen() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(this, AdminReceiver::class.java)
        if (dpm.isAdminActive(adminComponent)) {
            try {
                dpm.lockNow()
            } catch (e: Exception) {
                Log.e(TAG, "Lock failure: ${e.localizedMessage}")
            }
        } else {
            handler.post {
                Toast.makeText(this, "يرجى تنشيط صلاحية مسؤول الجهاز (Admin Role) للقفل", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun getLocation() {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            @Suppress("DEPRECATION")
            val location = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

            if (location != null) {
                Log.i(TAG, "Acquired coords: ${location.latitude}, ${location.longitude}")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission missing: ${e.localizedMessage}")
        }
    }

    override fun onDestroy() {
        scope.cancel()
        stopAudioRecording()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "remote_service_channel",
                "خدمة التحكم والمراقبة",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "قناة إشعارات الخدمة النشطة في الخلفية لتلقي تنفيذ الأوامر"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, "remote_service_channel")
            .setContentTitle("خدمة المراقبة نشطة")
            .setContentText("التطبيق جاهز لتلقي أوامر المشرف")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }
}
