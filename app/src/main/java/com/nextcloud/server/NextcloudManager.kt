package com.nextcloud.server

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Environment
import android.text.format.Formatter
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import android.os.ParcelFileDescriptor
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * High-level manager for Nextcloud Server operations:
 * - Automated pre-installation on first launch
 * - Administrator credential management
 * - Trusted domains management
 * - Root data storage directory switching with safe atomic data migration
 * - Maintenance mode control
 * - OCC CLI process execution
 */
class NextcloudManager(private val context: Context) {

    companion object {
        private const val TAG = "NextcloudManager"
    }

    private val filesDir: File = context.filesDir
    private val nextcloudDir: File = File(filesDir, "nextcloud")
    private val configPhp: File = File(nextcloudDir, "config/config.php")

    fun isReadyForOcc(isInstalling: Boolean = false): Boolean {
        val phpBin = File(BinaryHelper.getPhpBinary(context))
        val occ = File(filesDir, "nextcloud/occ")
        val consolePhp = File(filesDir, "nextcloud/console.php")
        val configPhp = File(filesDir, "nextcloud/config/config.php")
        val extractionSentinel = File(nextcloudDir, ".extraction_complete")

        if (!phpBin.exists() || !occ.exists() || !consolePhp.exists()) {
            return false
        }
        if (!isInstalling && !configPhp.exists()) {
            return false
        }
        if (!extractionSentinel.exists() && !configPhp.exists()) {
            return false
        }
        return true
    }

    fun isInstalled(): Boolean {
        if (!configPhp.exists()) return false
        if (!File(nextcloudDir, "console.php").exists()) return false
        if (!File(nextcloudDir, "version.php").exists()) return false
        val output = executeOcc("status")
        return output.contains("installed: true", ignoreCase = true)
    }

    /**
     * Automatically pre-installs Nextcloud on first boot so the user never sees
     * a setup wizard in the browser.
     */
    fun autoInstall(
        adminUser: String,
        adminPass: String,
        dataDir: String,
        onProgress: ((stage: String, percent: Int) -> Unit)? = null
    ): Boolean {
        // Pre-flight check: minimum free storage required (1.2 GB)
        val freeBytes = filesDir.freeSpace
        val minRequiredBytes = 1200L * 1024 * 1024
        if (freeBytes < minRequiredBytes) {
            val freeMb = freeBytes / (1024 * 1024)
            throw java.io.IOException("Insufficient internal storage. Nextcloud requires at least 1.2 GB free space, only ${freeMb} MB available.")
        }

        onProgress?.invoke("Extracting Nextcloud core...", 5)
        val extractionSentinel = File(nextcloudDir, ".extraction_complete")
        val hasExistingWorkingConfig = configPhp.exists() && File(nextcloudDir, "version.php").exists()

        if (!extractionSentinel.exists() && !hasExistingWorkingConfig) {
            if (nextcloudDir.exists()) {
                Log.w(TAG, "Incomplete or interrupted Nextcloud extraction found. Purging for a clean extract.")
                nextcloudDir.deleteRecursively()
            }
            AssetHelper.unzipAsset(context, "nextcloud.zip", filesDir.absolutePath, 33992) { zipPct ->
                val overall = 5 + ((zipPct * 65) / 100)
                onProgress?.invoke("Extracting Nextcloud core ($zipPct%)...", overall)
            }
            if (File(nextcloudDir, "version.php").exists() && File(nextcloudDir, "console.php").exists() && File(nextcloudDir, "occ").exists()) {
                try {
                    extractionSentinel.createNewFile()
                    Log.i(TAG, "Extraction verified 100% complete; sentinel created.")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to write extraction sentinel: ${e.message}")
                }
            } else {
                throw java.io.IOException("Nextcloud extraction incomplete or missing core files.")
            }
        } else if (!extractionSentinel.exists() && hasExistingWorkingConfig) {
            try { extractionSentinel.createNewFile() } catch (_: Exception) {}
        }

        val targetDataDir = File(dataDir)
        if (!targetDataDir.exists()) {
            targetDataDir.mkdirs()
        }
        val canonicalDataDir = try { targetDataDir.canonicalPath } catch (_: Exception) { targetDataDir.absolutePath }

        // Create standard verification markers
        ensureNcMarkers(targetDataDir)
        ensurePatches()

        onProgress?.invoke("Initializing Nextcloud SQLite database...", 72)
        val output = executeOcc(
            "maintenance:install",
            "--database=sqlite",
            "--database-name=nextcloud",
            "--admin-user=$adminUser",
            "--admin-pass=$adminPass",
            "--data-dir=$canonicalDataDir"
        )
        
        Log.i(TAG, "Install output: $output")
        val success = output.contains("Nextcloud was successfully installed", ignoreCase = true) || isInstalled()

        if (success) {
            onProgress?.invoke("Configuring password policies...", 80)
            try {
                executeOcc("config:app:set", "password_policy", "enforce_have_i_been_pwned", "--value=0")
                executeOcc("config:app:set", "password_policy", "enforce_common_passwords", "--value=0")
                executeOcc("config:app:set", "password_policy", "min_length", "--value=4")
            } catch (_: Exception) {}

            onProgress?.invoke("Configuring trusted domains...", 83)
            // Automatically add localhost, 127.0.0.1, and current Wi-Fi LAN IP
            addTrustedDomain("localhost")
            addTrustedDomain("127.0.0.1")
            getWifiIpAddress()?.let { ip ->
                addTrustedDomain(ip)
            }

            onProgress?.invoke("Enabling productivity apps...", 86)
            ensureCoreAppsEnabled()

            onProgress?.invoke("Finalizing server parameters...", 90)
            ensureAppStoreAndAppsConfig()
            executeOcc("config:system:set", "check_data_directory_permissions", "--value=false", "--type=boolean")
            executeOcc("config:system:set", "allow_local_remote_servers", "--value=true", "--type=boolean")
            executeOcc("config:system:set", "tempdirectory", "--value=" + File(filesDir, "tmp").apply { mkdirs() }.absolutePath)
            executeOcc("config:system:set", "appstoreenabled", "--value=true", "--type=boolean")
            executeOcc("config:system:set", "default_phone_region", "--value=US")
            val currentPort = AppPreferences(context).serverPort
            executeOcc("config:system:set", "overwrite.cli.url", "--value=http://localhost:$currentPort")
            onProgress?.invoke("Nextcloud core setup ready", 92)
        }

        return success
    }

    /**
     * Automatically ensures all pre-bundled productivity apps (files_external,
     * calendar, contacts, notes, deck) are enabled out-of-the-box in a single batch OCC call.
     */
    fun ensureCoreAppsEnabled() {
        val coreApps = listOf("files_external", "calendar", "contacts", "notes", "deck", "photos", "viewer")
        val appsToEnable = mutableListOf<String>()
        for (app in coreApps) {
            val appInApps = File(File(nextcloudDir, "apps"), app).exists()
            val appInCustom = File(File(nextcloudDir, "custom_apps"), app).exists()
            if (appInApps || appInCustom) {
                appsToEnable.add(app)
            }
        }
        if (appsToEnable.isNotEmpty()) {
            try {
                val args = mutableListOf("app:enable")
                args.addAll(appsToEnable)
                val out = executeOcc(*args.toTypedArray())
                Log.d(TAG, "Enabled apps [${appsToEnable.joinToString()}]: $out")
            } catch (e: Exception) {
                Log.w(TAG, "Could not enable core apps in batch", e)
            }
        }
        ensureQrLoginCompatibility()
        configurePreviewSettings()
    }

    /**
     * Configures Nextcloud Preview and thumbnail generation subsystem for optimal
     * mobile performance and full Photos/Videos gallery support via native PHP GD.
     */
    fun configurePreviewSettings() {
        try {
            executeOcc("config:system:set", "filelocking.enabled", "--value=false", "--type=boolean")
            executeOcc("config:system:set", "filelocking.ttl", "--value=300", "--type=integer")
            // Configure 20MB chunk size for reliable 40GB+ uploads through Cloudflare
            executeOcc("config:app:set", "files", "max_chunk_size", "--value=20971520")

            executeOcc("config:system:set", "enable_previews", "--value=true", "--type=boolean")
            executeOcc("config:system:set", "preview_max_x", "--value=2048", "--type=integer")
            executeOcc("config:system:set", "preview_max_y", "--value=2048", "--type=integer")
            executeOcc("config:system:set", "preview_max_memory", "--value=256", "--type=integer")
            executeOcc("config:system:set", "preview_max_filesize_image", "--value=50", "--type=integer")
            executeOcc("config:system:set", "preview_concurrency_all", "--value=8", "--type=integer")
            executeOcc("config:system:set", "preview_concurrency_new", "--value=4", "--type=integer")

            val ffmpegBin = File(filesDir, "bin/ffmpeg")
            if (ffmpegBin.exists()) {
                executeOcc("config:system:set", "preview_ffmpeg_path", "--value=${ffmpegBin.absolutePath}")
            }

            val providers = listOf(
                "OC\\Preview\\PNG",
                "OC\\Preview\\JPEG",
                "OC\\Preview\\GIF",
                "OC\\Preview\\BMP",
                "OC\\Preview\\WebP",
                "OC\\Preview\\HEIC",
                "OC\\Preview\\Movie",
                "OC\\Preview\\XBitmap",
                "OC\\Preview\\MarkDown",
                "OC\\Preview\\MP3",
                "OC\\Preview\\TXT"
            )
            for ((idx, provider) in providers.withIndex()) {
                executeOcc("config:system:set", "enabledPreviewProviders", idx.toString(), "--value=$provider")
            }
            Log.i(TAG, "Preview generation system configured successfully with GD, HEIC, and Movie support.")
        } catch (e: Exception) {
            Log.w(TAG, "Error configuring preview settings: ${e.message}")
        }
    }

    /**
     * Clears orphaned file locks from oc_file_locks in SQLite.
     * Prevents HTTP 423 Locked errors caused by interrupted transfers or aborted chunk uploads.
     */
    fun clearStaleFileLocks() {
        try {
            val sqliteBin = BinaryHelper.getSqlite3Binary(context)
            val dbFile = File(nextcloudDir, "data/owncloud.db")
            if (File(sqliteBin).exists() && dbFile.exists()) {
                val pb = ProcessBuilder(sqliteBin, dbFile.absolutePath, "DELETE FROM oc_file_locks;")
                val proc = pb.start()
                proc.waitFor()
                Log.i(TAG, "Successfully purged oc_file_locks table on server start.")
            } else {
                executeOcc("maintenance:repair")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Non-fatal error clearing stale locks: ${e.message}")
        }
    }

    /**
     * Purges default Nextcloud skeleton and demo files (manual PDFs, intro MP4s, sample pictures)
     * so that the user's root folder is 100% clean and contains only real storage volumes and user files.
     */
    fun purgeAllDefaultDemoFiles() {
        try {
            // 1. Wipe core/skeleton directory completely so new accounts are always clean
            val skeletonDir = File(nextcloudDir, "core/skeleton")
            if (skeletonDir.exists() && skeletonDir.isDirectory) {
                skeletonDir.listFiles()?.forEach { it.deleteRecursively() }
                Log.i(TAG, "Purged core/skeleton contents to prevent seeding demo files.")
            }

            // 2. Lock skeletondirectory configuration
            executeOcc("config:system:set", "skeletondirectory", "--value=")

            // 3. Purge existing accounts
            val dataDir = File(nextcloudDir, "data")
            if (!dataDir.exists() || !dataDir.isDirectory) return

            val demoFileNames = setOf(
                "Nextcloud Manual.pdf",
                "Nextcloud intro.mp4",
                "Nextcloud.png",
                "Nextcloud flyer.pdf",
                "Reasons to use Nextcloud.pdf",
                "Readme.md",
                "Templates credits.md"
            )

            var anyDeleted = false
            dataDir.listFiles()?.filter { it.isDirectory && !it.name.startsWith("appdata_") }?.forEach { userDir ->
                val filesFolder = File(userDir, "files")
                if (filesFolder.exists() && filesFolder.isDirectory) {
                    filesFolder.listFiles()?.forEach { file ->
                        if (demoFileNames.contains(file.name)) {
                            file.deleteRecursively()
                            anyDeleted = true
                            Log.i(TAG, "Purged demo file: ${file.absolutePath}")
                        } else if (file.isDirectory && (file.name == "Photos" || file.name == "Documents" || file.name == "Templates")) {
                            val contents = file.listFiles() ?: emptyArray()
                            val isDemoDir = contents.all { c ->
                                c.name in setOf("Coast.jpg", "Hummingbird.jpg", "Nut.jpg", "Readme.md", "About.txt", "Example.odt") ||
                                c.name.endsWith(".md") || c.name.endsWith(".txt")
                            }
                            if (isDemoDir && contents.size <= 5) {
                                file.deleteRecursively()
                                anyDeleted = true
                                Log.i(TAG, "Purged demo directory: ${file.absolutePath}")
                            }
                        }
                    }
                }
            }

            if (anyDeleted) {
                executeOcc("files:cleanup")
                executeOcc("files:scan", "--all")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error purging default demo files: ${e.message}")
        }
    }

    /**
     * Ensures mobile app QR login compatibility by setting settings:qrcode_onetime to 'no'.
     * In Nextcloud 30+, qrcode_onetime defaults to true which generates restricted one-time
     * tokens that immediately invalidate upon connection from apps not implementing the 2026+ flow.
     * Disabling this flag generates standard permanent app passwords with full permissions
     * compatible with all official Nextcloud mobile apps (Android and iOS).
     */
    fun ensureQrLoginCompatibility() {
        try {
            val out = executeOcc("config:app:set", "settings", "qrcode_onetime", "--value=no")
            Log.d(TAG, "Ensured QR code mobile login compatibility: $out")
        } catch (e: Exception) {
            Log.w(TAG, "Could not set qrcode_onetime to no", e)
        }
    }

    /**
     * Ensures apps.config.php exists with proper apps_paths, appstoreenabled,
     * allow_local_remote_servers, default_phone_region, overwrite.cli.url,
     * symlink tolerance for Android, and preview/thumbnail providers.
     */
    fun ensureAppStoreAndAppsConfig() {
        try {
            val customApps = File(nextcloudDir, "custom_apps")
            if (!customApps.exists()) {
                customApps.mkdirs()
            }
            val configDir = File(nextcloudDir, "config")
            if (!configDir.exists()) {
                configDir.mkdirs()
            }
            val appsConfig = File(configDir, "apps.config.php")
            val currentPort = AppPreferences(context).serverPort
            val content = """<?php
${'$'}isHttps = (!empty(${'$'}_SERVER['HTTPS']) && ${'$'}_SERVER['HTTPS'] !== 'off')
    || (!empty(${'$'}_SERVER['HTTP_X_FORWARDED_PROTO']) && ${'$'}_SERVER['HTTP_X_FORWARDED_PROTO'] === 'https');

${'$'}CONFIG = [
  'apps_paths' => [
    0 => [
      'path' => __DIR__ . '/../apps',
      'url' => '/apps',
      'writable' => false,
    ],
    1 => [
      'path' => __DIR__ . '/../custom_apps',
      'url' => '/custom_apps',
      'writable' => true,
    ],
  ],
  'appstoreenabled' => true,
  'skeletondirectory' => '',
  'allow_local_remote_servers' => true,
  'check_data_directory_permissions' => false,
  'tempdirectory' => dirname(__DIR__, 2) . '/tmp',
  'default_phone_region' => 'US',
  'overwrite.cli.url' => 'http://localhost:$currentPort',
  'overwriteprotocol' => ${'$'}isHttps ? 'https' : '',
  'localstorage.allowsymlinks' => true,
  'filelocking.enabled' => false,
  'filelocking.ttl' => 300,
  'enable_previews' => true,
  'preview_max_x' => 2048,
  'preview_max_y' => 2048,
  'preview_max_scale_factor' => 1,
  'preview_ffmpeg_path' => dirname(__DIR__, 2) . '/bin/ffmpeg',
  'enabledPreviewProviders' => [
    'OC\\Preview\\PNG',
    'OC\\Preview\\JPEG',
    'OC\\Preview\\GIF',
    'OC\\Preview\\BMP',
    'OC\\Preview\\WebP',
    'OC\\Preview\\HEIC',
    'OC\\Preview\\Movie',
    'OC\\Preview\\XBitmap',
    'OC\\Preview\\MarkDown',
    'OC\\Preview\\MP3',
    'OC\\Preview\\TXT',
    'OC\\Preview\\OpenDocument',
    'OC\\Preview\\Krita',
  ],
  'sqlite.journal_mode' => 'WAL',
  'maintenance_window_start' => 1,
  'trusted_proxies' => [
    '127.0.0.1',
    '::1',
    '10.0.0.0/8',
    '172.16.0.0/12',
    '192.168.0.0/16',
    'fd00::/8',
    '173.245.48.0/20',
    '103.21.244.0/22',
    '103.22.200.0/22',
    '103.31.4.0/22',
    '141.101.64.0/18',
    '108.162.192.0/18',
    '190.93.240.0/20',
    '188.114.96.0/20',
    '197.234.240.0/22',
    '198.41.128.0/17',
    '162.158.0.0/15',
    '104.16.0.0/13',
    '104.24.0.0/14',
    '172.64.0.0/13',
    '131.0.72.0/22',
    '2400:cb00::/32',
    '2606:4700::/32',
    '2803:f800::/32',
    '2405:b500::/32',
    '2405:8100::/32',
    '2a06:98c0::/29',
    '2c0f:f248::/32',
  ],
  'forwarded_for_headers' => [
    'HTTP_CF_CONNECTING_IP',
    'HTTP_X_FORWARDED_FOR',
  ],
];
""".trimIndent()
            appsConfig.writeText(content)
            Log.i(TAG, "Ensured apps.config.php and custom_apps directory")

            ensureThemingAndAppstorePatches()
            purgeDefaultDemoFiles()
        } catch (e: Exception) {
            Log.e(TAG, "Error ensuring apps config", e)
        }
    }

    /**
     * Purges default demo files and folders (e.g. Nextcloud intro.mp4, Nextcloud Manual.pdf,
     * Readme.md, Reasons to use Nextcloud.pdf, Templates/, Documents/, etc.) from user storage
     * so that the file list starts completely clean.
     */
    fun purgeDefaultDemoFiles() {
        try {
            val dataDir = File(nextcloudDir, "data")
            if (!dataDir.exists()) return
            val userDirs = dataDir.listFiles()?.filter { it.isDirectory && !it.name.startsWith("appdata_") && it.name != "tmp" } ?: emptyList()
            var modified = false
            for (userDir in userDirs) {
                val filesDir = File(userDir, "files")
                if (!filesDir.exists()) continue
                val demoNames = setOf(
                    "Nextcloud intro.mp4",
                    "Nextcloud Manual.pdf",
                    "Nextcloud.png",
                    "Reasons to use Nextcloud.pdf",
                    "Readme.md",
                    "Templates",
                    "Documents"
                )
                filesDir.listFiles()?.forEach { file ->
                    if (demoNames.contains(file.name)) {
                        file.deleteRecursively()
                        Log.i(TAG, "Purged default demo file/folder: ${file.absolutePath}")
                        modified = true
                    }
                }
            }
            if (modified) {
                executeOcc("files:scan", "--all")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Non-fatal error purging default demo files: ${e.message}")
        }
    }

    /**
     * Patches runtime theming and appstore files on Android to resolve:
     * 1. Missing CSP image domains for App Store thumbnails
     * 2. Broken local relative screenshot URLs proxied to external usercontent CDN
     * 3. Theming app icon crashes and fallbacks when ImageMagick is absent
     */
    fun ensureThemingAndAppstorePatches() {
        try {
            // 1. Patch PageController.php CSP
            val pageCtrl = File(nextcloudDir, "apps/appstore/lib/Controller/PageController.php")
            if (pageCtrl.exists()) {
                var content = pageCtrl.readText()
                val oldCsp = "\$policy->addAllowedImageDomain('https://usercontent.apps.nextcloud.com');"
                if (content.contains(oldCsp) && !content.contains("https://apps.nextcloud.com")) {
                    val newCsp = """$oldCsp
		${'$'}policy->addAllowedImageDomain('https://apps.nextcloud.com');
		${'$'}policy->addAllowedImageDomain('https://raw.githubusercontent.com');
		${'$'}policy->addAllowedImageDomain('https://github.com');
		${'$'}policy->addAllowedImageDomain('https://avatars.githubusercontent.com');
		${'$'}policy->addAllowedImageDomain('http:');
		${'$'}policy->addAllowedImageDomain('https:');
		${'$'}policy->addAllowedImageDomain('data:');
		${'$'}policy->addAllowedImageDomain('blob:');"""
                    content = content.replace(oldCsp, newCsp)
                    pageCtrl.writeText(content)
                    Log.i(TAG, "Patched PageController.php CSP for appstore thumbnails")
                }
            }

            // 2. Patch ApiController.php screenshot proxying
            val apiCtrl = File(nextcloudDir, "apps/appstore/lib/Controller/ApiController.php")
            if (apiCtrl.exists()) {
                var content = apiCtrl.readText()
                val oldProxy = "return 'https://usercontent.apps.nextcloud.com/' . base64_encode(\$url);"
                val newProxy = """if (str_starts_with(${'$'}url, 'https://usercontent.apps.nextcloud.com/')) {
			return ${'$'}url;
		}
		if (!str_starts_with(${'$'}url, 'http://') && !str_starts_with(${'$'}url, 'https://')) {
			return ${'$'}url;
		}
		return 'https://usercontent.apps.nextcloud.com/' . base64_encode(${'$'}url);"""
                if (content.contains(oldProxy) && !content.contains("str_starts_with(\$url, 'http://')")) {
                    content = content.replace(oldProxy, newProxy)
                    apiCtrl.writeText(content)
                    Log.i(TAG, "Patched ApiController.php proxy url handling")
                }
            }

            // 3. Patch IconController.php theming fallback
            val iconCtrl = File(nextcloudDir, "apps/theming/lib/Controller/IconController.php")
            if (iconCtrl.exists()) {
                var content = iconCtrl.readText()
                if (content.contains("} catch (NotFoundException \$exception) {")) {
                    val oldCatch = "} catch (NotFoundException \$exception) {"
                    val newCatch = "} catch (\\Throwable \$exception) {"
                    content = content.replace(oldCatch, newCatch)

                    val oldSet = "\$iconFileName = \$this->imageManager->setCachedImage('icon-' . \$app . '-' . \$color . str_replace('/', '_', \$image), \$icon);\n\t\t}\n\t\t\$response = new FileDisplayResponse(\$iconFileName, Http::STATUS_OK, ['Content-Type' => 'image/svg+xml']);"
                    val newSet = """try {
				${'$'}iconFileName = ${'$'}this->imageManager->setCachedImage('icon-' . ${'$'}app . '-' . ${'$'}color . str_replace('/', '_', ${'$'}image), ${'$'}icon);
				${'$'}response = new FileDisplayResponse(${'$'}iconFileName, Http::STATUS_OK, ['Content-Type' => 'image/svg+xml']);
			} catch (\Throwable ${'$'}e) {
				${'$'}response = new DataDisplayResponse(${'$'}icon, Http::STATUS_OK, ['Content-Type' => 'image/svg+xml']);
			}
		}"""
                    if (content.contains(oldSet)) {
                        content = content.replace(oldSet, newSet)
                    }
                    iconCtrl.writeText(content)
                    Log.i(TAG, "Patched IconController.php theming icon fallback")
                }
            }

            // 4. Patch Notes App to prevent NoUserException 500 crash when session user does not exist or was deleted
            val noteUtil = File(nextcloudDir, "apps/notes/lib/Service/NoteUtil.php")
            if (noteUtil.exists()) {
                var content = noteUtil.readText()
                val oldCatch = "catch (NotesFolderException \$e) {"
                val newCatch = "catch (\\Throwable \$e) {"
                if (content.contains(oldCatch)) {
                    content = content.replace(oldCatch, newCatch)
                    noteUtil.writeText(content)
                    Log.i(TAG, "Patched NoteUtil.php to catch \\Throwable against NoUserException")
                }
            }

            val notesCap = File(nextcloudDir, "apps/notes/lib/AppInfo/Capabilities.php")
            if (notesCap.exists()) {
                var content = notesCap.readText()
                val oldNotesCall = "'notes_path' => \$this->userId !== null && \$this->userId !== ' ' ? \$this->noteUtil->getNotesFolderUserPath(\$this->userId, true) : null,"
                if (content.contains(oldNotesCall)) {
                    val newNotesCall = """'notes_path' => (${'$'}this->userId !== null && ${'$'}this->userId !== ' ') ? (function() {
				try {
					return ${'$'}this->noteUtil->getNotesFolderUserPath(${'$'}this->userId, true);
				} catch (\Throwable ${'$'}e) {
					return null;
				}
			})() : null,"""
                    content = content.replace(oldNotesCall, newNotesCall)
                    notesCap.writeText(content)
                    Log.i(TAG, "Patched Notes Capabilities.php against NoUserException crash")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error applying theming and appstore patches", e)
        }
    }

    fun setMaintenanceMode(enable: Boolean): Boolean {
        val flag = if (enable) "--on" else "--off"
        val out = executeOcc("maintenance:mode", flag)
        Log.i(TAG, "Maintenance mode $flag: $out")
        return out.contains("Maintenance mode", ignoreCase = true)
    }

    fun isUserExists(username: String): Boolean {
        val out = executeOcc("user:info", username.trim())
        return !out.contains("User does not exist", ignoreCase = true) && out.contains("user_id:", ignoreCase = true)
    }

    fun createAdminUser(username: String, pass: String): Pair<Boolean, String> {
        if (!isReadyForOcc()) return Pair(false, "Nextcloud server is not installed yet")
        try {
            executeOcc("config:app:set", "password_policy", "enforce_have_i_been_pwned", "--value=0")
            executeOcc("config:app:set", "password_policy", "enforce_common_passwords", "--value=0")
            executeOcc("config:app:set", "password_policy", "min_length", "--value=4")
        } catch (_: Exception) {}

        val out = executeOcc(
            "user:add",
            "--password-from-env",
            "--group=admin",
            username.trim(),
            envVars = mapOf("OC_PASS" to pass, "NC_PASS" to pass)
        )
        Log.i(TAG, "Create admin user $username: $out")
        val success = out.contains("The user \"$username\" was created successfully", ignoreCase = true) || isUserExists(username)
        if (success) {
            return Pair(true, "")
        }
        val err = out.lines().firstOrNull {
            it.contains("password", ignoreCase = true) || it.contains("error", ignoreCase = true) || it.contains("failed", ignoreCase = true)
        } ?: out.trim()
        return Pair(false, err)
    }

    fun resetAdminPassword(adminUser: String, newPass: String): Pair<Boolean, String> {
        if (!isReadyForOcc()) return Pair(false, "Nextcloud server is not installed yet")
        try {
            executeOcc("config:app:set", "password_policy", "enforce_have_i_been_pwned", "--value=0")
            executeOcc("config:app:set", "password_policy", "enforce_common_passwords", "--value=0")
            executeOcc("config:app:set", "password_policy", "min_length", "--value=4")
        } catch (_: Exception) {}

        val output = executeOcc(
            "user:resetpassword",
            "--password-from-env",
            adminUser.trim(),
            envVars = mapOf("OC_PASS" to newPass, "NC_PASS" to newPass)
        )
        Log.i(TAG, "Reset password output: $output")
        val success = output.contains("Successfully reset password", ignoreCase = true)
        if (success) {
            return Pair(true, "")
        }
        val err = output.lines().firstOrNull {
            it.contains("password", ignoreCase = true) || it.contains("error", ignoreCase = true) || it.contains("failed", ignoreCase = true)
        } ?: output.trim()
        return Pair(false, err)
    }

    fun updateSuperAdminCredentials(
        newUsername: String,
        newPass: String,
        oldUsername: String,
        onProgress: ((String) -> Unit)? = null
    ): Pair<Boolean, String> {
        if (!isReadyForOcc()) return Pair(false, "Nextcloud server is not installed yet")

        val newClean = newUsername.trim()
        val oldClean = oldUsername.trim()

        try {
            executeOcc("config:app:set", "password_policy", "enforce_have_i_been_pwned", "--value=0")
            executeOcc("config:app:set", "password_policy", "enforce_common_passwords", "--value=0")
            executeOcc("config:app:set", "password_policy", "min_length", "--value=4")
        } catch (_: Exception) {}

        // Case 1: Same username - only update password or re-verify
        if (newClean.equals(oldClean, ignoreCase = true)) {
            onProgress?.invoke("Updating administrator password in Nextcloud...")
            val exists = isUserExists(newClean)
            if (!exists) {
                val created = createAdminUser(newClean, newPass)
                if (!created.first) return created
            } else {
                val reset = resetAdminPassword(newClean, newPass)
                if (!reset.first) return reset
            }
            executeOcc("group:adduser", "admin", newClean)
            ensureThemingAndAppstorePatches()
            purgeStaleWebSessions()
            return Pair(true, "")
        }

        // Case 2: Username is changing!
        // Step 1: Ensure new superadmin user is created with the new password
        onProgress?.invoke("Configuring new administrator '$newClean'...")
        val newExists = isUserExists(newClean)
        if (!newExists) {
            val created = createAdminUser(newClean, newPass)
            if (!created.first) {
                return created
            }
        } else {
            val reset = resetAdminPassword(newClean, newPass)
            if (!reset.first) {
                return reset
            }
            executeOcc("group:adduser", "admin", newClean)
        }

        // Step 2: Transfer files from old administrator to new administrator
        if (oldClean.isNotEmpty() && isUserExists(oldClean) && !oldClean.equals(newClean, ignoreCase = true)) {
            onProgress?.invoke("Transferring files from '$oldClean' to '$newClean'...")
            try {
                val transferOut = executeOcc("files:transfer-ownership", oldClean, newClean)
                Log.i(TAG, "Ownership transfer from $oldClean to $newClean: $transferOut")
            } catch (e: Exception) {
                Log.w(TAG, "File transfer ownership warning: ${e.message}")
            }

            // Step 3: Delete old administrator account so no ghost account remains!
            onProgress?.invoke("Removing previous administrator '$oldClean'...")
            val delOut = executeOcc("user:delete", oldClean)
            Log.i(TAG, "Deleted old administrator $oldClean: $delOut")
        }

        // Step 4: If the default 'admin' account still exists and is not newClean, purge it as well
        if (!newClean.equals("admin", ignoreCase = true) && isUserExists("admin")) {
            onProgress?.invoke("Purging leftover default 'admin' user...")
            try {
                executeOcc("files:transfer-ownership", "admin", newClean)
            } catch (_: Exception) {}
            val delAdminOut = executeOcc("user:delete", "admin")
            Log.i(TAG, "Purged default admin user: $delAdminOut")
        }

        // Step 5: Rescan new administrator directory
        onProgress?.invoke("Scanning files for '$newClean'...")
        try {
            executeOcc("files:scan", "--path=/$newClean/files")
        } catch (_: Exception) {}

        // Step 6: Invalidate active web sessions & clean orphaned auth tokens
        onProgress?.invoke("Invalidating stale sessions...")
        ensureThemingAndAppstorePatches()
        purgeStaleWebSessions()

        return Pair(true, "")
    }

    fun getTrustedDomains(): List<String> {
        val configFile = File(nextcloudDir, "config/config.php")
        if (configFile.exists()) {
            try {
                val text = configFile.readText()
                val match = Regex("'trusted_domains'\\s*=>\\s*array\\s*\\((.*?)\\)", RegexOption.DOT_MATCHES_ALL).find(text)
                if (match != null) {
                    val body = match.groupValues[1]
                    val domainRegex = Regex("=>\\s*['\"]([^'\"]+)['\"]")
                    val list = domainRegex.findAll(body).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.toList()
                    if (list.isNotEmpty()) return list
                }
            } catch (e: Exception) {
                Log.d(TAG, "Error reading config.php for trusted domains: ${e.message}")
            }
        }
        if (!isReadyForOcc()) {
            val list = mutableListOf("localhost", "127.0.0.1")
            getWifiIpAddress()?.let { list.add(it) }
            return list
        }
        val output = executeOcc("config:system:get", "trusted_domains")
        val list = output.lines().map { it.trim() }.filter {
            it.isNotEmpty() &&
            !it.startsWith("Warning", ignoreCase = true) &&
            !it.startsWith("Fatal", ignoreCase = true) &&
            !it.startsWith("Error", ignoreCase = true) &&
            !it.contains("Stack trace", ignoreCase = true) &&
            !it.contains("require_once", ignoreCase = true)
        }
        return if (list.isNotEmpty()) list else listOf("localhost", "127.0.0.1")
    }

    fun cleanDomainInput(raw: String): String {
        var domain = raw.trim()
        if (domain.contains("://")) {
            domain = domain.substringAfter("://")
        }
        if (domain.contains("/")) {
            domain = domain.substringBefore("/")
        }
        if (domain.contains(":")) {
            if (domain.startsWith("[") && domain.contains("]:")) {
                domain = domain.substringBeforeLast(":")
            } else if (!domain.startsWith("[") && domain.count { it == ':' } == 1) {
                domain = domain.substringBefore(":")
            }
        }
        return domain.trim().trimEnd('/').lowercase()
    }

    fun addTrustedDomain(domain: String): Boolean {
        val cleanDomain = cleanDomainInput(domain)
        if (cleanDomain.isEmpty()) return false
        val current = getTrustedDomains().toMutableList()
        if (current.contains(cleanDomain)) return true

        val nextIndex = current.size
        val output = executeOcc("config:system:set", "trusted_domains", nextIndex.toString(), "--value=$cleanDomain")
        Log.d(TAG, "Add trusted domain $cleanDomain: $output")
        return output.contains("set to", ignoreCase = true)
    }

    fun removeTrustedDomain(domain: String): Boolean {
        val cleanDomain = cleanDomainInput(domain)
        if (cleanDomain == "localhost" || cleanDomain == "127.0.0.1") {
            Log.w(TAG, "Cannot remove loopback domain: $cleanDomain")
            return false
        }
        val current = getTrustedDomains()
        val index = current.indexOf(cleanDomain)
        if (index == -1) return false
        val output = executeOcc("config:system:delete", "trusted_domains", index.toString())
        return output.contains("deleted", ignoreCase = true) || !getTrustedDomains().contains(cleanDomain)
    }

    fun getDataDirectory(): String {
        val configFile = File(nextcloudDir, "config/config.php")
        if (configFile.exists()) {
            try {
                val text = configFile.readText()
                val match = Regex("'datadirectory'\\s*=>\\s*['\"]([^'\"]+)['\"]").find(text)
                if (match != null) {
                    val path = match.groupValues[1].trim()
                    if (path.isNotEmpty() && !path.startsWith("Warning", ignoreCase = true) && !path.startsWith("Fatal", ignoreCase = true)) return path
                }
            } catch (e: Exception) {
                Log.d(TAG, "Error reading config.php for datadirectory: ${e.message}")
            }
        }
        val prefs = AppPreferences(context).sharedPreferences
        val savedDataDir = prefs.getString("data_dir", null)
        if (!savedDataDir.isNullOrBlank()) {
            return savedDataDir
        }
        if (!isReadyForOcc()) {
            return File(nextcloudDir, "data").absolutePath
        }
        val output = executeOcc("config:system:get", "datadirectory").trim()
        if (output.isNotEmpty() &&
            !output.startsWith("Error", ignoreCase = true) &&
            !output.startsWith("Warning", ignoreCase = true) &&
            !output.startsWith("Fatal", ignoreCase = true) &&
            !output.contains("require_once", ignoreCase = true)
        ) {
            return output
        }
        return File(nextcloudDir, "data").absolutePath
    }

    /**
     * Changes root data directory with optional atomic migration of the SQLite database
     * and user files, safeguarding against data loss and database decoupling.
     */
    fun migrateDataDirectory(
        oldPath: String,
        newPath: String,
        copyData: Boolean,
        onProgress: ((stage: String, percent: Int) -> Unit)? = null
    ): Boolean {
        val target = File(newPath)
        if (!target.exists()) {
            if (!target.mkdirs()) {
                Log.e(TAG, "Could not create target data directory: $newPath")
                return false
            }
        }

        val source = File(oldPath)

        // 1. Put Nextcloud into maintenance mode to safely freeze all write operations
        onProgress?.invoke("Activating Maintenance Mode...", 5)
        setMaintenanceMode(true)

        try {
            // 2. Ensure verification markers exist
            ensureNcMarkers(target)

            // 3. Migrate database and user files if requested
            if (copyData && source.exists() && source.absolutePath != target.absolutePath) {
                onProgress?.invoke("Calculating total data size...", 10)
                val totalBytes = calculateTotalBytes(source)
                val copiedBytes = LongArray(1) { 0L }
                copyDataFiles(source, target, totalBytes, copiedBytes, onProgress)
            } else if (!copyData && source.exists()) {
                // Non-migration / fresh installation mode:
                // Ensure the base database and system configuration exist in the new target so user accounts,
                // passwords, and app configurations remain identical, but user files remain fresh.
                onProgress?.invoke("Preserving system configuration & accounts...", 35)
                val srcDb = File(source, "nextcloud.db")
                val dstDb = File(target, "nextcloud.db")
                if (srcDb.exists() && !dstDb.exists()) {
                    copySingleFile(srcDb, dstDb, srcDb.length(), LongArray(1), null)
                }
                val wal = File(source, "nextcloud.db-wal")
                if (wal.exists()) {
                    copySingleFile(wal, File(target, "nextcloud.db-wal"), wal.length(), LongArray(1), null)
                }
                val shm = File(source, "nextcloud.db-shm")
                if (shm.exists()) {
                    copySingleFile(shm, File(target, "nextcloud.db-shm"), shm.length(), LongArray(1), null)
                }

                // Copy appdata configuration directory if present
                source.listFiles { f -> f.isDirectory && f.name.startsWith("appdata_") }?.forEach { appDataDir ->
                    val dstAppData = File(target, appDataDir.name)
                    if (!dstAppData.exists()) {
                        copyDirectoryRecursively(appDataDir, dstAppData, 0L, LongArray(1), null)
                    }
                }

                // Initialize clean user files directory
                val adminUser = AppPreferences(context).adminUser
                val userFilesDir = File(target, "$adminUser/files").apply { mkdirs() }
                val readme = File(userFilesDir, "Welcome.md")
                if (!readme.exists()) {
                    readme.writeText("# Nextcloud Storage\n\nThis storage folder was initialized as a fresh storage location.\n")
                }
            }

            // 4. Update Nextcloud configuration
            onProgress?.invoke("Updating Nextcloud storage pointer...", 85)
            executeOcc("config:system:set", "check_data_directory_permissions", "--value=false", "--type=boolean")
            val output = executeOcc("config:system:set", "datadirectory", "--value=$newPath")
            Log.i(TAG, "Set datadirectory: $output")
            val success = output.contains("set to", ignoreCase = true)

            // 5. Disable maintenance mode
            onProgress?.invoke("Resuming Nextcloud server...", 92)
            setMaintenanceMode(false)

            if (success) {
                if (!copyData) {
                    onProgress?.invoke("Cleaning up old file catalog entries...", 95)
                    executeOcc("files:cleanup")
                }
                // 6. Trigger background file scan so all files are indexed
                onProgress?.invoke("Indexing files in new storage...", 98)
                executeOcc("files:scan", "--all")
            }

            onProgress?.invoke("Storage configuration completed!", 100)
            return success
        } catch (e: Exception) {
            Log.e(TAG, "Error configuring data directory", e)
            setMaintenanceMode(false)
            return false
        }
    }

    /**
     * Backward-compatible simple setter.
     */
    fun setDataDirectory(newPath: String): Boolean {
        val current = getDataDirectory()
        return migrateDataDirectory(current, newPath, copyData = true, null)
    }

    private fun ensureNcMarkers(dir: File) {
        val ncData = File(dir, ".ncdata")
        if (!ncData.exists()) {
            try {
                ncData.writeText("# Nextcloud data directory\n# Do not change this file\n")
            } catch (e: Exception) {
                Log.w(TAG, "Could not write .ncdata", e)
            }
        }
        val ocData = File(dir, ".ocdata")
        if (!ocData.exists()) {
            try {
                ocData.createNewFile()
            } catch (e: Exception) {
                Log.w(TAG, "Could not write .ocdata", e)
            }
        }
        val indexHtml = File(dir, "index.html")
        if (!indexHtml.exists()) {
            try {
                indexHtml.createNewFile()
            } catch (_: Exception) {}
        }
        val htaccess = File(dir, ".htaccess")
        if (!htaccess.exists()) {
            try {
                htaccess.writeText("# Nextcloud data directory protection\nRequire all denied\n")
            } catch (_: Exception) {}
        }
    }

    private fun calculateTotalBytes(dir: File): Long {
        var total = 0L
        dir.listFiles()?.forEach { file ->
            total += if (file.isDirectory) calculateTotalBytes(file) else file.length()
        }
        return total
    }

    private fun copyDataFiles(
        source: File,
        target: File,
        totalBytes: Long,
        copiedBytes: LongArray,
        onProgress: ((String, Int) -> Unit)? = null
    ) {
        val files = source.listFiles() ?: return
        for (file in files) {
            val dest = File(target, file.name)
            if (file.isDirectory) {
                copyDirectoryRecursively(file, dest, totalBytes, copiedBytes, onProgress)
            } else {
                copySingleFile(file, dest, totalBytes, copiedBytes, onProgress)
            }
        }
    }

    private fun copyDirectoryRecursively(
        source: File,
        target: File,
        totalBytes: Long,
        copiedBytes: LongArray,
        onProgress: ((String, Int) -> Unit)? = null
    ) {
        if (!target.exists()) {
            if (!target.mkdirs() && target.absolutePath.startsWith("/storage/")) {
                SdRootMediaStoreBridge.mkdirOnSdRoot(context, target.absolutePath)
            }
        }
        source.listFiles()?.forEach { child ->
            val destChild = File(target, child.name)
            if (child.isDirectory) {
                copyDirectoryRecursively(child, destChild, totalBytes, copiedBytes, onProgress)
            } else {
                copySingleFile(child, destChild, totalBytes, copiedBytes, onProgress)
            }
        }
    }

    private fun copySingleFile(
        src: File,
        dest: File,
        totalBytes: Long,
        copiedBytes: LongArray,
        onProgress: ((String, Int) -> Unit)? = null
    ) {
        if (dest.exists() && dest.length() == src.length() && dest.lastModified() >= src.lastModified()) {
            copiedBytes[0] = copiedBytes[0] + src.length()
            return
        }

        // Ensure parent directory exists
        dest.parentFile?.let { p ->
            if (!p.exists()) {
                if (!p.mkdirs() && p.absolutePath.startsWith("/storage/")) {
                    SdRootMediaStoreBridge.mkdirOnSdRoot(context, p.absolutePath)
                }
            }
        }

        var copySuccess = false
        try {
            FileInputStream(src).use { inStream ->
                val outStream: OutputStream = if (dest.canWrite() || dest.parentFile?.canWrite() == true || !dest.absolutePath.startsWith("/storage/")) {
                    FileOutputStream(dest)
                } else {
                    val pfd = SdRootMediaStoreBridge.openRootWritablePfd(context, dest.absolutePath, "w")
                    if (pfd != null) {
                        ParcelFileDescriptor.AutoCloseOutputStream(pfd)
                    } else {
                        FileOutputStream(dest)
                    }
                }
                outStream.use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var bytesRead: Int
                    while (inStream.read(buffer).also { bytesRead = it } != -1) {
                        out.write(buffer, 0, bytesRead)
                        copiedBytes[0] = copiedBytes[0] + bytesRead
                        if (totalBytes > 0) {
                            val pct = ((copiedBytes[0] * 100) / totalBytes).toInt().coerceIn(10, 89)
                            onProgress?.invoke("Copying ${src.name} ($pct%)", pct)
                        }
                    }
                    out.flush()
                }
            }
            copySuccess = dest.exists() && dest.length() == src.length()
        } catch (e: Exception) {
            Log.w(TAG, "copySingleFile direct stream failed for ${src.name}: ${e.message}. Attempting bridge fallback...")
        }

        if (!copySuccess && dest.absolutePath.startsWith("/storage/")) {
            val bridgeOk = SdRootMediaStoreBridge.copyOnSdRoot(context, src.absolutePath, dest.absolutePath)
            if (bridgeOk) {
                copiedBytes[0] = copiedBytes[0] + src.length()
                return
            }
        }

        if (!copySuccess && !dest.exists()) {
            throw java.io.IOException("Failed to copy ${src.name} to ${dest.absolutePath}")
        }
    }

    fun getWifiIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Error resolving network interface IP: ${e.message}")
        }
        return null
    }

    fun executeOcc(vararg args: String, envVars: Map<String, String> = emptyMap()): String {
        val phpBin = BinaryHelper.getPhpBinary(context)
        val phpIni = File(filesDir, "conf/php.ini").absolutePath
        val occ = File(filesDir, "nextcloud/occ").absolutePath
        val tmpDir = File(filesDir, "tmp").apply { mkdirs() }
        val opensslCnf = File(filesDir, "conf/openssl.cnf")

        val isInstalling = args.firstOrNull() == "maintenance:install"
        if (!isReadyForOcc(isInstalling)) {
            Log.w(TAG, "PHP or occ/console.php not ready for command: ${args.joinToString(" ")}")
            return ""
        }
        ensurePatches()

        val command = mutableListOf(phpBin, "-c", phpIni, occ)
        command.addAll(args)

        return try {
            val pb = ProcessBuilder(command)
            pb.directory(filesDir)
            pb.redirectErrorStream(true)
            val env = pb.environment()
            if (opensslCnf.exists()) {
                env["OPENSSL_CONF"] = opensslCnf.absolutePath
            }
            env["TMPDIR"] = tmpDir.absolutePath
            val ldPath = BinaryHelper.getLdLibraryPath(context)
            if (!ldPath.isNullOrBlank()) {
                env["LD_LIBRARY_PATH"] = ldPath + (env["LD_LIBRARY_PATH"]?.let { ":$it" } ?: "")
            }
            for ((k, v) in envVars) {
                env[k] = v
            }

            val process = pb.start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            output
        } catch (e: Exception) {
            Log.e(TAG, "Error executing occ ${args.joinToString(" ")}", e)
            ""
        }
    }

    fun executePhpCode(code: String): String {
        val phpBin = BinaryHelper.getPhpBinary(context)
        val phpIni = File(filesDir, "conf/php.ini").absolutePath
        val tmpDir = File(filesDir, "tmp").apply { mkdirs() }
        val opensslCnf = File(filesDir, "conf/openssl.cnf")

        val command = listOf(phpBin, "-c", phpIni, "-r", code)

        return try {
            val pb = ProcessBuilder(command)
            pb.directory(filesDir)
            pb.redirectErrorStream(true)
            val env = pb.environment()
            if (opensslCnf.exists()) {
                env["OPENSSL_CONF"] = opensslCnf.absolutePath
            }
            env["TMPDIR"] = tmpDir.absolutePath
            val codeLdPath = BinaryHelper.getLdLibraryPath(context)
            if (!codeLdPath.isNullOrBlank()) {
                env["LD_LIBRARY_PATH"] = codeLdPath + (env["LD_LIBRARY_PATH"]?.let { ":$it" } ?: "")
            }

            val process = pb.start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            output
        } catch (e: Exception) {
            Log.e(TAG, "Error executing PHP code", e)
            ""
        }
    }

    fun purgeStaleWebSessions() {
        try {
            val tmpDir = File(filesDir, "tmp")
            if (tmpDir.exists()) {
                val sessions = tmpDir.listFiles { f -> f.name.startsWith("sess_") }
                sessions?.forEach { it.delete() }
                Log.i(TAG, "Purged ${sessions?.size ?: 0} stale PHP session files from ${tmpDir.absolutePath}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not purge session files: ${e.message}")
        }

        try {
            val dbFile = File(getDataDirectory(), "owncloud.db")
            if (dbFile.exists()) {
                val sqlitePhp = """
                    try {
                        ${'$'}db = new PDO('sqlite:${dbFile.absolutePath}');
                        ${'$'}db->exec("DELETE FROM oc_authtoken WHERE uid NOT IN (SELECT uid FROM oc_users)");
                    } catch (\Throwable ${'$'}e) {}
                """.trimIndent()
                executePhpCode(sqlitePhp)
                Log.i(TAG, "Purged orphaned oc_authtoken entries from owncloud.db")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not clean authtokens: ${e.message}")
        }
    }

    private fun ensurePatches() {
        try {
            val ocUtil = File(nextcloudDir, "lib/private/legacy/OC_Util.php")
            if (ocUtil.exists()) {
                val content = ocUtil.readText()
                if (!content.contains("// 'gd_info' => 'GD',") && content.contains("'gd_info' => 'GD',")) {
                    ocUtil.writeText(content.replace("'gd_info' => 'GD',", "// 'gd_info' => 'GD',"))
                    Log.i(TAG, "Patched OC_Util.php: removed hard requirement for gd_info.")
                }
            }

            val localPhp = File(nextcloudDir, "lib/private/Files/Storage/Local.php")
            if (localPhp.exists()) {
                var content = localPhp.readText()
                var modified = false
                if (content.contains("\$this->config->getSystemValueBool('localstorage.allowsymlinks', false)")) {
                    content = content.replace(
                        "\$this->config->getSystemValueBool('localstorage.allowsymlinks', false)",
                        "\$this->config->getSystemValueBool('localstorage.allowsymlinks', true)"
                    )
                    modified = true
                }
                if (!content.contains("COPY ") || !content.contains("private function bridgeOpenFile")) {
                    content = patchLocalPhpWithBridge(content)
                    modified = true
                }
                if (modified) {
                    localPhp.writeText(content)
                    Log.i(TAG, "Patched Local.php: storage bridge and permissions installed.")
                }
            }

            val hasher = File(nextcloudDir, "lib/private/Security/Hasher.php")
            if (hasher.exists()) {
                val content = hasher.readText()
                if (content.contains("PASSWORD_ARGON2ID => 3,")) {
                    val patched = content
                        .replace("PASSWORD_ARGON2ID => 3,", "'argon2id' => 3,")
                        .replace("PASSWORD_ARGON2I => 2,", "'argon2i' => 2,")
                    hasher.writeText(patched)
                    Log.i(TAG, "Patched Hasher.php: resolved undefined PASSWORD_ARGON2ID for bcrypt-only PHP runtime.")
                }
            }

            // 4. Install Nextcloud Web UI Storage Sidebar Widget
            val sidebarJs = File(nextcloudDir, "storage_sidebar.js")
            val jsScript = """
(function() {
    function findSidebarTarget() {
        // Nextcloud 28-35 Vue 3 files navigation settings footer
        const settingsUl = document.querySelector('ul.app-navigation-entry__settings');
        if (settingsUl && settingsUl.parentNode) {
            return { parent: settingsUl.parentNode, before: settingsUl };
        }
        // Quota navigation entry
        const quotaItem = document.querySelector('.app-navigation-entry__settings-quota, [data-cy-files-navigation-settings-quota]');
        if (quotaItem && quotaItem.parentNode) {
            return { parent: quotaItem.parentNode, before: quotaItem };
        }
        // Settings button entry
        const settingsBtn = document.querySelector('[data-cy-files-navigation-settings-button]');
        if (settingsBtn && settingsBtn.parentNode) {
            return { parent: settingsBtn.parentNode, before: settingsBtn };
        }
        // Legacy Nextcloud app-settings container
        const appSettings = document.getElementById('app-settings');
        if (appSettings && appSettings.parentNode) {
            return { parent: appSettings.parentNode, before: appSettings };
        }
        // General app navigation container
        const navContent = document.querySelector('.app-navigation__content, #app-navigation, nav.app-navigation');
        if (navContent) {
            return { parent: navContent, before: null };
        }
        return null;
    }

    function injectStorageWidget() {
        if (document.getElementById('nc-storage-sidebar-container')) return true;

        const target = findSidebarTarget();
        if (!target) return false;

        const container = document.createElement('div');
        container.id = 'nc-storage-sidebar-container';
        container.style.padding = '10px 14px 8px 14px';
        container.style.borderTop = '1px solid var(--color-border, #e0e0e0)';
        container.style.fontSize = '12px';
        container.style.color = 'var(--color-text-maxcontrast, #666)';
        container.style.width = '100%';
        container.style.boxSizing = 'border-box';

        const header = document.createElement('div');
        header.style.display = 'flex';
        header.style.justifyContent = 'space-between';
        header.style.alignItems = 'center';
        header.style.marginBottom = '8px';
        header.style.fontWeight = '600';
        header.style.letterSpacing = '0.5px';
        header.style.textTransform = 'uppercase';
        header.style.fontSize = '10.5px';
        header.style.color = 'var(--color-text-maxcontrast, #888)';
        header.innerHTML = '<span>Storage Volumes</span><span id="nc-storage-refresh" style="cursor:pointer; font-size:12px; padding: 2px;" title="Refresh storage stats">🔄</span>';
        container.appendChild(header);

        const list = document.createElement('div');
        list.id = 'nc-storage-drives-list';
        list.innerHTML = '<div style="opacity:0.6; padding: 4px 0;">Loading storages...</div>';
        container.appendChild(list);

        if (target.before) {
            target.parent.insertBefore(container, target.before);
        } else {
            target.parent.appendChild(container);
        }

        const refreshBtn = header.querySelector('#nc-storage-refresh');
        if (refreshBtn) {
            refreshBtn.addEventListener('click', function(e) {
                e.preventDefault();
                e.stopPropagation();
                refreshBtn.style.transform = 'rotate(180deg)';
                refreshBtn.style.transition = 'transform 0.3s ease';
                setTimeout(() => { refreshBtn.style.transform = 'none'; }, 300);
                loadStorageStats();
            });
        }

        loadStorageStats();
        return true;
    }

    function formatBytes(bytes) {
        if (!bytes || bytes <= 0) return '0 GB';
        const gb = bytes / (1024 * 1024 * 1024);
        return gb.toFixed(1) + ' GB';
    }

    function loadStorageStats() {
        const list = document.getElementById('nc-storage-drives-list');
        if (!list) return;

        fetch('/storage_stats.json?t=' + Date.now())
            .then(function(res) {
                if (!res.ok) throw new Error('HTTP ' + res.status);
                return res.json();
            })
            .then(function(drives) {
                if (!Array.isArray(drives) || drives.length === 0) {
                    list.innerHTML = '<div style="opacity:0.6; padding: 4px 0;">No active drives</div>';
                    return;
                }
                list.innerHTML = '';
                drives.forEach(function(drive) {
                    const driveRow = document.createElement('a');
                    const mountParam = encodeURIComponent(drive.mountPoint || drive.displayName);
                    driveRow.href = '/index.php/apps/files/?dir=/' + mountParam;
                    driveRow.style.display = 'block';
                    driveRow.style.textDecoration = 'none';
                    driveRow.style.color = 'inherit';
                    driveRow.style.padding = '6px 8px';
                    driveRow.style.borderRadius = '8px';
                    driveRow.style.marginBottom = '6px';
                    driveRow.style.transition = 'background-color 0.15s ease';
                    driveRow.style.backgroundColor = 'var(--color-background-hover, rgba(0,0,0,0.02))';
                    driveRow.onmouseenter = function() { driveRow.style.backgroundColor = 'var(--color-background-darker, rgba(0,0,0,0.06))'; };
                    driveRow.onmouseleave = function() { driveRow.style.backgroundColor = 'var(--color-background-hover, rgba(0,0,0,0.02))'; };

                    const used = drive.usedFormatted || formatBytes(drive.usedBytes);
                    const total = drive.totalFormatted || formatBytes(drive.totalBytes);
                    const free = drive.freeFormatted || formatBytes(drive.freeBytes);
                    const pct = Math.min(100, Math.max(0, drive.percent || 0));

                    const icon = (drive.id === 'primary_internal' || drive.displayName.toLowerCase().indexOf('phone') !== -1) 
                        ? '📱' 
                        : (drive.displayName.toLowerCase().indexOf('sd') !== -1 ? '💾' : '🔌');

                    const barColor = pct >= 90 ? '#e9322d' : (pct >= 80 ? '#e09b00' : 'var(--color-primary, #0082c9)');

                    driveRow.innerHTML = 
                        '<div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 4px;">' +
                            '<span style="font-weight: 600; color: var(--color-main-text, #222); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 125px;">' +
                                icon + ' ' + drive.displayName +
                            '</span>' +
                            '<span style="font-size: 11px; opacity: 0.9; font-weight: 600; color: var(--color-primary, #0082c9);">' +
                                free + ' free' +
                            '</span>' +
                        '</div>' +
                        '<div style="height: 6px; width: 100%; background: var(--color-border, #e4e4e4); border-radius: 3px; overflow: hidden; margin-bottom: 2px;">' +
                            '<div style="height: 100%; width: ' + pct + '%; background: ' + barColor + '; border-radius: 3px; transition: width 0.3s ease;"></div>' +
                        '</div>' +
                        '<div style="font-size: 10px; opacity: 0.7; display: flex; justify-content: space-between;">' +
                            '<span>' + used + ' of ' + total + '</span>' +
                            '<span>' + pct + '%</span>' +
                        '</div>';
                    list.appendChild(driveRow);
                });
            })
            .catch(function(err) {
                console.warn('[StorageSidebar] Could not load storage_stats.json:', err);
                list.innerHTML = '<div style="opacity:0.6; padding: 4px 0;">Storage stats unavailable</div>';
            });
    }

    let attempts = 0;
    const interval = setInterval(function() {
        attempts++;
        if (injectStorageWidget() || attempts > 30) {
            clearInterval(interval);
        }
    }, 400);

    const observer = new MutationObserver(function() {
        if (!document.getElementById('nc-storage-sidebar-container')) {
            injectStorageWidget();
        }
    });
    observer.observe(document.body, { childList: true, subtree: true });

    // Periodically refresh stats every 30 seconds
    setInterval(function() {
        if (document.getElementById('nc-storage-sidebar-container')) {
            loadStorageStats();
        }
    }, 30000);
})();
""".trimIndent()
            sidebarJs.writeText(jsScript)

            val layoutUser = File(nextcloudDir, "core/templates/layout.user.php")
            if (layoutUser.exists()) {
                val layoutContent = layoutUser.readText()
                if (!layoutContent.contains("storage_sidebar.js")) {
                    val scriptTag = """<script nonce="<?php p(${'$'}_['cspNonce']); ?>" src="/storage_sidebar.js" defer></script>"""
                    val patchedLayout = layoutContent.replace("</body>", "    $scriptTag\n</body>")
                    layoutUser.writeText(patchedLayout)
                    Log.i(TAG, "Patched layout.user.php: registered storage_sidebar.js with CSP nonce.")
                }
            }

            val cronServiceFile = File(nextcloudDir, "core/Service/CronService.php")
            if (cronServiceFile.exists()) {
                val content = cronServiceFile.readText()
                if (content.contains("if (\$user !== \$configUser) {")) {
                    cronServiceFile.writeText(content.replace("if (\$user !== \$configUser) {", "if (\$user !== \$configUser && \$user !== 0) {"))
                    Log.i(TAG, "Patched CronService.php: allowed root CLI execution.")
                }
            }

            val consoleFile = File(nextcloudDir, "console.php")
            if (consoleFile.exists()) {
                val content = consoleFile.readText()
                if (content.contains("if (\$user !== \$configUser) {")) {
                    consoleFile.writeText(content.replace("if (\$user !== \$configUser) {", "if (\$user !== \$configUser && \$user !== 0) {"))
                    Log.i(TAG, "Patched console.php: allowed root CLI execution.")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply core patches: ${e.message}")
        }
    }

    private fun patchLocalPhpWithBridge(input: String): String {
        var text = input

        // 1. mkdir delegation
        text = text.replace(
            "umask(\$oldMask);\n\t\treturn \$result;\n\t}\n\n\t#[\\Override]\n\tpublic function rmdir",
            "umask(\$oldMask);\n\t\tif (!\$result && str_starts_with(\$sourcePath, '/storage/')) {\n\t\t\t\$result = \$this->bridgeCommand(\"MKDIR \$sourcePath\\n\");\n\t\t}\n\t\treturn \$result;\n\t}\n\n\t#[\\Override]\n\tpublic function rmdir"
        )

        // 2. getMetaData permissions
        text = text.replace(
            "if (\$statPermissions & 0x0080) {",
            "\$isStorage = str_starts_with(\$stat['full_path'], '/storage/');\n\t\tif ((\$statPermissions & 0x0080) || \$isStorage) {"
        ).replace(
            "if (is_writable(\$parent)) {",
            "if (is_writable(\$parent) || str_starts_with(\$parent, '/storage/')) {"
        )

        // 3. isUpdatable, isCreatable, isDeletable
        text = text.replace(
            "public function isUpdatable(string \$path): bool {\n\t\treturn is_writable(\$this->getSourcePath(\$path));\n\t}",
            "public function isUpdatable(string \$path): bool {\n\t\t\$sourcePath = \$this->getSourcePath(\$path);\n\t\tif (str_starts_with(\$sourcePath, '/storage/')) {\n\t\t\treturn true;\n\t\t}\n\t\treturn is_writable(\$sourcePath);\n\t}\n\n\t#[\\Override]\n\tpublic function isCreatable(string \$path): bool {\n\t\t\$sourcePath = \$this->getSourcePath(\$path);\n\t\tif (str_starts_with(\$sourcePath, '/storage/')) {\n\t\t\treturn \$this->is_dir(\$path);\n\t\t}\n\t\treturn parent::isCreatable(\$path);\n\t}\n\n\t#[\\Override]\n\tpublic function isDeletable(string \$path): bool {\n\t\t\$sourcePath = \$this->getSourcePath(\$path);\n\t\tif (str_starts_with(\$sourcePath, '/storage/')) {\n\t\t\treturn true;\n\t\t}\n\t\treturn parent::isDeletable(\$path);\n\t}"
        )

        // 4. fopen delegation
        text = text.replace(
            "\$this->clearRealpathCache(\$sourcePath);\n\t\t\t\t\$result = @fopen(\$sourcePath, \$mode);\n\t\t\t}\n\t\t} finally {",
            "\$this->clearRealpathCache(\$sourcePath);\n\t\t\t\t\$result = @fopen(\$sourcePath, \$mode);\n\t\t\t}\n\t\t\tif (\$result === false && str_starts_with(\$sourcePath, '/storage/')) {\n\t\t\t\t\$result = \$this->bridgeOpenFile(\$sourcePath, \$mode);\n\t\t\t}\n\t\t} finally {"
        )

        // 5. file_put_contents delegation
        text = text.replace(
            "\$result = file_put_contents(\$this->getSourcePath(\$path), \$data);\n\t\tumask(\$oldMask);\n\t\treturn \$result;",
            "\$sourcePath = \$this->getSourcePath(\$path);\n\t\t\$result = @file_put_contents(\$sourcePath, \$data);\n\t\tif (\$result === false && str_starts_with(\$sourcePath, '/storage/')) {\n\t\t\t\$stream = \$this->bridgeOpenFile(\$sourcePath, 'w');\n\t\t\tif (\$stream) {\n\t\t\t\tif (is_resource(\$data)) {\n\t\t\t\t\t\$result = stream_copy_to_stream(\$data, \$stream);\n\t\t\t\t} else {\n\t\t\t\t\t\$result = fwrite(\$stream, \$data);\n\t\t\t\t}\n\t\t\t\tfclose(\$stream);\n\t\t\t}\n\t\t}\n\t\tumask(\$oldMask);\n\t\treturn \$result;"
        )

        // 6. rmdir delegation
        text = text.replace(
            "public function rmdir(string \$path): bool {\n\t\tif (!\$this->isDeletable(\$path)) {\n\t\t\treturn false;\n\t\t}\n\t\ttry {",
            "public function rmdir(string \$path): bool {\n\t\tif (!\$this->isDeletable(\$path)) {\n\t\t\treturn false;\n\t\t}\n\t\t\$sourcePath = \$this->getSourcePath(\$path);\n\t\tif (str_starts_with(\$sourcePath, '/storage/')) {\n\t\t\t\$delResult = \$this->bridgeCommand(\"DELETE \$sourcePath\\n\");\n\t\t\tclearstatcache(true, \$sourcePath);\n\t\t\treturn \$delResult;\n\t\t}\n\t\ttry {"
        )

        // 7. unlink delegation
        text = text.replace(
            "public function unlink(string \$path): bool {\n\t\tif (\$this->is_dir(\$path)) {\n\t\t\treturn \$this->rmdir(\$path);\n\t\t} elseif (\$this->is_file(\$path)) {\n\t\t\treturn unlink(\$this->getSourcePath(\$path));\n\t\t} else {\n\t\t\treturn false;\n\t\t}\n\t}",
            "public function unlink(string \$path): bool {\n\t\t\$sourcePath = \$this->getSourcePath(\$path);\n\t\tif (\$this->is_dir(\$path)) {\n\t\t\treturn \$this->rmdir(\$path);\n\t\t} elseif (\$this->is_file(\$path)) {\n\t\t\t\$result = @unlink(\$sourcePath);\n\t\t\tif (!\$result && str_starts_with(\$sourcePath, '/storage/')) {\n\t\t\t\t\$result = \$this->bridgeCommand(\"DELETE \$sourcePath\\n\");\n\t\t\t}\n\t\t\treturn \$result;\n\t\t} else {\n\t\t\tif (str_starts_with(\$sourcePath, '/storage/')) {\n\t\t\t\treturn \$this->bridgeCommand(\"DELETE \$sourcePath\\n\");\n\t\t\t}\n\t\t\treturn false;\n\t\t}\n\t}"
        )

        // 8. rename delegation
        text = text.replace(
            "if (@rename(\$absoluteSource, \$absoluteTarget)) {\n\t\t\tif (\$this->caseInsensitive) {\n\t\t\t\tif (mb_strtolower(\$target) === mb_strtolower(\$source) && !\$this->file_exists(\$target)) {\n\t\t\t\t\treturn false;\n\t\t\t\t}\n\t\t\t}\n\t\t\treturn true;\n\t\t} else {",
            "if (@rename(\$absoluteSource, \$absoluteTarget)) {\n\t\t\tif (\$this->caseInsensitive) {\n\t\t\t\tif (mb_strtolower(\$target) === mb_strtolower(\$source) && !\$this->file_exists(\$target)) {\n\t\t\t\t\treturn false;\n\t\t\t\t}\n\t\t\t}\n\t\t\treturn true;\n\t\t}\n\t\tif (str_starts_with(\$absoluteSource, '/storage/') || str_starts_with(\$absoluteTarget, '/storage/')) {\n\t\t\tif (\$this->bridgeCommand(\"RENAME \$absoluteSource \$absoluteTarget\\n\")) {\n\t\t\t\tclearstatcache(true, \$absoluteSource);\n\t\t\t\tclearstatcache(true, \$absoluteTarget);\n\t\t\t\treturn true;\n\t\t\t}\n\t\t} else {"
        )

        // 9. copy delegation
        val copyMethodNeedle = "public function copy(string \$source, string \$target): bool {"
        val scopeNeedle = "\t/** The scope this has to keep"
        val copyStart = text.indexOf(copyMethodNeedle)
        val copyEnd = if (copyStart != -1) text.indexOf(scopeNeedle, copyStart) else -1
        if (copyStart != -1 && copyEnd != -1) {
            val newCopyMethod = """public function copy(string ${'$'}source, string ${'$'}target): bool {
		${'$'}sourcePath = ${'$'}this->getSourcePath(${'$'}source);
		${'$'}targetPath = ${'$'}this->getSourcePath(${'$'}target);
		if (${'$'}this->is_dir(${'$'}source)) {
			if (str_starts_with(${'$'}targetPath, '/storage/') || str_starts_with(${'$'}sourcePath, '/storage/')) {
				if (${'$'}this->bridgeCommand("COPY ${'$'}sourcePath ${'$'}targetPath\n")) {
					clearstatcache(true, ${'$'}targetPath);
					return true;
				}
			}
			return parent::copy(${'$'}source, ${'$'}target);
		} else {
			${'$'}oldMask = umask(${'$'}this->defUMask);
			if (${'$'}this->unlinkOnTruncate) {
				${'$'}this->unlink(${'$'}target);
			}
			${'$'}result = @copy(${'$'}sourcePath, ${'$'}targetPath);
			if (!${'$'}result && (str_starts_with(${'$'}targetPath, '/storage/') || str_starts_with(${'$'}sourcePath, '/storage/'))) {
				${'$'}result = ${'$'}this->bridgeCommand("COPY ${'$'}sourcePath ${'$'}targetPath\n");
				clearstatcache(true, ${'$'}targetPath);
			}
			umask(${'$'}oldMask);
			if (${'$'}this->caseInsensitive) {
				if (mb_strtolower(${'$'}target) === mb_strtolower(${'$'}source) && !${'$'}this->file_exists(${'$'}target)) {
					return false;
				}
			}
			return ${'$'}result;
		}
	}

"""
            text = text.substring(0, copyStart) + newCopyMethod + text.substring(copyEnd)
        }

        // 10. Bridge helper methods before closing brace
        if (!text.contains("private function bridgeOpenFile")) {
            val closingBraceIdx = text.lastIndexOf('}')
            if (closingBraceIdx != -1) {
                val bridgeHelpers = """
	private function bridgeOpenFile(string ${'$'}sourcePath, string ${'$'}mode) {
		${'$'}sock = @socket_create(AF_UNIX, SOCK_STREAM, 0);
		if (!${'$'}sock) return false;
		if (!@socket_connect(${'$'}sock, "\0com.nextcloud.server.saf_bridge")) {
			@socket_close(${'$'}sock);
			return false;
		}
		${'$'}bridgeMode = (str_contains(${'$'}mode, 'w') || str_contains(${'$'}mode, 'a') || str_contains(${'$'}mode, '+')) ? 'w' : 'r';
		${'$'}cmd = "OPEN ${'$'}bridgeMode ${'$'}sourcePath\n";
		@socket_write(${'$'}sock, ${'$'}cmd, strlen(${'$'}cmd));

		${'$'}controllen = function_exists('socket_cmsg_space') ? socket_cmsg_space(SOL_SOCKET, SCM_RIGHTS, 1) : 32;
		${'$'}msg = [
			'name' => [],
			'buffer_size' => 1024,
			'controllen' => ${'$'}controllen,
		];
		${'$'}bytes = @socket_recvmsg(${'$'}sock, ${'$'}msg, 0);
		@socket_close(${'$'}sock);

		if (!empty(${'$'}msg['control'][0]['data'][0])) {
			${'$'}res = ${'$'}msg['control'][0]['data'][0];
			if (is_object(${'$'}res) && function_exists('socket_export_stream')) {
				return @socket_export_stream(${'$'}res);
			}
			return ${'$'}res;
		}
		return false;
	}

	private function bridgeCommand(string ${'$'}cmd): bool {
		${'$'}sock = @socket_create(AF_UNIX, SOCK_STREAM, 0);
		if (!${'$'}sock) return false;
		if (!@socket_connect(${'$'}sock, "\0com.nextcloud.server.saf_bridge")) {
			@socket_close(${'$'}sock);
			return false;
		}
		@socket_write(${'$'}sock, ${'$'}cmd, strlen(${'$'}cmd));
		${'$'}resp = '';
		${'$'}bytes = @socket_recv(${'$'}sock, ${'$'}resp, 1, 0);
		@socket_close(${'$'}sock);
		return (${'$'}bytes > 0 && ord(${'$'}resp[0]) === 1);
	}
}
"""
                text = text.substring(0, closingBraceIdx) + bridgeHelpers
            }
        }

        return text
    }
}

