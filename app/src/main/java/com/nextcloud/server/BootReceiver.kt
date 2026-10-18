package com.nextcloud.server

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.UserManager
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.i(TAG, "Received broadcast action: $action")

        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == Intent.ACTION_USER_UNLOCKED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON") {
            
            val prefs = AppPreferences(context)
            val startOnBoot = prefs.startOnBoot
            Log.d(TAG, "Start on boot setting: $startOnBoot")
            
            if (!startOnBoot) {
                Log.d(TAG, "Auto-start is disabled in preferences. Doing nothing.")
                return
            }

            // Nextcloud server files (PHP-FPM, Nginx, SQLite database) reside in credential-protected storage.
            // In Direct Boot before user enters PIN/passcode, wait for ACTION_USER_UNLOCKED / ACTION_BOOT_COMPLETED.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val userManager = context.getSystemService(Context.USER_SERVICE) as? UserManager
                val isUnlocked = userManager?.isUserUnlocked ?: true
                if (!isUnlocked) {
                    Log.i(TAG, "Device is locked in Direct Boot mode. Awaiting user unlock before launching Nextcloud server.")
                    return
                }
            }

            Log.i(TAG, "Auto-starting NextcloudServerService...")
            val serviceIntent = Intent(context, NextcloudServerService::class.java).apply {
                this.action = NextcloudServerService.ACTION_START
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
                Log.i(TAG, "Successfully started NextcloudServerService on boot")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start NextcloudServerService on boot", e)
            }
        }
    }
}
