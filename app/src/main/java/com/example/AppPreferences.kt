package com.example

import android.content.Context
import android.content.SharedPreferences

/**
 * محرك حفظ التفضيلات والخيارات الدائمة لتثبيت دور الجهاز كـ (مشرف دائم) أو (عميل دائم)
 * لضمان تحويل التطبيق المدمج إلى تطبيقين منفصلين تماماً بمجرد التثبيت والاختيار المبدئي.
 */
object AppPreferences {
    private const val PREFS_NAME = "remote_control_prefs"
    private const val KEY_SELECTED_ROLE = "selected_role" // "admin" or "user" or "dual" or null

    private fun getSharedPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * جلب الدور المثبت والمسجل حالياً للجهاز.
     */
    fun getSavedRole(context: Context): String? {
        // إذا كان البناء الأساسي للمشروع تم تصديره ليكون مشرف فقط أو عميل فقط، نلتزم تماماً بخيار البناء البرمجي
        if (AppConfig.BUILD_ROLE == AppConfig.ROLE_ADMIN) return "admin"
        if (AppConfig.BUILD_ROLE == AppConfig.ROLE_USER) return "user"
        
        // غير ذلك، نجلب الدور الذي تم اختياره وتثبيته محلياً بواسطة المستخدم من شاشة البداية
        return getSharedPrefs(context).getString(KEY_SELECTED_ROLE, null)
    }

    /**
     * حفظ وتثبيت خيار استخدام دور الجهاز بشكل دائم.
     */
    fun saveSavedRole(context: Context, role: String?) {
        getSharedPrefs(context).edit().putString(KEY_SELECTED_ROLE, role).apply()
    }

    /**
     * إعادة تعيين واستعادة شاشة إعداد واختيار الدور المزدوج (مثال: بالضغط المطول 5 مرات على شعار التطبيق)
     */
    fun clearSavedRole(context: Context) {
        getSharedPrefs(context).edit().remove(KEY_SELECTED_ROLE).apply()
    }
}
