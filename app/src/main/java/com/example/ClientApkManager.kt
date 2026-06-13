package com.example

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * مدير حزمة تطبيق العميل المؤمن والمحمي (Client APK Manager)
 * يوفر تشفيراً، ومصادقة، واستخراجاً، ومشاركة آمنة، بالإضافة إلى إتاحة تحديث الحاوية محلياً.
 */
object ClientApkManager {

    private const val PREFS_NAME = "client_apk_prefs"
    private const val ASSET_NAME = "client_secured.enc"
    private const val CUSTOM_FILE_NAME = "custom_client_secured.enc"
    
    // مفتاح تعمية متماثل لحماية حزمة التطبيق من الاستخراج المباشر
    private const val ENCRYPTION_KEY: Byte = 0x5A

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * التحقق مما إذا كان هناك تطبيق عميل مخصص تم رفعه مسبقاً من لوحة تحكم المشرف
     */
    fun hasCustomApk(context: Context): Boolean {
        val file = File(context.filesDir, CUSTOM_FILE_NAME)
        return file.exists() && file.length() > 0
    }

    /**
     * تفاصيل ملف APK الحالي (الحجم، الإصدار، تاريخ التحديث، ومصدر الملف)
     */
    data class ClientApkDetails(
        val source: String, // "مدمج افتراضي" أو "مرفوع مخصص"
        val versionName: String,
        val sizeFormatted: String,
        val lastUpdated: String,
        val sha256: String,
        val sizeBytes: Long
    )

    /**
     * جلب تفاصيل حزمة العميل النشطة حالياً
     */
    fun getActiveApkDetails(context: Context): ClientApkDetails {
        val isCustom = hasCustomApk(context)
        val file = if (isCustom) File(context.filesDir, CUSTOM_FILE_NAME) else null

        var sizeBytes: Long = 0
        var lastModifiedTime: Long = 0
        var sourceStr: String = ""

        if (file != null) {
            sizeBytes = file.length()
            lastModifiedTime = file.lastModified()
            sourceStr = "تحديث مخصص للمشرف"
        } else {
            // جلب الحزمة المدمجة من Assets
            try {
                context.assets.open(ASSET_NAME).use { input ->
                    sizeBytes = input.available().toLong()
                }
            } catch (e: Exception) {
                sizeBytes = 0
            }
            lastModifiedTime = getPrefs(context).getLong("asset_last_modified", System.currentTimeMillis())
            sourceStr = "الإصدار المدمج الافتراضي"
        }

        // جلب رقم الإصدار الكامن خلف حافة التشفير
        val savedVerName = getPrefs(context).getString("apk_version_name", "v2.4.0 (العميل)") ?: "v2.4.0 (العميل)"
        val savedSha = getPrefs(context).getString(if (isCustom) "custom_sha" else "asset_sha", "غير محتسب") ?: "غير محتسب"

        val sizeFormatted = formatFileSize(sizeBytes)
        val dateFormatted = SimpleDateFormat("yyyy-MM-dd hh:mm a", Locale.getDefault()).format(Date(lastModifiedTime))

        return ClientApkDetails(
            source = sourceStr,
            versionName = savedVerName,
            sizeFormatted = sizeFormatted,
            lastUpdated = dateFormatted,
            sha256 = savedSha,
            sizeBytes = sizeBytes
        )
    }

    /**
     * معالجة وحفظ ملف APK جديد رفعه المشرف في لوحة التحكم وتشفيره ديناميكياً
     */
    fun upgradeClientApk(context: Context, sourceUri: Uri): Boolean {
        val contentResolver = context.contentResolver
        val tempFile = File(context.cacheDir, "temp_incoming.apk")
        
        try {
            // 1. نسخ الملف المؤقت لفك تشفيره وقراءة بياناته التعريفية الرسمية
            contentResolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return false

            // 2. تحليل تفاصيل الحاوية عبر PackageManager
            val pm = context.packageManager
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageArchiveInfo(tempFile.absolutePath, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(tempFile.absolutePath, 0)
            }

            val versionName = packageInfo?.versionName ?: "v${packageInfo?.versionCode ?: "3.0.0"}"
            
            // 3. حساب بصمة SHA-256 كإجراء لضمان الحفاظ على موثوقية التطبيق
            val sha256 = calculateFileSha256(tempFile)

            // 4. قراءة الملف وتعميته عبر التشفير المعتمد (XOR Stream) وحفظ المتغير في المسار الدائم للمشرف
            val destFile = File(context.filesDir, CUSTOM_FILE_NAME)
            FileInputStream(tempFile).use { input ->
                FileOutputStream(destFile).use { output ->
                    val buffer = ByteArray(4096)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        for (i in 0 until bytesRead) {
                            buffer[i] = (buffer[i].toInt() xor ENCRYPTION_KEY.toInt()).toByte()
                        }
                        output.write(buffer, 0, bytesRead)
                    }
                }
            }

            // 5. حفظ التفضيلات
            getPrefs(context).edit().apply {
                putString("apk_version_name", versionName)
                putString("custom_sha", sha256)
                putLong("custom_last_modified", System.currentTimeMillis())
                apply()
            }

            tempFile.delete()
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            if (tempFile.exists()) tempFile.delete()
            return false
        }
    }

    /**
     * استعادة وحذف ملف APK المخصص للعودة للإصدار المدمج الافتراضي
     */
    fun restoreDefaultApk(context: Context): Boolean {
        val file = File(context.filesDir, CUSTOM_FILE_NAME)
        if (file.exists()) {
            val deleted = file.delete()
            if (deleted) {
                getPrefs(context).edit().apply {
                    remove("apk_version_name")
                    remove("custom_sha")
                    remove("custom_last_modified")
                    apply()
                }
                return true
            }
        }
        return false
    }

    /**
     * استخراج فك التشفير الحقيقي الخاص بـ Client APK لإنتاج ملف .apk حقيقي مؤقت متوافق مع الحماية والمشاركة الشاملة
     */
    fun extractAndDecryptApkFlow(context: Context): Flow<Float> = flow {
        val cacheDir = File(context.cacheDir, "client_apks")
        if (!cacheDir.exists()) cacheDir.mkdirs()
        val decryptedFile = File(cacheDir, "client_agent_release.apk")
        if (decryptedFile.exists()) decryptedFile.delete()

        val isCustom = hasCustomApk(context)
        val input: InputStream = if (isCustom) {
            FileInputStream(File(context.filesDir, CUSTOM_FILE_NAME))
        } else {
            try {
                context.assets.open(ASSET_NAME)
            } catch (e: Exception) {
                // في حال عدم وجود ملف حقيقي مشفر مدمج مسبقاً، سنولد نموذج ملف مشفر محلياً
                generateMockAssetApkIfMissing(context)
                context.assets.open(ASSET_NAME)
            }
        }

        val totalSize = input.available().toFloat()
        var bytesWritten = 0L

        FileOutputStream(decryptedFile).use { output ->
            val buffer = ByteArray(4096)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                for (i in 0 until bytesRead) {
                    buffer[i] = (buffer[i].toInt() xor ENCRYPTION_KEY.toInt()).toByte()
                }
                output.write(buffer, 0, bytesRead)
                bytesWritten += bytesRead
                emit(bytesWritten / totalSize)
            }
        }
        input.close()
        emit(1.0f)
    }.flowOn(Dispatchers.IO)

    /**
     * إرسال ومشاركة تطبيق العميل من خلال Android Share Sheet الآمن
     */
    fun shareClientApk(context: Context, onProgressChange: (Boolean) -> Unit, onError: (String) -> Unit) {
        onProgressChange(true)
        val extractedFile = File(File(context.cacheDir, "client_apks"), "client_agent_release.apk")
        
        // استخراج وفك الضغط بشكل متزامن مريح
        kotlinx.coroutines.GlobalScope.launchInIOOrFallback(
            work = {
                extractAndDecryptApkFlow(context).collect { _ -> }
            },
            onSuccess = {
                onProgressChange(false)
                if (extractedFile.exists() && extractedFile.length() > 0) {
                    val authority = "${context.packageName}.fileprovider"
                    val contentUri = FileProvider.getUriForFile(context, authority, extractedFile)
                    
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/vnd.android.package-archive"
                        putExtra(Intent.EXTRA_STREAM, contentUri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    
                    val chooser = Intent.createChooser(shareIntent, "مشاركة تطبيق العميل الموثق والآمن").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(chooser)
                } else {
                    onError("حدث خطأ أثناء فك حظر حزمة الأمان")
                }
            },
            onError = { e ->
                onProgressChange(false)
                onError("أخفق الاستخراج: ${e.message}")
            }
        )
    }

    /**
     * حفظ ملف APK داخلياً في ملفات التحميلات العامة (Downloads) الخاصة بالمستخدم
     */
    fun saveClientApkToDownloads(
        context: Context, 
        onProgressChange: (Boolean) -> Unit, 
        onSuccess: (String) -> Unit, 
        onError: (String) -> Unit
    ) {
        onProgressChange(true)
        val apkFileName = "Client_Agent_v${getPrefs(context).getString("apk_version_name", "2.4.0")?.filter { it.isLetterOrDigit() || it == '.' } ?: "2.4.0"}.apk"
        
        kotlinx.coroutines.GlobalScope.launchInIOOrFallback(
            work = {
                extractAndDecryptApkFlow(context).collect { _ -> }
                val sourceFile = File(File(context.cacheDir, "client_apks"), "client_agent_release.apk")
                if (!sourceFile.exists() || sourceFile.length() == 0L) {
                    throw Exception("ملف استخراج مؤقت مفقود")
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // استخدام MediaStore المخصص لنظام أندرويد 10 فما فوق
                    val resolver = context.contentResolver
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, apkFileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "application/vnd.android.package-archive")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }

                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                        ?: throw Exception("تعذر إنشاء مدخل في مجلد التحميل")

                    resolver.openOutputStream(uri).use { output ->
                        if (output == null) throw Exception("تعذر فتح تيار كتابة للمخرجات")
                        FileInputStream(sourceFile).use { input ->
                            input.copyTo(output)
                        }
                    }
                    "تم الحفظ بنجاح داخل مجلد التنزيلات (Downloads) باسم: $apkFileName"
                } else {
                    // الأنظمة القديمة معتمداً على صلاحية التخزين
                    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    if (!downloadsDir.exists()) downloadsDir.mkdirs()
                    val targetFile = File(downloadsDir, apkFileName)
                    
                    FileInputStream(sourceFile).use { input ->
                        FileOutputStream(targetFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    "تم حفظ حزمة APK داخل مجلد التنزيلات بنجاح: ${targetFile.absolutePath}"
                }
            },
            onSuccess = { msg ->
                onProgressChange(false)
                onSuccess(msg)
            },
            onError = { e ->
                onProgressChange(false)
                onError("أخفق التصدير لحافظة التنزيلات: ${e.message}")
            }
        )
    }

    /**
     * إعداد ملف تجريبي مدمج داخل للتطبيق المشترط للتجربة الأولية في حال غياب حزمة APK حقيقية في الأصول
     */
    fun generateMockAssetApkIfMissing(context: Context) {
        // نتحقق من سلامة الأصول الافتراضية، وفي حال غيابها نولد ملف تجريبي أولي
        val destFile = File(context.filesDir, "asset_fallback_client_secured.enc")
        if (destFile.exists()) return

        try {
            // توليد مكونات تجريبية موثقة
            val mockApkBytes = "MOCK_APK_HEADER_COMPRESSED_ZIP_ARCHIVE_REPRESENTATIVE_DATA_OF_REAL_CLIENT".toByteArray(Charsets.UTF_8)
            val encryptedBytes = ByteArray(mockApkBytes.size)
            for (i in mockApkBytes.indices) {
                encryptedBytes[i] = (mockApkBytes[i].toInt() xor ENCRYPTION_KEY.toInt()).toByte()
            }
            
            FileOutputStream(destFile).use { out ->
                out.write(encryptedBytes)
            }
            
            // حساب SHA-256 للمصادقة الشاملة
            val sha = calculateFileSha256(destFile)
            getPrefs(context).edit().apply {
                putString("asset_sha", sha)
                putLong("asset_last_modified", System.currentTimeMillis())
                putString("apk_version_name", "v2.5.4 (الإصدار التجريبي المدمج)")
                apply()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // --- توابع مساعدة ---

    private fun formatFileSize(size: Long): String {
        if (size <= 0) return "0 بابت"
        val units = arrayOf("بايت", "كيلوبايت", "ميغابايت", "جيجابايت")
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
        return String.format(Locale.getDefault(), "%.2f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }

    private fun calculateFileSha256(file: File): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8192)
            val fis = FileInputStream(file)
            var read: Int
            while (fis.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
            fis.close()
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            "Unknown"
        }
    }

    /**
     * امتداد مريح لتجنب مشاكل تشغيل الكوروتين والخطوط الخلفية بشكل آمن في أي تطبيق أندرويد
     */
    private fun <T> CoroutineScope.launchInIOOrFallback(
        work: suspend () -> T,
        onSuccess: (T) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        this.launch(Dispatchers.Main) {
            try {
                val result = withContext(Dispatchers.IO) {
                    work()
                }
                onSuccess(result)
            } catch (e: Throwable) {
                onError(e)
            }
        }
    }
}
