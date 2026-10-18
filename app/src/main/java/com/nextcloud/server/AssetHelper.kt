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

    fun unzipAsset(context: Context, assetPath: String, targetDir: String) {
        val targetFileDir = File(targetDir)
        if (!targetFileDir.exists()) targetFileDir.mkdirs()

        context.assets.open(assetPath).use { input ->
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val file = File(targetDir, entry.name)
                    if (entry.isDirectory) {
                        file.mkdirs()
                    } else {
                        file.parentFile?.mkdirs()
                        FileOutputStream(file).use { output ->
                            zip.copyTo(output)
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
    }

    fun makeExecutable(filePath: String) {
        val file = File(filePath)
        if (file.exists()) {
            if (!file.setExecutable(true, false)) {
                // Fallback to chmod 755
                try {
                    Runtime.getRuntime().exec("chmod 755 $filePath").waitFor()
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }
    }
}
