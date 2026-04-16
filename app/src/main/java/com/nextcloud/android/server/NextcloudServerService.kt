package com.nextcloud.android.server

import android.app.*
import android.content.Intent
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.*
import java.util.concurrent.Executors

class NextcloudServerService : Service() {

    private val CHANNEL_ID = "NextcloudServerChannel"
    private var phpProcess: Process? = null
    private var nginxProcess: Process? = null
    private val executor = Executors.newFixedThreadPool(2)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == "STOP") {
            stopServer()
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(1, createNotification("Starting server..."))
        
        executor.execute {
            try {
                prepareBinaries()
                startPhpFpm()
                startNginx()
                updateNotification("Nextcloud Server is running at http://localhost:8080")
            } catch (e: Exception) {
                Log.e("NextcloudServer", "Error starting server", e)
                updateNotification("Error starting server: ${e.message}")
            }
        }

        return START_STICKY
    }

    private fun prepareBinaries() {
        val filesDir = filesDir.absolutePath
        val binDir = File(filesDir, "bin")
        val confDir = File(filesDir, "conf")
        val logsDir = File(filesDir, "logs")
        val runDir = File(filesDir, "run")
        val nextcloudDir = File(filesDir, "nextcloud")

        if (!binDir.exists()) binDir.mkdirs()
        if (!confDir.exists()) confDir.mkdirs()
        if (!logsDir.exists()) logsDir.mkdirs()
        if (!runDir.exists()) runDir.mkdirs()

        // Copy binaries from assets
        AssetHelper.copyAssetFolder(this, "bin", binDir.absolutePath)
        AssetHelper.makeExecutable(File(binDir, "php").absolutePath)
        AssetHelper.makeExecutable(File(binDir, "nginx").absolutePath)

        // Copy and process configs
        processConfig("nginx.conf.template", File(confDir, "nginx.conf"))
        processConfig("php-fpm.conf.template", File(confDir, "php-fpm.conf"))
        processConfig("php.ini.template", File(confDir, "php.ini"))
        AssetHelper.copyAssetFolder(this, "conf", confDir.absolutePath) // For mime.types

        // Extract Nextcloud if not present
        if (!nextcloudDir.exists()) {
            updateNotification("Extracting Nextcloud core...")
            AssetHelper.unzipAsset(this, "nextcloud.zip", filesDir)
        }
    }

    private fun processConfig(assetName: String, targetFile: File) {
        val template = assets.open(assetName).bufferedReader().readText()
        val processed = template.replace("{{FILES_DIR}}", filesDir.absolutePath)
            .replace("{{USER}}", "shell") // Default for Android
            .replace("{{GROUP}}", "shell")
        targetFile.writeText(processed)
    }

    private fun startPhpFpm() {
        val phpBin = File(filesDir, "bin/php-fpm").absolutePath
        val phpConf = File(filesDir, "conf/php-fpm.conf").absolutePath
        val pb = ProcessBuilder(phpBin, "-y", phpConf, "-F")
        pb.directory(filesDir)
        pb.redirectErrorStream(true)
        phpProcess = pb.start()
        
        executor.execute {
            phpProcess?.inputStream?.bufferedReader()?.forEachLine { line ->
                Log.d("PHP-FPM", line)
            }
        }
    }

    private fun startNginx() {
        val nginxBin = File(filesDir, "bin/nginx").absolutePath
        val nginxConf = File(filesDir, "conf/nginx.conf").absolutePath
        val pb = ProcessBuilder(nginxBin, "-c", nginxConf, "-g", "daemon off;")
        pb.directory(filesDir)
        pb.redirectErrorStream(true)
        nginxProcess = pb.start()

        executor.execute {
            nginxProcess?.inputStream?.bufferedReader()?.forEachLine { line ->
                Log.d("Nginx", line)
            }
        }
    }

    private fun stopServer() {
        phpProcess?.destroy()
        nginxProcess?.destroy()
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Nextcloud Server Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(content: String): Notification {
        val stopIntent = Intent(this, NextcloudServerService::class.java).apply {
            action = "STOP"
        }
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Nextcloud Server")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .build()
    }

    private fun updateNotification(content: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(1, createNotification(content))
    }
}
