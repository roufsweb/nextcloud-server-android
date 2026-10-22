package com.nextcloud.server

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.OutputStream

/**
 * Manages Storage Access Framework (SAF) URI permissions, document trees,
 * and high-performance file operations for external storage media
 * (SD cards, USB OTG flash drives, external HDDs/SSDs) without root.
 *
 * Supported across Android 8.0 (API 26) through Android 15 (API 35).
 */
class SdCardSafManager(private val context: Context) {

    companion object {
        private const val TAG = "SdCardSafManager"
        private const val PREFS_NAME = "NextcloudSafPrefs"
        private const val KEY_PREFIX = "saf_uri_"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun normalizeUuid(storageUuid: String): String {
        return storageUuid.removeSuffix("_root").removeSuffix("_uploads").trim().uppercase()
    }

    /**
     * Persists the SAF tree URI grant permanently across reboots.
     */
    fun saveSafUri(storageUuid: String, uri: Uri) {
        val cleanUuid = normalizeUuid(storageUuid)
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to take persistable URI permission for $cleanUuid: ${e.message}")
        }

        prefs.edit().putString("$KEY_PREFIX$cleanUuid", uri.toString()).apply()
        Log.i(TAG, "SAF URI saved permanently for storage volume: $cleanUuid -> $uri")
    }

    /**
     * Retrieves the stored SAF tree URI for a storage volume.
     * Checks normalized uppercase first, falling back to lowercase.
     */
    fun getSafUri(storageUuid: String): Uri? {
        val cleanUuid = normalizeUuid(storageUuid)
        val uriStr = prefs.getString("$KEY_PREFIX$cleanUuid", null)
            ?: prefs.getString("$KEY_PREFIX${cleanUuid.lowercase()}", null)
            ?: return null
        return try {
            Uri.parse(uriStr)
        } catch (e: Exception) {
            Log.w(TAG, "Invalid SAF URI for $cleanUuid: $uriStr")
            null
        }
    }

    /**
     * Validates whether write permission is active and valid for the given storage.
     */
    fun hasSafAccess(storageUuid: String): Boolean {
        val uri = getSafUri(storageUuid) ?: return false
        return try {
            val persisted = context.contentResolver.persistedUriPermissions
            val hasPermission = persisted.any { it.uri == uri && it.isWritePermission }
            if (!hasPermission) {
                // Double check if tree document can actually be accessed
                val doc = DocumentFile.fromTreeUri(context, uri)
                doc != null && doc.canWrite()
            } else {
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking SAF access for $storageUuid: ${e.message}")
            false
        }
    }

    /**
     * Revokes and removes the stored SAF URI.
     */
    fun revokeSafUri(storageUuid: String) {
        val cleanUuid = normalizeUuid(storageUuid)
        val uri = getSafUri(cleanUuid)
        if (uri != null) {
            try {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.releasePersistableUriPermission(uri, flags)
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing persistable URI for $cleanUuid: ${e.message}")
            }
        }
        prefs.edit().remove("$KEY_PREFIX$cleanUuid").remove("$KEY_PREFIX${cleanUuid.lowercase()}").apply()
        Log.i(TAG, "Revoked SAF URI for storage volume: $cleanUuid")
    }

    /**
     * Lists all storage UUIDs with valid, active SAF permissions.
     */
    fun getAllGrantedStorageUuids(): List<String> {
        return prefs.all.keys
            .filter { it.startsWith(KEY_PREFIX) }
            .map { it.removePrefix(KEY_PREFIX).uppercase() }
            .distinct()
            .filter { hasSafAccess(it) }
    }

    /**
     * Returns DocumentFile pointing to the root of the external drive.
     */
    fun getRootDocumentFile(storageUuid: String): DocumentFile? {
        val uri = getSafUri(storageUuid) ?: return null
        return try {
            val root = DocumentFile.fromTreeUri(context, uri)
            if (root != null && root.exists() && root.canWrite()) {
                root
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve root DocumentFile for $storageUuid: ${e.message}")
            null
        }
    }

    /**
     * Traverses or creates directories along the relative path on external storage.
     * e.g. relativePath = "DCIM/Camera" or "Photos/Vacation"
     */
    fun getOrCreateFolder(storageUuid: String, relativePath: String): DocumentFile? {
        var current = getRootDocumentFile(storageUuid) ?: return null
        val cleanPath = relativePath.trim('/').replace('\\', '/')
        if (cleanPath.isEmpty()) return current

        val segments = cleanPath.split("/").filter { it.isNotEmpty() }
        for (seg in segments) {
            val existing = current.findFile(seg)
            current = if (existing != null && existing.isDirectory) {
                existing
            } else if (existing != null && !existing.isDirectory) {
                Log.e(TAG, "Path conflict: '$seg' exists and is a file, not a directory")
                return null
            } else {
                val created = current.createDirectory(seg)
                if (created == null) {
                    Log.e(TAG, "Failed to create directory '$seg' under ${current.uri}")
                    return null
                }
                created
            }
        }
        return current
    }

    /**
     * Opens a temporary output stream (.nctmp) for atomic writing.
     * Prevents partial/corrupted files from appearing in Nextcloud until 100% written.
     */
    fun openTempWriteStream(
        storageUuid: String,
        relativeDir: String,
        fileName: String,
        mimeType: String
    ): Pair<DocumentFile, OutputStream>? {
        val folder = getOrCreateFolder(storageUuid, relativeDir) ?: run {
            Log.e(TAG, "Target folder could not be resolved for $storageUuid:$relativeDir")
            return null
        }

        val tmpName = ".nc_tmp_${System.currentTimeMillis()}_$fileName"

        // Delete any stale temp file from previous failed attempts
        try {
            folder.findFile(tmpName)?.delete()
        } catch (_: Exception) {}

        // Use application/octet-stream so DocumentsContract does not append extra extensions
        val tmpDoc = folder.createFile("application/octet-stream", tmpName) ?: run {
            Log.e(TAG, "Failed to create temp DocumentFile '$tmpName' in ${folder.uri}")
            return null
        }

        val os = try {
            context.contentResolver.openOutputStream(tmpDoc.uri, "wt")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open OutputStream for '${tmpDoc.uri}': ${e.message}")
            try { tmpDoc.delete() } catch (_: Exception) {}
            null
        } ?: return null

        return Pair(tmpDoc, os)
    }

    /**
     * Atomically finalizes the .nctmp file into its target name.
     * If a file with the destination name already exists, deletes it first so the rename succeeds.
     */
    fun finalizeTempFile(tmpDocFile: DocumentFile, finalName: String, parentFolder: DocumentFile? = null): Boolean {
        return try {
            // Check if existing file with target name exists in the parent
            parentFolder?.findFile(finalName)?.let { existing ->
                if (existing.exists() && existing.isFile) {
                    existing.delete()
                }
            }

            // DocumentFile.renameTo updates its internal URI and returns true on success
            val renamed = tmpDocFile.renameTo(finalName)
            if (renamed) {
                true
            } else {
                // Direct fallback via DocumentsContract
                val newUri = DocumentsContract.renameDocument(
                    context.contentResolver,
                    tmpDocFile.uri,
                    finalName
                )
                newUri != null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finalizing temp file to '$finalName': ${e.message}")
            false
        }
    }

    /**
     * Deletes a file on external storage via SAF.
     */
    fun deleteFile(storageUuid: String, relativePath: String): Boolean {
        val cleanPath = relativePath.trim('/').replace('\\', '/')
        if (cleanPath.isEmpty()) return false

        val parts = cleanPath.split("/").filter { it.isNotEmpty() }
        val fileName = parts.last()
        val dirPath = parts.dropLast(1).joinToString("/")

        val folder = getOrCreateFolder(storageUuid, dirPath) ?: return false
        val file = folder.findFile(fileName) ?: return false
        return try {
            file.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting file via SAF '$cleanPath': ${e.message}")
            false
        }
    }

    /**
     * Checks if a file or folder exists on external storage via SAF.
     */
    fun exists(storageUuid: String, relativePath: String): Boolean {
        val cleanPath = relativePath.trim('/').replace('\\', '/')
        if (cleanPath.isEmpty()) return getRootDocumentFile(storageUuid) != null

        val parts = cleanPath.split("/").filter { it.isNotEmpty() }
        val targetName = parts.last()
        val dirPath = parts.dropLast(1).joinToString("/")

        val folder = getOrCreateFolder(storageUuid, dirPath) ?: return false
        return folder.findFile(targetName) != null
    }

    /**
     * Cleans up orphaned .nctmp files on startup.
     */
    fun cleanupOrphanedTempFiles(storageUuid: String) {
        try {
            val root = getRootDocumentFile(storageUuid) ?: return
            cleanupRecursive(root, 0)
        } catch (e: Exception) {
            Log.w(TAG, "Error cleaning temp files on $storageUuid: ${e.message}")
        }
    }

    private fun cleanupRecursive(dir: DocumentFile, depth: Int) {
        if (depth > 6) return // Guard against excessive nesting
        for (item in dir.listFiles()) {
            try {
                if (item.isDirectory) {
                    cleanupRecursive(item, depth + 1)
                } else if (item.name?.startsWith(".nc_tmp_") == true || item.name?.contains(".nctmp") == true) {
                    Log.i(TAG, "Removing orphaned temporary upload: ${item.name}")
                    item.delete()
                }
            } catch (_: Exception) {}
        }
    }
}
