package com.nextcloud.server

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.UserManager
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate

class AppPreferences(context: Context) {
    companion object {
        private const val TAG = "AppPreferences"
        const val PREFS_NAME = "NextcloudPrefs"
    }

    private val prefs: SharedPreferences = run {
        val storageContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (context.isDeviceProtectedStorage) {
                context
            } else {
                val deviceContext = context.createDeviceProtectedStorageContext()
                val dePrefs = deviceContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val isMigrated = dePrefs.getBoolean("__de_migrated__", false)
                if (!isMigrated) {
                    val userManager = context.getSystemService(Context.USER_SERVICE) as? UserManager
                    val isUnlocked = userManager?.isUserUnlocked ?: true
                    if (isUnlocked) {
                        try {
                            deviceContext.moveSharedPreferencesFrom(context, PREFS_NAME)
                            dePrefs.edit().putBoolean("__de_migrated__", true).apply()
                            Log.i(TAG, "Successfully migrated $PREFS_NAME to device-protected storage")
                        } catch (e: Exception) {
                            Log.w(TAG, "Exception migrating $PREFS_NAME to device-protected storage: ${e.message}")
                        }
                    }
                }
                deviceContext
            }
        } else {
            context
        }
        storageContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    val sharedPreferences: SharedPreferences
        get() = prefs

    var startOnBoot: Boolean
        get() = prefs.getBoolean("start_on_boot", false)
        set(value) = prefs.edit().putBoolean("start_on_boot", value).apply()

    var serverPort: Int
        get() = prefs.getInt("server_port", 8000)
        set(value) = prefs.edit().putInt("server_port", value).apply()

    var wakeLockEnabled: Boolean
        get() = prefs.getBoolean("wake_lock_enabled", true)
        set(value) = prefs.edit().putBoolean("wake_lock_enabled", value).apply()

    var customDataDir: String?
        get() = prefs.getString("custom_data_dir", null)
        set(value) = prefs.edit().putString("custom_data_dir", value).apply()

    var adminUser: String
        get() = prefs.getString("admin_user", "admin") ?: "admin"
        set(value) = prefs.edit().putString("admin_user", value).apply()

    var adminPass: String
        get() = prefs.getString("admin_pass", "") ?: ""
        set(value) = prefs.edit().putString("admin_pass", value).apply()

    var themeMode: Int
        get() = prefs.getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        set(value) = prefs.edit().putInt("theme_mode", value).apply()

    var isServerRunning: Boolean
        get() = prefs.getBoolean("server_running", false)
        set(value) = prefs.edit().putBoolean("server_running", value).apply()

    var isInternalStorageMountEnabled: Boolean
        get() = prefs.getBoolean("internal_storage_mount_enabled", true)
        set(value) = prefs.edit().putBoolean("internal_storage_mount_enabled", value).apply()
}
