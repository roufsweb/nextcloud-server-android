package com.nextcloud.server

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONArray
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Manages automatic hot-plug detection, multi-drive routing, and mounting of
 * external storage media (MicroSD cards, USB OTG flash drives, external HDDs, NVMe SSDs)
 * formatted as FAT32, exFAT, NTFS, or ext4 into Nextcloud via files_external.
 *
 * Supported across Android 8.0 (API 26) through Android 15 (API 35).
 */
class StorageMountManager(private val context: Context) {

    companion object {
        private const val TAG = "StorageMountManager"
        const val ACTION_STORAGE_CHANGED = "com.nextcloud.server.STORAGE_CHANGED"
        const val ACTION_USB_DEVICE_ATTACHED = "android.hardware.usb.action.USB_DEVICE_ATTACHED"
        const val ACTION_USB_DEVICE_DETACHED = "android.hardware.usb.action.USB_DEVICE_DETACHED"

        internal fun parseFilesystemTypeFromMountLine(line: String): String {
            val lower = line.lowercase()
            return when {
                lower.contains("vfat") || lower.contains("fat32") || lower.contains("msdos") -> "FAT32"
                lower.contains("exfat") || lower.contains("fs=exfat") -> "exFAT"
                lower.contains("ntfs") || lower.contains("ntfs3") || lower.contains("fuseblk") -> "NTFS"
                lower.contains("ext4") -> "ext4"
                lower.contains("f2fs") -> "F2FS"
                lower.contains("sdfat") -> if (lower.contains("fs=exfat")) "exFAT" else "FAT32"
                else -> "FAT32"
            }
        }

        internal fun isSdCardClassification(
            desc: String,
            volId: String?,
            mountLines: List<String> = emptyList(),
            voldEntries: List<String> = emptyList(),
            uuid: String = ""
        ): Boolean {
            val lowerDesc = desc.lowercase()
            if (lowerDesc.contains("usb") || lowerDesc.contains("otg")) {
                return false
            }

            if (volId != null) {
                val lowerVol = volId.lowercase()
                if (lowerVol.contains("179,") || lowerVol.contains("mmc")) {
                    return true
                }
                if (lowerVol.contains("8,") || lowerVol.contains("7,") || lowerVol.contains("loop")) {
                    return false
                }
            }

            for (line in mountLines) {
                if (uuid.isEmpty() || line.contains(uuid, ignoreCase = true)) {
                    val lowerLine = line.lowercase()
                    if (lowerLine.contains("public:179") || lowerLine.contains("mmcblk") || lowerLine.contains("/dev/block/mmc")) {
                        return true
                    }
                    if (lowerLine.contains("public:8") || lowerLine.contains("public:7") || lowerLine.contains("/dev/block/sd") || lowerLine.contains("loop")) {
                        return false
                    }
                }
            }

            if (voldEntries.any { it.startsWith("public:179") && (uuid.isEmpty() || it.contains(uuid, ignoreCase = true)) }) {
                return true
            }

            return lowerDesc.contains("sd") || lowerDesc.contains("card") || lowerDesc.contains("micro") || lowerDesc.contains("tf")
        }
    }

    private val scheduledExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private var pendingDebounceTask: ScheduledFuture<*>? = null

    private val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
    private val nextcloudManager = NextcloudManager(context)

    // Maps volume UUID/ID to Mount ID in Nextcloud
    private val activeMounts = ConcurrentHashMap<String, Int>()
    private val activeMountPoints = ConcurrentHashMap<String, String>() // UUID -> MountPoint
    private var mediaReceiver: BroadcastReceiver? = null
    private var usbReceiver: BroadcastReceiver? = null

    data class DetectedStorage(
        val id: String,
        val label: String,
        val displayName: String,
        val nextcloudMountPoint: String,
        val path: String,
        val displayPath: String = path,
        val isFullDrive: Boolean,
        val isWritable: Boolean,
        val fsType: String,
        val totalBytes: Long,
        val freeBytes: Long,
        val hasSafAccess: Boolean = false,
        val isReadOnlyFs: Boolean = false,
        val isSdCard: Boolean = false
    )

    private data class ExistingMount(
        val id: Int,
        val mountPoint: String,
        val datadir: String
    )

    fun isStorageEnabled(storageId: String): Boolean {
        val appPrefs = AppPreferences(context)
        val prefs = appPrefs.sharedPreferences
        val baseId = storageId.removeSuffix("_media").removeSuffix("_root").removeSuffix("_uploads")
        if (baseId == "primary_internal") {
            return appPrefs.isInternalStorageMountEnabled && prefs.getBoolean("ext_storage_enabled_primary_internal", true)
        }
        return prefs.getBoolean("ext_storage_enabled_$baseId", true)
    }

    fun setStorageEnabled(storageId: String, enabled: Boolean) {
        val appPrefs = AppPreferences(context)
        val prefs = appPrefs.sharedPreferences
        val baseId = storageId.removeSuffix("_media").removeSuffix("_root").removeSuffix("_uploads")
        if (baseId == "primary_internal") {
            appPrefs.isInternalStorageMountEnabled = enabled
        }
        prefs.edit().putBoolean("ext_storage_enabled_$baseId", enabled).apply()
        scheduleDebouncedSync()
    }

    fun startMonitoring() {
        Log.i(TAG, "Starting storage & USB hot-plug monitoring")

        // Initial synchronization
        scheduleDebouncedSync()

        // 1. Universal Media Broadcast Receiver (Requires "file" scheme)
        val mediaFilter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addDataScheme("file")
        }

        val mReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val action = intent?.action ?: ""
                Log.d(TAG, "Media broadcast received: $action data=${intent?.data}")
                val isRemoval = action == Intent.ACTION_MEDIA_UNMOUNTED ||
                                action == Intent.ACTION_MEDIA_EJECT ||
                                action == Intent.ACTION_MEDIA_REMOVED ||
                                action == Intent.ACTION_MEDIA_BAD_REMOVAL
                scheduleDebouncedSync(isRemoval)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(mReceiver, mediaFilter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(mReceiver, mediaFilter)
        }
        mediaReceiver = mReceiver

        // 2. Hardware USB Device & Storage Volume State Receiver (No scheme)
        val usbFilter = IntentFilter().apply {
            addAction(ACTION_USB_DEVICE_ATTACHED)
            addAction(ACTION_USB_DEVICE_DETACHED)
            addAction("android.os.storage.action.VOLUME_STATE_CHANGED")
            addAction(Intent.ACTION_MEDIA_SCANNER_STARTED)
            addAction(Intent.ACTION_MEDIA_SCANNER_FINISHED)
        }

        val uReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val action = intent?.action ?: ""
                Log.d(TAG, "USB/Hardware volume broadcast received: $action")
                val isRemoval = action == ACTION_USB_DEVICE_DETACHED
                scheduleDebouncedSync(isRemoval)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(uReceiver, usbFilter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(uReceiver, usbFilter)
        }
        usbReceiver = uReceiver
    }

    fun stopMonitoring() {
        Log.i(TAG, "Stopping storage hot-plug monitoring")
        mediaReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering mediaReceiver: ${e.message}")
            }
            mediaReceiver = null
        }
        usbReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering usbReceiver: ${e.message}")
            }
            usbReceiver = null
        }
        pendingDebounceTask?.cancel(false)
    }

    @Synchronized
    private fun scheduleDebouncedSync(isRemoval: Boolean = false) {
        pendingDebounceTask?.cancel(false)
        val delay = if (isRemoval) 150L else 500L
        pendingDebounceTask = scheduledExecutor.schedule({
            syncConnectedStorages()
        }, delay, TimeUnit.MILLISECONDS)

        // For newly attached USB/SD drives, kernel/vold filesystem mounting can take up to 2 seconds
        if (!isRemoval) {
            scheduledExecutor.schedule({
                syncConnectedStorages()
            }, 2500, TimeUnit.MILLISECONDS)
        }
    }

    /**
     * Scans all connected storage drives (FAT32, exFAT, NTFS, ext4) and synchronizes
     * them with Nextcloud files_external backend with zero duplicates.
     */
    fun syncConnectedStorages() {
        try {
            if (!nextcloudManager.isInstalled()) {
                Log.d(TAG, "Nextcloud is not installed yet; skipping OCC external storage sync.")
                val changeIntent = Intent(ACTION_STORAGE_CHANGED)
                context.sendBroadcast(changeIntent)
                return
            }

            enableFilesExternal()

            val currentStorages = getRemovableStorages()
            Log.i(TAG, "Detected ${currentStorages.size} removable storage drive(s)")

            val currentDataDir = nextcloudManager.getDataDirectory()
            val existingMounts = getExistingNextcloudMounts()

            // Deduplicate mounts in Nextcloud
            val seenMountPoints = mutableSetOf<String>()
            val validExistingMounts = mutableListOf<ExistingMount>()

            for (m in existingMounts) {
                if (seenMountPoints.contains(m.mountPoint)) {
                    Log.i(TAG, "Deleting duplicate mount in Nextcloud: id=${m.id}, point=${m.mountPoint}")
                    removeNextcloudMount(m.id)
                } else {
                    seenMountPoints.add(m.mountPoint)
                    validExistingMounts.add(m)
                }
            }

            // 1. Unmount any removed drives & inspect for Root Storage Disconnect
            var anyUnmounted = false
            for (em in validExistingMounts) {
                // A drive is STILL connected only if an actively detected storage matches this datadir or mountPoint,
                // and the underlying storage path physically exists right now on the filesystem.
                val matchingStorage = currentStorages.firstOrNull {
                    (it.path == em.datadir || it.nextcloudMountPoint == em.mountPoint) && File(it.path).exists()
                }

                if (matchingStorage == null || !File(em.datadir).exists()) {
                    Log.i(TAG, "Unmounting detached drive from Nextcloud: id=${em.id}, point=${em.mountPoint}, datadir=${em.datadir}")
                    removeNextcloudMount(em.id)
                    val keysToRemove = activeMountPoints.filter { it.value == em.mountPoint }.keys
                    for (k in keysToRemove) {
                        activeMounts.remove(k)
                        activeMountPoints.remove(k)
                    }
                    activeMounts.remove(em.mountPoint)
                    activeMountPoints.remove(em.mountPoint)
                    anyUnmounted = true
                }
            }



            // Root Storage Disconnect Sentinel
            val currentStorageIds = currentStorages.map { it.id }.toSet()
            if (currentDataDir.contains("/storage/") && !currentDataDir.contains("/storage/emulated/")) {
                val isRootConnected = currentStorageIds.any { currentDataDir.contains(it) }
                if (!isRootConnected && !File(currentDataDir).exists()) {
                    Log.w(TAG, "CRITICAL: The active root data storage drive was disconnected!")
                    nextcloudManager.setMaintenanceMode(true)

                    val intent = Intent("com.nextcloud.server.STATUS_UPDATE").apply {
                        setPackage(context.packageName)
                        putExtra("status", "Root storage missing (Drive disconnected). Server paused.")
                        putExtra("storage_disconnected", true)
                        putExtra("disconnected_path", currentDataDir)
                    }
                    context.sendBroadcast(intent)
                }
            }

            // 2. Reconcile and Mount newly discovered drives
            for (storage in currentStorages) {
                val isEnabled = isStorageEnabled(storage.id)
                val existing = validExistingMounts.firstOrNull {
                    it.mountPoint == storage.nextcloudMountPoint
                }

                if (!isEnabled) {
                    if (existing != null) {
                        Log.i(TAG, "Unmounting disabled drive from Nextcloud: ${storage.displayName} (ID=${existing.id})")
                        removeNextcloudMount(existing.id)
                        activeMounts.remove(storage.id)
                        activeMountPoints.remove(storage.id)
                        anyUnmounted = true
                    }
                    continue
                }

                if (existing != null) {
                    if (existing.datadir != storage.path) {
                        Log.i(TAG, "Updating mount ${storage.nextcloudMountPoint}: datadir changed from ${existing.datadir} to ${storage.path}")
                        removeNextcloudMount(existing.id)
                        val mountId = addNextcloudMount(storage.nextcloudMountPoint, storage.path)
                        if (mountId != null) {
                            activeMounts[storage.id] = mountId
                            activeMountPoints[storage.id] = storage.nextcloudMountPoint
                            scanNextcloudMount(storage.nextcloudMountPoint)
                        }
                    } else {
                        activeMounts[storage.id] = existing.id
                        activeMountPoints[storage.id] = storage.nextcloudMountPoint
                        Log.d(TAG, "Reconciled existing mount: ${storage.displayName} (ID=${existing.id})")
                    }
                } else {
                    val targetDir = File(storage.path)
                    if (!targetDir.exists()) {
                        try { targetDir.mkdirs() } catch (_: Exception) {}
                    }
                    Log.i(TAG, "Creating new Nextcloud mount: ${storage.nextcloudMountPoint} -> ${storage.path} (${storage.fsType})")
                    val mountId = addNextcloudMount(storage.nextcloudMountPoint, storage.path)
                    if (mountId != null) {
                        activeMounts[storage.id] = mountId
                        activeMountPoints[storage.id] = storage.nextcloudMountPoint
                        // Trigger background file scan so files show up immediately in Nextcloud UI
                        scanNextcloudMount(storage.nextcloudMountPoint)
                    }
                }
            }

            if (anyUnmounted) {
                // Purge file cache for detached or disabled mounts so Nextcloud Web UI immediately removes the folder!
                executeOcc("files:cleanup")
                val adminUser = AppPreferences(context).adminUser
                executeOcc("files:scan", "--path=/$adminUser/files")
                if (adminUser != "admin") {
                    executeOcc("files:scan", "--path=/admin/files")
                }
            }
            nextcloudManager.purgeAllDefaultDemoFiles()

            // 3. Notify UI of storage changes
            val changeIntent = Intent(ACTION_STORAGE_CHANGED).apply {
                setPackage(context.packageName)
            }
            context.sendBroadcast(changeIntent)

            // 4. Export storage metrics for Nextcloud Web UI Sidebar
            exportStorageStatsJson(currentStorages)

        } catch (e: Exception) {
            Log.e(TAG, "Error synchronizing storage drives", e)
        }
    }

    /**
     * Exports current active storage drive metrics to storage_stats.json in Nextcloud web root
     * so that the Web UI sidebar widget can display live storage capacity above Files settings.
     */
    fun exportStorageStatsJson(storages: List<DetectedStorage>) {
        try {
            val jsonArray = JSONArray()
            for (s in storages) {
                if (!isStorageEnabled(s.id)) continue
                val total = s.totalBytes
                val free = s.freeBytes
                val used = if (total > free) total - free else 0L
                val totalGb = total / (1024.0 * 1024.0 * 1024.0)
                val usedGb = used / (1024.0 * 1024.0 * 1024.0)
                val freeGb = free / (1024.0 * 1024.0 * 1024.0)
                val pct = if (total > 0) ((used.toDouble() / total.toDouble()) * 100).toInt().coerceIn(0, 100) else 0

                val obj = org.json.JSONObject().apply {
                    put("id", s.id)
                    put("displayName", s.displayName)
                    put("mountPoint", s.nextcloudMountPoint)
                    put("path", s.path)
                    put("usedBytes", used)
                    put("totalBytes", total)
                    put("freeBytes", free)
                    put("usedFormatted", String.format(java.util.Locale.US, "%.1f GB", usedGb))
                    put("totalFormatted", String.format(java.util.Locale.US, "%.1f GB", totalGb))
                    put("freeFormatted", String.format(java.util.Locale.US, "%.1f GB", freeGb))
                    put("percent", pct)
                }
                jsonArray.put(obj)
            }

            val targetFile = File(context.filesDir, "nextcloud/storage_stats.json")
            targetFile.writeText(jsonArray.toString(2))
            Log.i(TAG, "Exported storage_stats.json with ${jsonArray.length()} active storage volume(s)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export storage_stats.json", e)
        }
    }

    /**
     * Discovers all removable storages across Android versions (8 through 15).
     * Tests write access (full root vs app-scoped), resolves filesystem types (FAT32/exFAT/NTFS/ext4),
     * and assigns collision-free, human-readable display names for multiple drives.
     */
    fun getRemovableStorages(): List<DetectedStorage> {
        val result = mutableListOf<DetectedStorage>()
        val volumes = storageManager.storageVolumes
        val externalDirs = ContextCompat.getExternalFilesDirs(context, null)

        var sdCardCount = 0
        var usbCount = 0
        var ssdCount = 0
        val processedIds = mutableSetOf<String>()

        // Pass 0: Primary Internal Shared Storage (/storage/emulated/0)
        val appPrefs = AppPreferences(context)
        if (appPrefs.isInternalStorageMountEnabled) {
            val primaryDir = Environment.getExternalStorageDirectory()
            if (primaryDir != null && primaryDir.exists()) {
                val total = primaryDir.totalSpace
                val free = primaryDir.freeSpace
                val isWritable = isDirectoryWritable(primaryDir) || hasExternalStoragePermission()
                processedIds.add("primary_internal")
                result.add(
                    DetectedStorage(
                        id = "primary_internal",
                        label = "Internal Storage",
                        displayName = "Phone Storage",
                        nextcloudMountPoint = "Phone Storage",
                        path = primaryDir.absolutePath,
                        displayPath = primaryDir.absolutePath,
                        isFullDrive = true,
                        isWritable = isWritable,
                        fsType = "ext4/F2FS",
                        totalBytes = total,
                        freeBytes = free,
                        hasSafAccess = true,
                        isReadOnlyFs = false,
                        isSdCard = false
                    )
                )
            }
        }

        // Pass 1: Discover via StorageManager.storageVolumes (Universal Android API 24+)
        for (volume in volumes) {
            if (volume.isRemovable) {
                val state = volume.state
                if (state == Environment.MEDIA_MOUNTED || state == Environment.MEDIA_MOUNTED_READ_ONLY) {
                    val rawUuid = volume.uuid ?: "ext_${volume.hashCode().toString(16).takeLast(4)}"
                    if (processedIds.contains(rawUuid)) continue
                    processedIds.add(rawUuid)

                    val desc = volume.getDescription(context) ?: "External Storage"

                    // Filesystem Detection (FAT32, exFAT, NTFS, ext4)
                    val fsType = getFilesystemType(rawUuid)

                    // Hardware-accurate classification: MMC/SD vs NVMe/SSD vs USB
                    val isSdCard = isSdCardVolume(volume, rawUuid, desc)
                    val isSsd = isSsdVolume(desc, rawUuid)

                    // Determine user-friendly label
                    val customLabel = if (
                        desc.isNotBlank() &&
                        !desc.equals("External Drive", ignoreCase = true) &&
                        !desc.equals("External Storage", ignoreCase = true) &&
                        !desc.equals("SD card", ignoreCase = true) &&
                        !desc.equals("USB drive", ignoreCase = true) &&
                        !desc.equals(rawUuid, ignoreCase = true)
                    ) desc.trim() else ""

                    // Collision-free naming
                    val displayName = when {
                        isSdCard -> {
                            sdCardCount++
                            if (sdCardCount == 1) "SD Card" else "SD Card $sdCardCount"
                        }
                        isSsd -> {
                            ssdCount++
                            if (ssdCount == 1) "External SSD" else "External SSD $ssdCount"
                        }
                        else -> {
                            usbCount++
                            if (usbCount == 1) "USB Drive" else "USB Drive $usbCount"
                        }
                    }

                    // Resolve mount path
                    var targetPath: String? = null
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        targetPath = volume.directory?.absolutePath
                    }
                    if (targetPath == null) {
                        try {
                            val method = volume.javaClass.getMethod("getPath")
                            targetPath = method.invoke(volume) as? String
                        } catch (e: Exception) {
                            Log.d(TAG, "getPath reflection failed: ${e.message}")
                        }
                    }
                    if (targetPath == null || targetPath.startsWith("/mnt/media_rw") || targetPath.startsWith("/mnt/runtime")) {
                        targetPath = "/storage/$rawUuid"
                    }
                    if (targetPath.startsWith("/mnt/media_rw/")) {
                        targetPath = targetPath.replace("/mnt/media_rw/", "/storage/")
                    }
                    if (!File(targetPath).exists() && File("/storage/$rawUuid").exists()) {
                        targetPath = "/storage/$rawUuid"
                    }

                    // Determine optimal mount directory:
                    // When storage permission is granted (or drive root is accessible), mount the bare drive root (/storage/<UUID>)
                    // This gives users immediate zero-picker access to all existing media/files on the card across Android 8-17.
                    val driveRootDir = File(targetPath)

                    val safManager = SdCardSafManager(context)
                    val hasSaf = safManager.hasSafAccess(rawUuid)
                    val treeUri = if (hasSaf) safManager.getSafUri(rawUuid) else null

                    // If user granted access to a subfolder (e.g. Nextcloud), mount that subtree
                    var effectiveDriveDir = driveRootDir
                    if (treeUri != null) {
                        try {
                            val treeDocId = android.provider.DocumentsContract.getTreeDocumentId(treeUri)
                            if (treeDocId.contains(":")) {
                                val sub = treeDocId.substringAfter(":").trim('/')
                                if (sub.isNotEmpty()) {
                                    val subDir = File(driveRootDir, sub)
                                    if (subDir.exists() || subDir.mkdirs()) {
                                        effectiveDriveDir = subDir
                                    }
                                }
                            }
                        } catch (_: Exception) {}
                    }

                    val isReadOnlyFs = state == Environment.MEDIA_MOUNTED_READ_ONLY || fsType.contains("NTFS", ignoreCase = true)
                    val canPosixWrite = try { effectiveDriveDir.canWrite() } catch (_: Exception) { false }
                    val isActualWritable = !isReadOnlyFs && (canPosixWrite || hasSaf)

                    val (chosenDir, isFullDrive) = when {
                        effectiveDriveDir.exists() -> {
                            Log.i(TAG, "Using physical root for $displayName: ${effectiveDriveDir.absolutePath}")
                            Pair(effectiveDriveDir, true)
                        }
                        else -> {
                            val matchingAppDir = externalDirs.firstOrNull { it != null && it.absolutePath.contains(rawUuid, ignoreCase = true) }
                            val appScopedDir = matchingAppDir?.let { File(it, "Nextcloud") }
                                ?: File("/storage/$rawUuid/Android/data/${context.packageName}/files/Nextcloud")
                            if (!appScopedDir.exists()) {
                                try { appScopedDir.mkdirs() } catch (_: Exception) {}
                            }
                            Log.i(TAG, "Using dedicated zero-popup physical volume storage for $displayName: ${appScopedDir.absolutePath}")
                            Pair(appScopedDir, false)
                        }
                    }
                    ensureDirectoryPermissions(chosenDir)

                    val rootFileObj = File(targetPath)
                    val total = if (rootFileObj.totalSpace > 0) rootFileObj.totalSpace else chosenDir.totalSpace
                    val free = if (rootFileObj.freeSpace > 0) rootFileObj.freeSpace else chosenDir.freeSpace

                    if (rootFileObj.exists()) {
                        result.add(
                            DetectedStorage(
                                id = rawUuid,
                                label = customLabel,
                                displayName = displayName,
                                nextcloudMountPoint = displayName,
                                path = chosenDir.absolutePath,
                                displayPath = chosenDir.absolutePath,
                                isFullDrive = isFullDrive,
                                isWritable = isActualWritable,
                                fsType = fsType,
                                totalBytes = total,
                                freeBytes = free,
                                hasSafAccess = hasSaf,
                                isReadOnlyFs = isReadOnlyFs,
                                isSdCard = isSdCard
                            )
                        )
                    }
                }
            }
        }

        // Pass 2: Fallback for any secondary external dirs not captured in storageVolumes
        for (dir in externalDirs) {
            if (dir != null) {
                val abs = dir.absolutePath
                if (abs.startsWith("/storage/") && !abs.startsWith("/storage/emulated/")) {
                    val parts = abs.split("/")
                    if (parts.size >= 3) {
                        val uuid = parts[2]
                        if (!processedIds.contains(uuid)) {
                            processedIds.add(uuid)
                            val isSdCard = isSdCardVolume(null, uuid, "")
                            val displayName = if (isSdCard) {
                                sdCardCount++
                                if (sdCardCount == 1) "SD Card" else "SD Card $sdCardCount"
                            } else {
                                usbCount++
                                if (usbCount == 1) "USB Drive" else "USB Drive $usbCount"
                            }

                            val fsType = getFilesystemType(uuid)
                            val targetPath = "/storage/$uuid"
                            val driveRootDir = File(targetPath)

                            val safManager = SdCardSafManager(context)
                            val hasSaf = safManager.hasSafAccess(uuid)
                            val treeUri = if (hasSaf) safManager.getSafUri(uuid) else null

                            var effectiveDriveDir = driveRootDir
                            if (treeUri != null) {
                                try {
                                    val treeDocId = android.provider.DocumentsContract.getTreeDocumentId(treeUri)
                                    if (treeDocId.contains(":")) {
                                        val sub = treeDocId.substringAfter(":").trim('/')
                                        if (sub.isNotEmpty()) {
                                            val subDir = File(driveRootDir, sub)
                                            if (subDir.exists() || subDir.mkdirs()) {
                                                effectiveDriveDir = subDir
                                            }
                                        }
                                    }
                                } catch (_: Exception) {}
                            }

                            val isReadOnlyFs = fsType.contains("NTFS", ignoreCase = true)

                            val (chosenDir, isFullDrive) = when {
                                effectiveDriveDir.exists() -> {
                                    Log.i(TAG, "Using physical root for $displayName: ${effectiveDriveDir.absolutePath}")
                                    Pair(effectiveDriveDir, true)
                                }
                                else -> {
                                    val appDir = File(dir, "Nextcloud")
                                    if (!appDir.exists()) {
                                        try { appDir.mkdirs() } catch (_: Exception) {}
                                    }
                                    Log.i(TAG, "Using dedicated zero-popup physical volume storage for $displayName: ${appDir.absolutePath}")
                                    Pair(appDir, false)
                                }
                            }
                            ensureDirectoryPermissions(chosenDir)

                            val total = if (driveRootDir.totalSpace > 0) driveRootDir.totalSpace else chosenDir.totalSpace
                            val free = if (driveRootDir.freeSpace > 0) driveRootDir.freeSpace else chosenDir.freeSpace

                            val hasSaf2 = safManager.hasSafAccess(uuid)
                            val canPosixWrite2 = try { driveRootDir.canWrite() } catch (_: Exception) { false }
                            val isActualWritable2 = !isReadOnlyFs && (canPosixWrite2 || hasSaf2)

                            if (driveRootDir.exists()) {
                                result.add(
                                    DetectedStorage(
                                        id = uuid,
                                        label = "",
                                        displayName = displayName,
                                        nextcloudMountPoint = displayName,
                                        path = chosenDir.absolutePath,
                                        displayPath = chosenDir.absolutePath,
                                        isFullDrive = isFullDrive,
                                        isWritable = isActualWritable2,
                                        fsType = fsType,
                                        totalBytes = total,
                                        freeBytes = free,
                                        hasSafAccess = hasSaf2,
                                        isReadOnlyFs = isReadOnlyFs,
                                        isSdCard = isSdCard
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

        return result
    }

    /**
     * Determines whether a storage volume represents a physical MicroSD / SD card.
     * Evaluates description text, Linux block device major number (179 = MMC/SD),
     * StorageVolume ID, and /proc/mounts entries.
     */
    private fun isSdCardVolume(volume: StorageVolume?, uuid: String, desc: String): Boolean {
        var volId: String? = null
        if (volume != null) {
            try {
                val method = volume.javaClass.getMethod("getId")
                volId = method.invoke(volume) as? String
            } catch (_: Exception) {}
        }

        val mountLines = try {
            val mountsFile = File("/proc/mounts")
            if (mountsFile.exists() && mountsFile.canRead()) mountsFile.readLines() else emptyList()
        } catch (_: Exception) {
            emptyList<String>()
        }

        val voldEntries = try {
            val voldDir = File("/dev/block/vold")
            if (voldDir.exists()) voldDir.list()?.toList() ?: emptyList() else emptyList()
        } catch (_: Exception) {
            emptyList<String>()
        }

        return isSdCardClassification(desc, volId, mountLines, voldEntries, uuid)
    }

    /**
     * Determines if a volume is an NVMe or external solid-state drive (SSD).
     */
    private fun isSsdVolume(desc: String, uuid: String): Boolean {
        val lower = desc.lowercase()
        if (lower.contains("ssd") || lower.contains("nvme")) return true
        try {
            val mountsFile = File("/proc/mounts")
            if (mountsFile.exists() && mountsFile.canRead()) {
                for (line in mountsFile.readLines()) {
                    if (line.contains(uuid, ignoreCase = true)) {
                        val lowerLine = line.lowercase()
                        if (lowerLine.contains("nvme") || lowerLine.contains("public:259")) {
                            return true
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return false
    }

    /**
     * Resolves the filesystem format (FAT32, exFAT, NTFS, ext4) by inspecting /proc/mounts.
     */
    private fun getFilesystemType(uuidOrPath: String): String {
        try {
            val mountsFile = File("/proc/mounts")
            if (mountsFile.exists() && mountsFile.canRead()) {
                for (line in mountsFile.readLines()) {
                    if (line.contains(uuidOrPath, ignoreCase = true)) {
                        return parseFilesystemTypeFromMountLine(line)
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Error resolving filesystem format: ${e.message}")
        }
        return "FAT32 / exFAT"
    }

    /**
     * Checks if the app holds full storage access (MANAGE_EXTERNAL_STORAGE on Android 11+, or WRITE_EXTERNAL_STORAGE on Android 8-10).
     */
    private fun hasExternalStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Checks write permission on a directory safely and definitively using a real probe write test.
     */
    private fun isDirectoryWritable(dir: File): Boolean {
        if (!dir.exists() || !dir.isDirectory) return false
        val probe = File(dir, "nc_write_probe_${System.currentTimeMillis()}.tmp")
        return try {
            if (probe.createNewFile()) {
                probe.delete()
                true
            } else {
                dir.canWrite()
            }
        } catch (e: Exception) {
            Log.d(TAG, "Write probe on ${dir.absolutePath} failed: ${e.message}")
            dir.canWrite()
        }
    }

    private fun enableFilesExternal() {
        executeOcc("app:enable", "files_external")
    }

    private fun getExistingNextcloudMounts(): List<ExistingMount> {
        val mounts = mutableListOf<ExistingMount>()
        try {
            val output = executeOcc("files_external:list", "--output=json")
            if (output.isNotBlank()) {
                val jsonStart = output.indexOf('[')
                val jsonEnd = output.lastIndexOf(']')
                if (jsonStart != -1 && jsonEnd != -1 && jsonEnd > jsonStart) {
                    val jsonArray = JSONArray(output.substring(jsonStart, jsonEnd + 1))
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        val id = obj.optInt("mount_id", -1)
                        val mp = obj.optString("mount_point", "").trimStart('/')
                        val conf = obj.optJSONObject("configuration")
                        val datadir = conf?.optString("datadir", "") ?: ""
                        if (id != -1 && mp.isNotEmpty()) {
                            mounts.add(ExistingMount(id, mp, datadir))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse existing files_external mounts: ${e.message}")
        }
        return mounts
    }

    /**
     * Ensures standard POSIX permissions using standard Android SDK file APIs.
     * Universal across all Android devices. No root permissions required.
     */
    private fun ensureDirectoryPermissions(dir: File) {
        try {
            if (!dir.exists()) {
                dir.mkdirs()
            }
            dir.setReadable(true, false)
            dir.setWritable(true, false)
            dir.setExecutable(true, false)
        } catch (_: Exception) {}
    }

    private fun addNextcloudMount(mountPoint: String, dataDir: String): Int? {
        val cleanPoint = mountPoint.trimStart('/')
        val dFile = File(dataDir)
        ensureDirectoryPermissions(dFile)

        val output = executeOcc(
            "files_external:create",
            cleanPoint,
            "local",
            "null::null",
            "-c",
            "datadir=$dataDir"
        )

        val regex = Regex("Storage created with id (\\d+)")
        val match = regex.find(output)
        val mountId = match?.groupValues?.get(1)?.toIntOrNull()
        if (mountId != null) {
            try {
                executeOcc("files_external:option", mountId.toString(), "enable_sharing", "true")
                executeOcc("files_external:option", mountId.toString(), "filesystem_check_changes", "1")
            } catch (_: Exception) {}
        }
        return mountId
    }

    private fun removeNextcloudMount(mountId: Int) {
        val output = executeOcc("files_external:delete", "-y", mountId.toString())
        Log.i(TAG, "Deleted external mount $mountId: $output")
    }

    private fun scanNextcloudMount(mountPoint: String) {
        val cleanPoint = mountPoint.trimStart('/')
        Log.i(TAG, "Triggering Nextcloud file scan for mount point: $cleanPoint")
        val adminUser = AppPreferences(context).adminUser
        scheduledExecutor.execute {
            try {
                val out = executeOcc("files:scan", "--shallow", "--path=/$adminUser/files/$cleanPoint")
                Log.d(TAG, "files:scan output: $out")
            } catch (e: Exception) {
                Log.w(TAG, "Background scan for $cleanPoint failed: ${e.message}")
            }
        }
    }

    private fun executeOcc(vararg args: String): String {
        return nextcloudManager.executeOcc(*args)
    }

    /**
     * Formatted multi-storage summary for the UI.
     */
    fun getActiveDrivesSummary(): String {
        val storages = getRemovableStorages()
        if (storages.isEmpty()) {
            return "No removable storage media connected.\nMonitoring for MicroSD cards and USB OTG drives"
        }
        return storages.joinToString("\n\n") { s ->
            val totalGb = String.format(java.util.Locale.US, "%.1f GB", s.totalBytes / (1024.0 * 1024.0 * 1024.0))
            val freeGb = String.format(java.util.Locale.US, "%.1f GB", s.freeBytes / (1024.0 * 1024.0 * 1024.0))
            val labelPart = if (s.label.isNotBlank() && !s.label.equals(s.id, ignoreCase = true)) " [${s.label}]" else ""
            "• ${s.displayName}$labelPart ($totalGb, $freeGb free)\n  Path: ${s.displayPath}"
        }
    }
}
