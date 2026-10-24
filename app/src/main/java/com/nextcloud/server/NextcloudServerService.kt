package com.nextcloud.server

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.*
import java.util.concurrent.Executors

class NextcloudServerService : Service() {

    private val CHANNEL_ID = "NextcloudServerChannel"
    private var phpProcess: java.lang.Process? = null
    private var nginxProcess: java.lang.Process? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val executor = Executors.newCachedThreadPool()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val prefs = AppPreferences(this)
        if (prefs.wakeLockEnabled) {
            setWakeLockEnabled(true)
        }
    }

    companion object {
        const val ACTION_START = "com.nextcloud.server.START"
        const val ACTION_MIGRATE_STORAGE = "com.nextcloud.server.MIGRATE_STORAGE"
        const val ACTION_MIGRATION_PROGRESS = "com.nextcloud.server.MIGRATION_PROGRESS"
        const val ACTION_CHANGE_PORT = "com.nextcloud.server.CHANGE_PORT"
        const val ACTION_PORT_PROGRESS = "com.nextcloud.server.PORT_PROGRESS"
        const val ACTION_TOGGLE_WAKELOCK = "com.nextcloud.server.TOGGLE_WAKELOCK"
        const val ACTION_SAF_GRANTED = "com.nextcloud.server.SAF_GRANTED"
        const val ACTION_SETUP_PROGRESS = "com.nextcloud.server.SETUP_PROGRESS"
        @Volatile var isServerRunning = false
        @Volatile var currentRunningPort = 0
        @Volatile var lastStatus = "Stopped"
        @Volatile var isMigrationRunning = false
        @Volatile var lastMigrationStage = ""
        @Volatile var lastMigrationPercent = 0
        @Volatile var isPortChangeRunning = false
        @Volatile var lastPortChangeStatus = ""
        @Volatile var lastPortChangePercent = 0
        @Volatile var isSetupRunning = false
        @Volatile var lastSetupStage = ""
        @Volatile var lastSetupPercent = 0
        @Volatile var setupProgressListener: SetupProgressListener? = null
    }

    fun interface SetupProgressListener {
        fun onSetupProgress(isRunning: Boolean, stage: String, percent: Int, success: Boolean)
    }

    private var storageMountManager: StorageMountManager? = null
    private var safManager: SdCardSafManager? = null
    private var uploadRelayService: UploadRelayService? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == "STOP") {
            stopServer()
            stopSelf()
            return START_NOT_STICKY
        }

        if (action == ACTION_SAF_GRANTED) {
            val uuid = intent.getStringExtra("uuid")
            if (uuid != null) {
                uploadRelayService?.startWatching(listOf(uuid))
                Log.i("NextcloudServer", "SAF permission granted for $uuid; UploadRelayService watching started")
            }
            return START_STICKY
        }

        if (action == ACTION_TOGGLE_WAKELOCK) {
            val enabled = intent.getBooleanExtra("enabled", true)
            setWakeLockEnabled(enabled)
            return START_STICKY
        }

        if (action == ACTION_CHANGE_PORT) {
            val newPort = intent.getIntExtra("port", 8000)
            ensureForeground("Changing server port to $newPort...")
            executor.execute {
                performPortChange(newPort)
            }
            return START_STICKY
        }

        if (action == ACTION_MIGRATE_STORAGE) {
            val oldPath = intent.getStringExtra("old_path") ?: ""
            val newPath = intent.getStringExtra("new_path") ?: ""
            val copyData = intent.getBooleanExtra("copy_data", true)

            ensureForeground("Starting storage setup...")
            executor.execute {
                performStorageMigration(oldPath, newPath, copyData)
            }
            return START_STICKY
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val userManager = getSystemService(Context.USER_SERVICE) as? UserManager
            if (userManager?.isUserUnlocked == false) {
                Log.w("NextcloudServer", "Device is still locked in Direct Boot mode. Delaying startup until user unlocks.")
                ensureForeground("Waiting for device unlock...")
                return START_NOT_STICKY
            }
        }

        val savedPort = AppPreferences(this).serverPort
        val port = intent?.getIntExtra("port", savedPort) ?: savedPort

        if (isServerRunning) {
            if (currentRunningPort == 0 || currentRunningPort == port) {
                Log.i("NextcloudServer", "Server is already running on port $port, skipping redundant startup.")
                ensureForeground(lastStatus)
                updateStatus(lastStatus)
                return START_STICKY
            } else {
                Log.i("NextcloudServer", "Server is running on $currentRunningPort, requested $port. Triggering port change...")
                executor.execute {
                    performPortChange(port)
                }
                return START_STICKY
            }
        }

        ensureForeground("Starting server...")
        
        executor.execute {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            val setupWakeLock = powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "NextcloudServer::SetupWakeLock"
            )?.apply {
                setReferenceCounted(false)
                acquire(45 * 60 * 1000L /* 45 minutes max */)
            }

            try {
                val ncManager = NextcloudManager(this@NextcloudServerService)
                val isFirstInstall = !ncManager.isInstalled()
                if (isFirstInstall) {
                    broadcastSetupProgress(true, "Preparing server setup...", 2, false)
                    updateSetupNotification("Preparing server setup", 2)
                }

                prepareBinaries(port)

                if (isFirstInstall) {
                    broadcastSetupProgress(true, "Starting PHP-FPM service...", 95, false)
                    updateSetupNotification("Starting PHP-FPM service", 95)
                }
                startPhpFpm()

                if (isFirstInstall) {
                    broadcastSetupProgress(true, "Starting Nginx web server...", 98, false)
                    updateSetupNotification("Starting Nginx web server", 98)
                }
                startNginx()
                currentRunningPort = port
                isServerRunning = true

                // Initialize SAF & upload relay service
                safManager = SdCardSafManager(this@NextcloudServerService)
                uploadRelayService = UploadRelayService(
                    this@NextcloudServerService,
                    safManager!!,
                    ncManager
                )

                // Initialize hot-plug external storage manager
                storageMountManager = StorageMountManager(this@NextcloudServerService).apply {
                    startMonitoring()
                }

                // Start Zero-Picker MediaStore/SAF Broker for Android 8-17 SD root access
                SdRootMediaStoreBridge.startBroker(this@NextcloudServerService)

                // Start active relay watchers for all storages with granted SAF permissions
                val grantedUuids = safManager?.getAllGrantedStorageUuids() ?: emptyList()
                if (grantedUuids.isNotEmpty()) {
                    uploadRelayService?.startWatching(grantedUuids)
                }

                val wifiIp = ncManager.getWifiIpAddress()
                val ipDisplay = if (wifiIp != null) "http://$wifiIp:$port" else "http://localhost:$port"

                if (isFirstInstall) {
                    broadcastSetupProgress(false, "Server is ready!", 100, true)
                }
                updateStatus("Nextcloud is running at $ipDisplay")

                // Run post-boot OCC maintenance and demo purges asynchronously in background
                Thread({
                    performPostBootMaintenance(ncManager)
                }, "NC-PostBootMaintenance").start()
            } catch (t: Throwable) {
                Log.e("NextcloudServer", "Error starting server", t)
                val errMsg = t.localizedMessage ?: t.message ?: t.javaClass.simpleName
                broadcastSetupProgress(false, "Setup failed: $errMsg", 0, false)
                updateStatus("Error starting server: $errMsg")
            } finally {
                try {
                    if (setupWakeLock?.isHeld == true) {
                        setupWakeLock.release()
                    }
                } catch (_: Exception) {}
            }
        }

        return START_STICKY
    }

    private fun performPostBootMaintenance(ncManager: NextcloudManager) {
        try {
            ncManager.getWifiIpAddress()?.let { ip ->
                ncManager.addTrustedDomain(ip)
            }
            ncManager.ensureAppStoreAndAppsConfig()
            val prefs = AppPreferences(this).sharedPreferences
            val isConfigured = prefs.getBoolean("core_apps_and_previews_configured", false)
            if (!isConfigured) {
                ncManager.ensureCoreAppsEnabled()
                ncManager.configurePreviewSettings()
                prefs.edit().putBoolean("core_apps_and_previews_configured", true).apply()
            }
            try {
                val tmpDir = File(filesDir, "tmp")
                ncManager.executeOcc("config:system:set", "check_data_directory_permissions", "--value=false", "--type=boolean")
                ncManager.executeOcc("config:system:set", "tempdirectory", "--value=" + tmpDir.absolutePath)
                ncManager.clearStaleFileLocks()
                ncManager.purgeAllDefaultDemoFiles()
            } catch (e: Exception) {
                Log.w("NextcloudServer", "Error setting tempdirectory or clearing locks: ${e.message}")
            }
        } catch (e: Exception) {
            Log.w("NextcloudServer", "Error during post-boot maintenance: ${e.message}")
        }
    }

    private fun prepareBinaries(port: Int) {
        val filesDir = filesDir.absolutePath
        val binDir = File(filesDir, "bin")
        val confDir = File(filesDir, "conf")
        val logsDir = File(filesDir, "logs")
        val runDir = File(filesDir, "run")
        val libDir = File(filesDir, "lib")
        val nextcloudDir = File(filesDir, "nextcloud")

        val tmpDir = File(filesDir, "tmp")

        if (!binDir.exists()) binDir.mkdirs()
        if (!confDir.exists()) confDir.mkdirs()
        if (!logsDir.exists()) logsDir.mkdirs()
        if (!runDir.exists()) runDir.mkdirs()
        if (!libDir.exists()) libDir.mkdirs()
        if (!tmpDir.exists()) tmpDir.mkdirs()
        try {
            tmpDir.setReadable(true, false)
            tmpDir.setWritable(true, false)
            tmpDir.setExecutable(true, false)
        } catch (_: Exception) {}

        // Copy binaries from assets (if present in assets for fallback)
        try {
            AssetHelper.copyAssetFolder(this, "bin", binDir.absolutePath)
            binDir.listFiles()?.forEach { file ->
                AssetHelper.makeExecutable(file.absolutePath)
            }
        } catch (_: Exception) {}

        // Copy and process configs
        processConfig("nginx.conf.template", File(confDir, "nginx.conf"), port)
        processConfig("php-fpm.conf.template", File(confDir, "php-fpm.conf"), port)
        processConfig("php.ini.template", File(confDir, "php.ini"), port)
        AssetHelper.copyAssetFolder(this, "conf", confDir.absolutePath) // For mime.types
        try {
            AssetHelper.copyAssetFolder(this, "lib", libDir.absolutePath)
        } catch (_: Exception) {}

        // Auto-install Nextcloud if not already installed
        val nextcloudManager = NextcloudManager(this)
        if (!nextcloudManager.isInstalled()) {
            val prefs = AppPreferences(this).sharedPreferences
            val adminUser = prefs.getString("admin_user", "admin") ?: "admin"
            val adminPass = prefs.getString("admin_pass", "NextcloudAdmin123!") ?: "NextcloudAdmin123!"
            val dataDir = prefs.getString("data_dir", File(nextcloudDir, "data").absolutePath)
                ?: File(nextcloudDir, "data").absolutePath

            broadcastSetupProgress(true, "Extracting Nextcloud core...", 5, false)
            updateSetupNotification("Extracting Nextcloud core", 5)

            val success = nextcloudManager.autoInstall(adminUser, adminPass, dataDir) { stage, percent ->
                broadcastSetupProgress(true, stage, percent, false)
                updateSetupNotification(stage, percent)
            }
            if (!success) {
                throw IOException("Nextcloud initial configuration failed")
            }
        } else {
            // Server is already installed; configurations and binaries are ready.
            // Post-boot OCC optimizations and demo skeleton purges execute asynchronously after server is up.
        }
    }

    private fun processConfig(assetName: String, targetFile: File, port: Int) {
        val template = assets.open(assetName).bufferedReader().readText()
        val processed = template.replace("{{FILES_DIR}}", filesDir.absolutePath)
            .replace("{{NATIVE_LIB_DIR}}", applicationInfo.nativeLibraryDir)
            .replace("{{USER}}", "shell") // Default for Android
            .replace("{{GROUP}}", "shell")
            .replace("{{PORT}}", port.toString())
        targetFile.writeText(processed)
    }

    private fun startPhpFpm() {
        val phpBin = BinaryHelper.getPhpFpmBinary(this)
        val phpBinFile = File(phpBin)
        if (!phpBinFile.exists()) {
            throw IOException("PHP binary not found at $phpBin")
        }

        val phpConf = File(filesDir, "conf/php-fpm.conf").absolutePath
        val phpIni = File(filesDir, "conf/php.ini").absolutePath
        val pb = ProcessBuilder(phpBin, "-p", filesDir.absolutePath, "-y", phpConf, "-c", phpIni, "-F")
        pb.directory(filesDir)
        pb.redirectErrorStream(true)
        
        val env = pb.environment()
        val tmpDir = File(filesDir, "tmp").apply { mkdirs() }
        val opensslCnf = File(filesDir, "conf/openssl.cnf")
        if (opensslCnf.exists()) {
            env["OPENSSL_CONF"] = opensslCnf.absolutePath
        }
        env["TMPDIR"] = tmpDir.absolutePath
        val ldPath = BinaryHelper.getLdLibraryPath(this)
        if (!ldPath.isNullOrBlank()) {
            env["LD_LIBRARY_PATH"] = ldPath + (env["LD_LIBRARY_PATH"]?.let { ":$it" } ?: "")
        }
        
        phpProcess = pb.start()
        
        executor.execute {
            try {
                phpProcess?.inputStream?.bufferedReader()?.forEachLine { line ->
                    Log.d("PHP-FPM", line)
                }
            } catch (e: Exception) {
                Log.d("PHP-FPM", "Stream closed: ${e.message}")
            }
        }
    }

    private fun startNginx() {
        val nginxBin = BinaryHelper.getNginxBinary(this)
        val nginxConf = File(filesDir, "conf/nginx.conf").absolutePath
        val nginxErrorLog = File(filesDir, "logs/nginx_error.log").absolutePath
        val pb = ProcessBuilder(nginxBin, "-p", filesDir.absolutePath, "-c", nginxConf, "-e", nginxErrorLog, "-g", "daemon off;")
        pb.directory(filesDir)
        pb.redirectErrorStream(true)
        
        // Set LD_LIBRARY_PATH
        val env = pb.environment()
        val nginxLdPath = BinaryHelper.getLdLibraryPath(this)
        if (!nginxLdPath.isNullOrBlank()) {
            env["LD_LIBRARY_PATH"] = nginxLdPath + (env["LD_LIBRARY_PATH"]?.let { ":$it" } ?: "")
        }

        nginxProcess = pb.start()

        executor.execute {
            try {
                nginxProcess?.inputStream?.bufferedReader()?.forEachLine { line ->
                    Log.d("Nginx", line)
                }
            } catch (e: Exception) {
                Log.d("Nginx", "Stream closed: ${e.message}")
            }
        }
    }

    private fun stopServer() {
        uploadRelayService?.stopWatching()
        uploadRelayService = null
        safManager = null
        storageMountManager?.stopMonitoring()
        storageMountManager = null
        SdRootMediaStoreBridge.stopBroker()
        phpProcess?.destroy()
        stopNginxProcess()
        phpProcess = null
        nginxProcess = null
        isServerRunning = false
        currentRunningPort = 0
        AppPreferences(this).isServerRunning = false
        lastStatus = "Stopped"
        val intent = Intent("com.nextcloud.server.STATUS_UPDATE").apply {
            setPackage(packageName)
            putExtra("status", "Server is stopped")
        }
        sendBroadcast(intent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            Log.e("NextcloudServer", "Error releasing wake lock", e)
        }
    }

    private fun setWakeLockEnabled(enabled: Boolean) {
        try {
            if (enabled) {
                if (wakeLock == null) {
                    val powerManager = getSystemService(android.content.Context.POWER_SERVICE) as PowerManager
                    wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NextcloudServer::WakeLock")
                }
                if (wakeLock?.isHeld != true) {
                    wakeLock?.acquire(24 * 60 * 60 * 1000L /* 24 hours */)
                    Log.i("NextcloudServer", "Wake lock acquired dynamically")
                }
            } else {
                if (wakeLock?.isHeld == true) {
                    wakeLock?.release()
                    Log.i("NextcloudServer", "Wake lock released dynamically")
                }
            }
        } catch (e: Exception) {
            Log.e("NextcloudServer", "Error toggling wake lock (enabled=$enabled)", e)
        }
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private var lastNotifPercent = -1
    private var lastNotifStage = ""

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            try {
                manager.deleteNotificationChannel("NextcloudServerChannel")
            } catch (_: Exception) {}

            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Nextcloud Server Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Nextcloud background server and setup notifications"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun updateSetupNotification(stage: String, percent: Int) {
        if (percent == lastNotifPercent && stage == lastNotifStage) return
        if (percent != 100 && percent - lastNotifPercent < 5 && stage == lastNotifStage) return
        lastNotifPercent = percent
        lastNotifStage = stage

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Nextcloud Setup")
            .setContentText("$stage ($percent%)")
            .setSmallIcon(R.drawable.ic_nextcloud_logo)
            .setProgress(100, percent, false)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(true)
            .build()
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(1, notification)
    }

    private fun createNotification(content: String): Notification {
        val stopIntent = Intent(this, NextcloudServerService::class.java).apply {
            action = "STOP"
        }
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Nextcloud Server")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_nextcloud_logo)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .build()
    }

    private fun updateStatus(content: String) {
        lastStatus = content
        isServerRunning = content.contains("running", ignoreCase = true) || content.contains("http://", ignoreCase = true)
        AppPreferences(this).isServerRunning = isServerRunning
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(1, createNotification(content))
        
        // Send broadcast to Activity
        val intent = Intent("com.nextcloud.server.STATUS_UPDATE").apply {
            setPackage(packageName)
            putExtra("status", content)
        }
        sendBroadcast(intent)
    }

    private fun ensureForeground(notificationText: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, createNotification(notificationText), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, createNotification(notificationText))
        }
    }

    private fun performStorageMigration(oldPath: String, newPath: String, copyData: Boolean) {
        isMigrationRunning = true
        lastMigrationStage = if (copyData) "Starting migration..." else "Configuring new storage..."
        lastMigrationPercent = 0

        val nextcloudManager = NextcloudManager(this)
        val isServerUp = phpProcess != null && nginxProcess != null

        // 1. Put Nextcloud into maintenance mode if server is running
        if (isServerUp) {
            nextcloudManager.setMaintenanceMode(true)
        }

        updateMigrationNotification(lastMigrationStage, 0)
        broadcastMigrationProgress(isMigrationRunning, lastMigrationStage, 0, newPath, false)

        val success = nextcloudManager.migrateDataDirectory(oldPath, newPath, copyData) { stage, percent ->
            lastMigrationStage = stage
            lastMigrationPercent = percent
            updateMigrationNotification(stage, percent)
            broadcastMigrationProgress(true, stage, percent, newPath, false)
        }

        // 2. Persist new storage location in prefs
        if (success) {
            AppPreferences(this).sharedPreferences.edit()
                .putString("data_dir", newPath)
                .apply()
        }

        isMigrationRunning = false
        lastMigrationStage = if (success) "Storage configuration completed!" else "Storage configuration failed"
        lastMigrationPercent = if (success) 100 else 0

        broadcastMigrationProgress(false, lastMigrationStage, lastMigrationPercent, newPath, success)

        // 3. Restore normal server notification
        if (isServerUp) {
            try {
                phpProcess?.destroy()
                startPhpFpm()
            } catch (e: Exception) {
                Log.e("NextcloudServer", "Error restarting PHP-FPM after migration", e)
            }
            val port = AppPreferences(this).serverPort
            val wifiIp = nextcloudManager.getWifiIpAddress()
            val ipDisplay = if (wifiIp != null) "http://$wifiIp:$port" else "http://localhost:$port"
            updateStatus("Nextcloud is running at $ipDisplay")
        } else {
            updateStatus("Server is stopped. Storage updated to $newPath")
            Handler(Looper.getMainLooper()).postDelayed({
                if (!isMigrationRunning && phpProcess == null && nginxProcess == null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    } else {
                        @Suppress("DEPRECATION")
                        stopForeground(true)
                    }
                    stopSelf()
                }
            }, 3000)
        }
    }

    private fun updateMigrationNotification(stage: String, percent: Int) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Nextcloud Storage Setup")
            .setContentText(stage)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setProgress(100, percent, percent == 0)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(true)
            .build()
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(1, notification)
    }

    private fun broadcastMigrationProgress(
        isRunning: Boolean,
        stage: String,
        percent: Int,
        newPath: String,
        success: Boolean
    ) {
        val intent = Intent(ACTION_MIGRATION_PROGRESS).apply {
            setPackage(packageName)
            putExtra("is_running", isRunning)
            putExtra("stage", stage)
            putExtra("percent", percent)
            putExtra("new_path", newPath)
            putExtra("success", success)
        }
        sendBroadcast(intent)
    }

    private fun performPortChange(newPort: Int) {
        isPortChangeRunning = true
        lastPortChangePercent = 25
        lastPortChangeStatus = "Reconfiguring web server for port $newPort..."
        updatePortProgressNotification(lastPortChangeStatus, 25)
        broadcastPortProgress(true, lastPortChangeStatus, 25, newPort, false)

        try {
            val confDir = File(filesDir, "conf")
            processConfig("nginx.conf.template", File(confDir, "nginx.conf"), newPort)

            lastPortChangePercent = 60
            lastPortChangeStatus = "Restarting web server on port $newPort..."
            updatePortProgressNotification(lastPortChangeStatus, 60)
            broadcastPortProgress(true, lastPortChangeStatus, 60, newPort, false)

            stopNginxProcess()
            Thread.sleep(500)

            lastPortChangePercent = 85
            lastPortChangeStatus = "Starting web server on port $newPort..."
            updatePortProgressNotification(lastPortChangeStatus, 85)
            broadcastPortProgress(true, lastPortChangeStatus, 85, newPort, false)

            startNginx()
            currentRunningPort = newPort
            isServerRunning = true
            Thread.sleep(600)

            // Update SharedPreferences with the newly applied port
            AppPreferences(this).serverPort = newPort

            val ncManager = NextcloudManager(this@NextcloudServerService)
            ncManager.ensureAppStoreAndAppsConfig()
            try {
                ncManager.executeOcc("config:system:set", "overwrite.cli.url", "--value=http://localhost:$newPort")
            } catch (e: Exception) {
                Log.w("NextcloudServer", "Could not update overwrite.cli.url via occ: ${e.message}")
            }

            val wifiIp = ncManager.getWifiIpAddress()
            val ipDisplay = if (wifiIp != null) "http://$wifiIp:$newPort" else "http://localhost:$newPort"

            lastPortChangePercent = 100
            lastPortChangeStatus = "Online on port $newPort"
            broadcastPortProgress(false, "Server is running on port $newPort", 100, newPort, true)
            updateStatus("Nextcloud is running at $ipDisplay")
        } catch (e: Exception) {
            Log.e("NextcloudServer", "Error changing port to $newPort", e)
            val err = "Failed to switch port: ${e.message}"
            lastPortChangeStatus = err
            broadcastPortProgress(false, err, 0, newPort, false)
            updateStatus("Error changing port: ${e.message}")
        } finally {
            isPortChangeRunning = false
        }
    }

    private fun stopNginxProcess() {
        try {
            val pidFile = File(filesDir, "run/nginx.pid")
            if (pidFile.exists()) {
                val pid = pidFile.readText().trim()
                if (pid.isNotEmpty()) {
                    try {
                        Runtime.getRuntime().exec(arrayOf("kill", "-TERM", pid)).waitFor(1, java.util.concurrent.TimeUnit.SECONDS)
                    } catch (e: Exception) {
                        Log.w("NextcloudServer", "kill -TERM failed", e)
                    }
                }
            }
            nginxProcess?.destroy()
            nginxProcess?.waitFor(1500, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            Log.w("NextcloudServer", "Error terminating nginx", e)
        }
        nginxProcess = null
    }

    private fun updatePortProgressNotification(stage: String, percent: Int) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Nextcloud Server")
            .setContentText(stage)
            .setSmallIcon(R.drawable.ic_nextcloud_logo)
            .setProgress(100, percent, false)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(true)
            .build()
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(1, notification)
    }

    private fun broadcastPortProgress(
        isRunning: Boolean,
        status: String,
        percent: Int,
        newPort: Int,
        success: Boolean
    ) {
        val intent = Intent(ACTION_PORT_PROGRESS).apply {
            setPackage(packageName)
            putExtra("is_running", isRunning)
            putExtra("status", status)
            putExtra("percent", percent)
            putExtra("port", newPort)
            putExtra("success", success)
        }
        sendBroadcast(intent)
    }

    private fun broadcastSetupProgress(
        isRunning: Boolean,
        stage: String,
        percent: Int,
        success: Boolean
    ) {
        isSetupRunning = isRunning
        lastSetupStage = stage
        lastSetupPercent = percent

        setupProgressListener?.onSetupProgress(isRunning, stage, percent, success)

        val intent = Intent(ACTION_SETUP_PROGRESS).apply {
            setPackage(packageName)
            putExtra("is_running", isRunning)
            putExtra("stage", stage)
            putExtra("percent", percent)
            putExtra("success", success)
        }
        sendBroadcast(intent)
    }
}

