package com.nextcloud.server

import android.content.Context
import android.content.Intent
import android.os.FileObserver
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * High-performance, zero-corruption background relay service that monitors
 * incoming upload dropboxes and streams/moves files into the true root or
 * subdirectories of external storage media (SD cards, USB OTG drives, SSDs)
 * via the Storage Access Framework (SAF).
 *
 * Prevents partial writes using .nctmp atomic renaming.
 * Supports multi-storage parallel streaming and automated Nextcloud index rescans.
 */
class UploadRelayService(
    private val context: Context,
    private val safManager: SdCardSafManager,
    private val nextcloudManager: NextcloudManager
) {

    companion object {
        private const val TAG = "UploadRelayService"
        private const val CHUNK_SIZE = 65536 // 64 KB buffer
        private const val TAIL_POLL_INTERVAL_MS = 60L
    }

    private val executor = Executors.newCachedThreadPool()
    private val activeSessions = ConcurrentHashMap<String, RelaySession>()
    private val activeObservers = ConcurrentHashMap<String, FileObserver>()
    private val activeTransferCount = AtomicInteger(0)

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var relayWakeLock: PowerManager.WakeLock? = null

    data class RelaySession(
        val storageUuid: String,
        val relativeDir: String,
        val fileName: String,
        val relayFile: File,
        var tmpDocFile: androidx.documentfile.provider.DocumentFile?,
        var targetFolderDoc: androidx.documentfile.provider.DocumentFile?,
        var outputStream: OutputStream?,
        var lastByteOffset: Long = 0L,
        @Volatile var isClosed: Boolean = false,
        @Volatile var isFailed: Boolean = false
    )

    /**
     * Resolves the designated relay/dropbox directory for a storage UUID.
     * Prefers the app-scoped directory directly on the external volume to save internal storage,
     * falling back to internal app storage.
     */
    fun getRelayDir(storageUuid: String): File {
        val cleanUuid = storageUuid.removeSuffix("_root").removeSuffix("_uploads")
        val externalDirs = ContextCompat.getExternalFilesDirs(context, null)
        val matchingExternal = externalDirs.firstOrNull { it != null && it.absolutePath.contains(cleanUuid) }

        val baseDir = matchingExternal?.let { File(it, "Nextcloud") }
            ?: File(context.filesDir, "relay/$cleanUuid")

        if (!baseDir.exists()) {
            baseDir.mkdirs()
        }
        return baseDir
    }

    /**
     * Starts monitoring relay directories for the specified storage UUIDs.
     */
    fun startWatching(storageUuids: List<String>) {
        Log.i(TAG, "Native direct SD card storage active; background relay watcher not required.")
    }

    /**
     * Stops monitoring one or all storage relay directories.
     */
    fun stopWatching(storageUuid: String? = null) {
        if (storageUuid != null) {
            val cleanUuid = storageUuid.removeSuffix("_root").removeSuffix("_uploads")
            val keysToRemove = activeObservers.keys.filter { it.startsWith("$cleanUuid::") || it == cleanUuid }
            for (k in keysToRemove) {
                activeObservers[k]?.stopWatching()
                activeObservers.remove(k)
            }
            Log.i(TAG, "Stopped watching relay directories for $cleanUuid")
        } else {
            for ((_, observer) in activeObservers) {
                try { observer.stopWatching() } catch (_: Exception) {}
            }
            activeObservers.clear()
            Log.i(TAG, "Stopped all storage relay watchers")
        }
        if (activeObservers.isEmpty()) {
            releaseWakeLock()
        }
    }

    private fun startWatchingStorage(storageUuid: String) {
        val relayDir = getRelayDir(storageUuid)
        Log.i(TAG, "Starting recursive relay monitoring for $storageUuid at: ${relayDir.absolutePath}")

        // Recover any complete or orphaned files from previous session
        executor.execute {
            recoverOrphanFiles(storageUuid, relayDir)
        }

        // Attach recursive file observers
        startRecursiveObserver(storageUuid, relayDir)
    }

    private fun startRecursiveObserver(storageUuid: String, dir: File) {
        if (!dir.exists() || !dir.isDirectory) return

        val obsKey = "$storageUuid::${dir.absolutePath}"
        if (activeObservers.containsKey(obsKey)) return

        val events = FileObserver.OPEN or FileObserver.CLOSE_WRITE or FileObserver.CREATE or FileObserver.MOVED_TO
        val eventHandler: (Int, String?) -> Unit = { event, path ->
            if (path != null && !path.startsWith(".") && !path.contains(".nctmp") && !path.contains(".part") && !path.contains("ocTransferId")) {
                val file = File(dir, path)
                when (event and FileObserver.ALL_EVENTS) {
                    FileObserver.CREATE -> {
                        if (file.isDirectory) {
                            startRecursiveObserver(storageUuid, file)
                        }
                    }
                    FileObserver.OPEN -> {
                        if (file.isFile) {
                            onFileOpened(storageUuid, getRelayDir(storageUuid), file)
                        }
                    }
                    FileObserver.MOVED_TO -> {
                        if (file.isDirectory) {
                            startRecursiveObserver(storageUuid, file)
                        } else if (file.isFile) {
                            onFileComplete(storageUuid, getRelayDir(storageUuid), file)
                        }
                    }
                    FileObserver.CLOSE_WRITE -> {
                        if (file.isFile) {
                            onFileComplete(storageUuid, getRelayDir(storageUuid), file)
                        }
                    }
                }
            }
        }

        @Suppress("DEPRECATION")
        val observer = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            object : FileObserver(dir, events) {
                override fun onEvent(event: Int, path: String?) = eventHandler(event, path)
            }
        } else {
            object : FileObserver(dir.absolutePath, events) {
                override fun onEvent(event: Int, path: String?) = eventHandler(event, path)
            }
        }

        try {
            observer.startWatching()
            activeObservers[obsKey] = observer
        } catch (e: Exception) {
            Log.w(TAG, "Failed to attach FileObserver to ${dir.absolutePath}: ${e.message}")
        }

        // Recursively attach to all existing subdirectories
        dir.listFiles()?.filter { it.isDirectory }?.forEach { sub ->
            startRecursiveObserver(storageUuid, sub)
        }
    }

    private fun onFileOpened(storageUuid: String, rootRelayDir: File, file: File) {
        val sessionKey = file.absolutePath
        if (activeSessions.containsKey(sessionKey)) return

        executor.execute {
            try {
                if (!safManager.hasSafAccess(storageUuid)) {
                    Log.d(TAG, "SAF permission not granted for $storageUuid; skipping parallel live stream")
                    return@execute
                }

                val relativeDir = computeRelativeDir(rootRelayDir, file)
                val fileName = file.name
                val mimeType = resolveMimeType(fileName)

                val pair = safManager.openTempWriteStream(storageUuid, relativeDir, fileName, mimeType)
                if (pair == null) {
                    Log.d(TAG, "Could not open SAF temp stream for $fileName")
                    return@execute
                }

                val parentFolder = safManager.getOrCreateFolder(storageUuid, relativeDir)
                val session = RelaySession(
                    storageUuid = storageUuid,
                    relativeDir = relativeDir,
                    fileName = fileName,
                    relayFile = file,
                    tmpDocFile = pair.first,
                    targetFolderDoc = parentFolder,
                    outputStream = pair.second,
                    lastByteOffset = 0L
                )

                activeSessions[sessionKey] = session
                acquireWakeLock()
                Log.d(TAG, "Live SAF relay session initiated: $fileName ($storageUuid/$relativeDir)")

                // Launch chunk tailer in background
                startTailingChunks(session, sessionKey)

            } catch (e: Exception) {
                Log.w(TAG, "Error in onFileOpened for ${file.name}: ${e.message}")
            }
        }
    }

    private fun startTailingChunks(session: RelaySession, sessionKey: String) {
        executor.execute {
            while (!session.isClosed && !session.isFailed && activeSessions.containsKey(sessionKey)) {
                try {
                    val bytesPiped = pipeAvailableBytes(session)
                    if (bytesPiped == 0L) {
                        Thread.sleep(TAIL_POLL_INTERVAL_MS)
                    }
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Chunk tailing interrupted for ${session.fileName}: ${e.message}")
                    session.isFailed = true
                    break
                }
            }
        }
    }

    private fun pipeAvailableBytes(session: RelaySession): Long {
        val f = session.relayFile
        if (!f.exists()) return 0L

        val curLen = f.length()
        if (curLen <= session.lastByteOffset) return 0L

        val os = session.outputStream ?: return 0L

        return try {
            FileInputStream(f).use { fis ->
                fis.skip(session.lastByteOffset)
                val buf = ByteArray(CHUNK_SIZE)
                var totalWritten = 0L

                while (true) {
                    val r = fis.read(buf)
                    if (r <= 0) break
                    os.write(buf, 0, r)
                    totalWritten += r
                }
                os.flush()
                session.lastByteOffset += totalWritten
                totalWritten
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error tailing bytes from ${session.fileName}: ${e.message}")
            0L
        }
    }

    private fun onFileComplete(storageUuid: String, rootRelayDir: File, file: File) {
        val sessionKey = file.absolutePath

        executor.execute {
            acquireWakeLock()
            try {
                if (!safManager.hasSafAccess(storageUuid)) {
                    Log.w(TAG, "Cannot relay ${file.name} to $storageUuid: SAF permission not granted yet")
                    return@execute
                }

                val session = activeSessions[sessionKey]
                if (session != null && !session.isFailed) {
                    session.isClosed = true
                    // Flush any remaining tail bytes
                    pipeAvailableBytes(session)
                    session.outputStream?.flush()
                    try { session.outputStream?.close() } catch (_: Exception) {}

                    val tmpDoc = session.tmpDocFile
                    val parentDoc = session.targetFolderDoc
                    if (tmpDoc != null) {
                        val finalized = safManager.finalizeTempFile(tmpDoc, session.fileName, parentDoc)
                        if (finalized) {
                            Log.i(TAG, "SAF Relay Completed: ${session.fileName} -> $storageUuid/${session.relativeDir}/")
                            file.delete()
                            triggerNextcloudScan(storageUuid, session.relativeDir, session.fileName)
                        } else {
                            Log.e(TAG, "Failed to finalize temp file for ${session.fileName}; keeping relay copy for recovery")
                        }
                    }
                    activeSessions.remove(sessionKey)

                } else {
                    // Full streaming copy for files that closed before OPEN listener or via direct drop
                    performDirectRelay(storageUuid, rootRelayDir, file)
                    activeSessions.remove(sessionKey)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error finalizing relay for ${file.name}: ${e.message}", e)
                activeSessions.remove(sessionKey)
            } finally {
                checkReleaseWakeLock()
            }
        }
    }

    /**
     * Directly relays a fully-written file to external storage via SAF.
     */
    private fun performDirectRelay(storageUuid: String, rootRelayDir: File, file: File) {
        if (!file.exists() || !file.isFile || file.length() == 0L) return
        val relativeDir = computeRelativeDir(rootRelayDir, file)
        val fileName = file.name
        val mimeType = resolveMimeType(fileName)

        val pair = safManager.openTempWriteStream(storageUuid, relativeDir, fileName, mimeType) ?: return
        val tmpDoc = pair.first
        val os = pair.second

        try {
            FileInputStream(file).use { fis ->
                os.use { out ->
                    val buf = ByteArray(CHUNK_SIZE)
                    var r: Int
                    while (fis.read(buf).also { r = it } > 0) {
                        out.write(buf, 0, r)
                    }
                    out.flush()
                }
            }

            val parentDoc = safManager.getOrCreateFolder(storageUuid, relativeDir)
            val finalized = safManager.finalizeTempFile(tmpDoc, fileName, parentDoc)
            if (finalized) {
                Log.i(TAG, "Direct SAF Relay Completed: $fileName -> $storageUuid/$relativeDir/")
                file.delete()
                triggerNextcloudScan(storageUuid, relativeDir, fileName)
            } else {
                Log.e(TAG, "Direct finalize failed for $fileName")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in direct relay for $fileName: ${e.message}")
            try { tmpDoc.delete() } catch (_: Exception) {}
        }
    }

    /**
     * Recovers any complete orphaned files that were interrupted during a previous process exit.
     */
    private fun recoverOrphanFiles(storageUuid: String, rootRelayDir: File) {
        try {
            safManager.cleanupOrphanedTempFiles(storageUuid)

            rootRelayDir.walkTopDown()
                .filter { it.isFile && !it.name.startsWith(".") && !it.name.contains(".nctmp") && !it.name.contains(".part") && !it.name.contains("ocTransferId") }
                .forEach { orphan ->
                    if (orphan.length() > 0) {
                        Log.i(TAG, "Recovering orphaned relay file: ${orphan.name}")
                        performDirectRelay(storageUuid, rootRelayDir, orphan)
                    }
                }
        } catch (e: Exception) {
            Log.w(TAG, "Error recovering orphan relay files for $storageUuid: ${e.message}")
        }
    }

    /**
     * Triggers Nextcloud's occ files:scan command so that newly relayed files
     * immediately appear in the user's Nextcloud Web UI and mobile apps.
     */
    private fun triggerNextcloudScan(storageUuid: String, relativeDir: String, fileName: String) {
        try {
            val adminUser = AppPreferences(context).adminUser

            // Run OCC file scan in background executor
            executor.execute {
                try {
                    Log.i(TAG, "Triggering Nextcloud OCC file scan for $fileName on storage $storageUuid (subpath: $relativeDir)")
                    val out = nextcloudManager.executeOcc("files:scan", "--path=/$adminUser/files")
                    Log.d(TAG, "Nextcloud scan result for $fileName: $out")
                } catch (e: Exception) {
                    Log.w(TAG, "Error executing files:scan for $fileName: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to schedule files:scan: ${e.message}")
        }
    }

    private fun computeRelativeDir(rootRelayDir: File, file: File): String {
        val parent = file.parentFile ?: return ""
        val rel = parent.absolutePath.removePrefix(rootRelayDir.absolutePath).trim('/', '\\')
        return rel.replace('\\', '/')
    }

    private fun resolveMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "mov" -> "video/quicktime"
            "avi" -> "video/x-msvideo"
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "tar" -> "application/x-tar"
            "gz" -> "application/gzip"
            "apk" -> "application/vnd.android.package-archive"
            "txt", "md" -> "text/plain"
            "doc", "docx" -> "application/msword"
            else -> "application/octet-stream"
        }
    }

    @Synchronized
    private fun acquireWakeLock() {
        if (relayWakeLock == null) {
            relayWakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "NextcloudServer::UploadRelayWakeLock"
            )
        }
        if (relayWakeLock?.isHeld != true) {
            relayWakeLock?.acquire(45 * 60 * 1000L /* 45 minutes max */)
            Log.d(TAG, "Relay wake lock acquired")
        }
        activeTransferCount.incrementAndGet()
    }

    @Synchronized
    private fun checkReleaseWakeLock() {
        val count = activeTransferCount.decrementAndGet()
        if (count <= 0) {
            activeTransferCount.set(0)
            releaseWakeLock()
        }
    }

    @Synchronized
    private fun releaseWakeLock() {
        try {
            if (relayWakeLock?.isHeld == true) {
                relayWakeLock?.release()
                Log.d(TAG, "Relay wake lock released")
            }
        } catch (_: Exception) {}
        relayWakeLock = null
    }
}
