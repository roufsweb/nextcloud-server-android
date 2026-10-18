package com.nextcloud.server

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d(TAG, "Received broadcast action: $action")

        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON") {
            
            val prefs = context.getSharedPreferences("NextcloudPrefs", Context.MODE_PRIVATE)
            val startOnBoot = prefs.getBoolean("start_on_boot", false)
            Log.d(TAG, "Device boot completed. start_on_boot setting: $startOnBoot")
            
            if (startOnBoot) {
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
}
