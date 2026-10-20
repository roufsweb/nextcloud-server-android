package com.nextcloud.server

import android.content.Context
import java.io.*
import java.util.zip.ZipInputStream

object AssetHelper {

    fun copyAssetFolder(context: Context, assetPath: String, targetDir: String) {
        val assetManager = context.assets
        val assets = assetManager.list(assetPath) ?: return
        
        val targetFileDir = File(targetDir)
        if (!targetFileDir.exists()) targetFileDir.mkdirs()

        for (asset in assets) {
            val fullAssetPath = if (assetPath.isEmpty()) asset else "$assetPath/$asset"
            val targetFile = File(targetDir, asset)
            
            val intentList = assetManager.list(fullAssetPath)
            if (intentList != null && intentList.isNotEmpty()) {
                // It's a directory
                copyAssetFolder(context, fullAssetPath, targetFile.absolutePath)
            } else {
                // It's a file
                copyAssetFile(context, fullAssetPath, targetFile.absolutePath)
            }
        }
    }

    private fun copyAssetFile(context: Context, assetPath: String, targetPath: String) {
        val targetFile = File(targetPath)
        val parent = targetFile.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        if (targetFile.exists()) {
            try {
                val fd = context.assets.openFd(assetPath)
                if (fd.length > 0 && fd.length == targetFile.length()) {
                    fd.close()
                    return
                }
                fd.close()
            } catch (e: Exception) {
                // If compressed asset, openFd may fail; if target already exists with size > 0, we can check
            }
        }
        try {
            context.assets.open(assetPath).use { input ->
                FileOutputStream(targetPath).use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: IOException) {
            // Text file busy happens if binary is running; safe to ignore if file exists
            if (targetFile.exists() && e.message?.contains("busy", ignoreCase = true) == true) {
                return
            }
            throw e
        }
    }

    fun unzipAsset(
        context: Context,
        assetPath: String,
        targetDir: String,
        totalEntries: Int = 33992,
        onProgress: ((percent: Int) -> Unit)? = null
    ) {
        val targetFileDir = File(targetDir)
        if (!targetFileDir.exists()) targetFileDir.mkdirs()

        val buffer = ByteArray(262144) // 256 KB buffer for high I/O throughput
        val createdDirs = HashSet<String>()
        markDirsCreated(targetFileDir, createdDirs)

        var entryCount = 0
        var lastReportedPct = -1

        context.assets.open(assetPath).use { rawInput ->
            java.io.BufferedInputStream(rawInput, 262144).use { bufferedInput ->
                ZipInputStream(bufferedInput).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        entryCount++
                        val file = File(targetDir, entry.name)
                        if (entry.isDirectory) {
                            if (!createdDirs.contains(file.absolutePath)) {
                                if (!file.exists()) {
                                    file.mkdirs()
                                }
                                markDirsCreated(file, createdDirs)
                            }
                        } else {
                            val parent = file.parentFile
                            if (parent != null && !createdDirs.contains(parent.absolutePath)) {
                                if (!parent.exists()) {
                                    parent.mkdirs()
                                }
                                markDirsCreated(parent, createdDirs)
                            }
                            if (file.exists() && file.isDirectory) {
                                file.deleteRecursively()
                            }
                            java.io.BufferedOutputStream(FileOutputStream(file), 262144).use { output ->
                                var bytesRead: Int
                                while (zip.read(buffer).also { bytesRead = it } != -1) {
                                    output.write(buffer, 0, bytesRead)
                                }
                            }
                        }
                        zip.closeEntry()

                        if (onProgress != null && (entryCount % 100 == 0 || entryCount == totalEntries)) {
                            val pct = ((entryCount * 100) / totalEntries).coerceIn(0, 100)
                            if (pct != lastReportedPct) {
                                lastReportedPct = pct
                                onProgress(pct)
                            }
                        }
                        entry = zip.nextEntry
                    }
                }
            }
        }
        onProgress?.invoke(100)
    }

    private fun markDirsCreated(dir: File?, createdDirs: HashSet<String>) {
        var curr: File? = dir
        while (curr != null && createdDirs.add(curr.absolutePath)) {
            curr = curr.parentFile
        }
    }

    fun makeExecutable(filePath: String) {
        val file = File(filePath)
        if (file.exists()) {
            file.setReadable(true, false)
            file.setExecutable(true, false)
            try {
                Runtime.getRuntime().exec(arrayOf("chmod", "755", filePath)).waitFor()
            } catch (_: Exception) {
            }
        }
    }
}
